package io.github.smssentinel.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import io.github.smssentinel.action.ActionExecutor
import io.github.smssentinel.core.RuleMatcher
import io.github.smssentinel.core.SmsSubject
import io.github.smssentinel.data.AppStore

/**
 * Fallback path for vendor ROMs that drop SMS_RECEIVED or delay content-observer
 * callbacks. It reads the SMS provider by increasing row id, so:
 *  - nothing is processed twice,
 *  - messages that arrived while the process was frozen are caught on the next wake,
 *  - it needs no broadcast and no observer.
 */
object SmsDatabaseWatcher {
    /** Process at most this many rows per poll to bound work after a long sleep. */
    private const val MAX_ROWS = 20

    /** Shared with the broadcast path so the same message never fires twice. */
    const val WINDOW_MS = 10_000L

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun poll(context: Context) {
        if (!hasPermission(context)) return
        val store = AppStore(context)
        val state = store.state()
        if (!state.enabled) return

        val since = store.lastSmsId()
        if (since == 0L) {
            // First run: remember the newest row instead of replaying the inbox.
            val seed = runCatching {
                context.contentResolver.query(
                    Uri.parse("content://sms/inbox"), arrayOf("_id"), null, null, "_id DESC",
                )?.use { rows -> if (rows.moveToFirst()) rows.getLong(0) else 0L } ?: 0L
            }.getOrDefault(0L)
            if (seed > 0L) store.seedLastSmsIdIfUnset(seed)
            return
        }
        val cursor = runCatching {
            context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id", "address", "body", "date", "sub_id"),
                "_id > ?", arrayOf(since.toString()), "_id ASC",
            )
        }.getOrNull() ?: return

        val pending = mutableListOf<Array<Any>>()
        cursor.use { rows ->
            while (rows.moveToNext() && pending.size < MAX_ROWS) {
                val subscriptionId = if (rows.getColumnIndex("sub_id") >= 0) rows.getInt(4) else -1
                pending += arrayOf(
                    rows.getLong(0),
                    rows.getString(1).orEmpty(),
                    rows.getString(2).orEmpty(),
                    rows.getLong(3),
                    subscriptionId,
                )
            }
        }
        if (pending.isEmpty()) return

        pending.forEach { row ->
            val id = row[0] as Long
            val address = row[1] as String
            val body = row[2] as String
            val date = row[3] as Long
            val subscriptionId = row[4] as Int
            handle(context, address, body, date, subscriptionId)
            store.setLastSmsId(id)
        }
    }

    /** De-duplication, matching and dispatch for one row. Testable without a provider. */
    fun handle(context: Context, address: String, body: String, date: Long, subscriptionId: Int) {
        val store = AppStore(context)
        val state = store.state()
        if (!state.enabled) return

        // The broadcast path may have handled this row already.
        val fingerprint = io.github.smssentinel.core.MessageFingerprint.of(
            address, body, date / WINDOW_MS, subscriptionId,
        )
        if (store.isDuplicate(fingerprint)) return

        val delaySeconds = (System.currentTimeMillis() - date) / 1000
        val subject = SmsSubject(
            body = body,
            sender = address,
            localNumber = PhoneNumbers.forSubscription(context, subscriptionId),
        )
        val hits = RuleMatcher.matched(state.activeRules(), subject)
        store.log(
            if (delaySeconds >= 5) "补查到位（延迟 ${delaySeconds} 秒），命中 ${hits.size} 条规则"
            else "补查到位，命中 ${hits.size} 条规则",
        )
        if (hits.isNotEmpty()) ActionExecutor.dispatch(context, hits, subject)
    }
}
