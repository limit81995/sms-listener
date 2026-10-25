package io.github.smslistener.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.smslistener.core.AlarmRule
import io.github.smslistener.core.ForwardTarget
import io.github.smslistener.core.SentinelState
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("smslistener", Context.MODE_PRIVATE)

    fun observe(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(listener)
    fun removeObserver(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(listener)

    fun state(): SentinelState = synchronized(lock) {
        val raw = prefs.getString(KEY_STATE, null)
        if (raw != null) return@synchronized try {
            RuleCodec.decode(raw)
        } catch (e: Exception) {
            Log.e("SmsListener", "Rule list could not be read; monitoring disabled", e)
            SentinelState()
        }
        // One-time migration from the single-rule schema.
        val legacy = prefs.getString(KEY_LEGACY, null) ?: return@synchronized SentinelState()
        try {
            RuleCodec.decode(legacy).also { migrated ->
                prefs.edit().putString(KEY_STATE, RuleCodec.encode(migrated)).apply()
                log("已从旧版配置迁移 ${migrated.rules.size} 条规则")
            }
        } catch (e: Exception) {
            Log.e("SmsListener", "Legacy configuration could not be migrated", e)
            SentinelState()
        }
    }

    fun saveState(state: SentinelState): Boolean = synchronized(lock) {
        prefs.edit().putString(KEY_STATE, RuleCodec.encode(state)).commit()
    }

    fun rules(): List<AlarmRule> = state().rules

    fun saveRule(rule: AlarmRule): Boolean = synchronized(lock) {
        val current = state()
        val index = current.rules.indexOfFirst { it.id == rule.id }
        val next = if (index >= 0) current.rules.toMutableList().apply { this[index] = rule }
        else current.rules + rule
        saveState(current.copy(rules = next))
    }

    fun deleteRule(id: String): Boolean = synchronized(lock) {
        val current = state()
        saveState(current.copy(rules = current.rules.filterNot { it.id == id }))
    }

    fun rule(id: String): AlarmRule? = rules().firstOrNull { it.id == id }

    fun setEnabled(enabled: Boolean): Boolean = synchronized(lock) { saveState(state().copy(enabled = enabled)) }

    fun saveForward(target: ForwardTarget): Boolean = synchronized(lock) {
        val current = state()
        val index = current.forwards.indexOfFirst { it.id == target.id }
        val next = if (index >= 0) current.forwards.toMutableList().apply { this[index] = target }
        else current.forwards + target
        saveState(current.copy(forwards = next))
    }

    fun deleteForward(id: String): Boolean = synchronized(lock) {
        val current = state()
        val remainingRules = current.rules.map { rule ->
            rule.copy(actions = rule.actions.map { action ->
                if (action.forwardIds.contains(id)) action.copy(forwardIds = action.forwardIds - id) else action
            })
        }
        saveState(current.copy(forwards = current.forwards.filterNot { it.id == id }, rules = remainingRules))
    }

    fun forward(id: String): ForwardTarget? = state().forwards.firstOrNull { it.id == id }

    /** Highest SMS row id already routed through the rules. */
    fun lastSmsId(): Long = synchronized(lock) { prefs.getLong(KEY_LAST_SMS_ID, 0L) }

    fun setLastSmsId(id: Long) = synchronized(lock) {
        if (id > prefs.getLong(KEY_LAST_SMS_ID, 0L)) prefs.edit().putLong(KEY_LAST_SMS_ID, id).apply()
    }

    /** Seeds the cursor on first run so existing messages are never replayed. */
    fun seedLastSmsIdIfUnset(id: Long) = synchronized(lock) {
        if (prefs.getLong(KEY_LAST_SMS_ID, 0L) == 0L) prefs.edit().putLong(KEY_LAST_SMS_ID, id).apply()
    }

    /** Store no SMS body, phone number, matched keyword or exception message. */
    fun log(event: String, error: Throwable? = null) = synchronized(lock) {
        val message = if (error == null) event else "$event [${error.javaClass.simpleName}]"
        if (error == null) Log.i("SmsListener", message) else Log.e("SmsListener", message)
        val old = readArray("logs")
        val entries = JSONArray().put(JSONObject().put("time", System.currentTimeMillis()).put("event", message))
        for (i in 0 until minOf(old.length(), 199)) entries.put(old.getJSONObject(i))
        prefs.edit().putString("logs", entries.toString()).apply()
    }

    fun logs(): String = synchronized(lock) {
        val entries = readArray("logs")
        val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
        (0 until entries.length()).joinToString("\n\n") {
            val entry = entries.getJSONObject(it)
            "${format.format(Date(entry.getLong("time")))}  ${entry.getString("event")}"
        }.ifEmpty { "还没有运行记录" }
    }

    fun clearLogs() = synchronized(lock) { prefs.edit().remove("logs").apply() }

    /** Persist a small rolling set; process recreation must not replay the same broadcast. */
    @SuppressLint("ApplySharedPref") // On SMS worker: persist before finishing the broadcast.
    fun isDuplicate(fingerprint: String): Boolean = synchronized(lock) {
        val old = readArray("seen")
        if ((0 until old.length()).any { old.optString(it) == fingerprint }) return@synchronized true
        val next = JSONArray().put(fingerprint)
        for (i in 0 until minOf(old.length(), 63)) next.put(old.getString(i))
        prefs.edit().putString("seen", next.toString()).commit()
        false
    }

    private fun readArray(key: String): JSONArray = try {
        JSONArray(prefs.getString(key, "[]"))
    } catch (_: Exception) { JSONArray() }

    private companion object {
        val lock = Any()
        const val KEY_STATE = "state_v2"
        const val KEY_LEGACY = "config"
        const val KEY_LAST_SMS_ID = "last_sms_id"
    }
}
