package io.github.smslistener.sms

import android.content.Context
import android.content.Intent
import android.provider.Telephony
import io.github.smslistener.action.ActionExecutor
import io.github.smslistener.core.MessageFingerprint
import io.github.smslistener.core.RuleMatcher
import io.github.smslistener.core.SmsSubject
import io.github.smslistener.data.AppStore

/** Synchronous SMS pipeline, invoked on the receiver's worker thread. */
class SmsProcessor(private val context: Context) {
    fun process(intent: Intent) {
        val store = AppStore(context)
        val state = store.state()
        if (!state.enabled) {
            // Silent drops made "why no alarm" impossible to diagnose from the log.
            store.log("收到短信，但监听未开启，已忽略")
            return
        }
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isNullOrEmpty()) {
            store.log("短信解析失败：没有可读取的分段")
            return
        }
        // Android delivers a multipart SMS together and in PDU order.
        val body = parts.joinToString("") { it.messageBody.orEmpty() }
        val sender = parts.first().originatingAddress.orEmpty()
        val subscriptionId = intent.getIntExtra("subscription", -1)
        val subject = SmsSubject(
            body = body,
            sender = sender,
            localNumber = PhoneNumbers.forSubscription(context, subscriptionId),
        )
        // Same 10s bucket as the database channel so the two paths never double-fire.
        val fingerprint = MessageFingerprint.of(
            sender, body, parts.first().timestampMillis / SmsDatabaseWatcher.WINDOW_MS, subscriptionId,
        )
        if (store.isDuplicate(fingerprint)) {
            store.log("已跳过重复的短信广播")
            return
        }
        val hits = RuleMatcher.matched(state.activeRules(), subject)
        store.log("收到短信（${parts.size} 段），命中 ${hits.size} 条规则")
        if (hits.isNotEmpty()) ActionExecutor.dispatch(context, hits, subject)
        else store.log("短信未启动报警：请检查已保存的条件；任意短信需三项留空并保存整条规则")
    }
}
