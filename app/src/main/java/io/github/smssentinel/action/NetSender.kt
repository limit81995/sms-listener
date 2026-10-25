package io.github.smssentinel.action

import android.util.Base64
import io.github.smssentinel.core.AlarmRule
import io.github.smssentinel.core.ForwardTarget
import io.github.smssentinel.core.ForwardType
import io.github.smssentinel.core.SmsSubject
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Sends one forward target. Blocking; always called from a background executor. */
object NetSender {
    private const val TIMEOUT_MS = 10_000

    fun send(target: ForwardTarget, rule: AlarmRule, subject: SmsSubject) {
        val payload = when (target.type) {
            ForwardType.FEISHU -> feishuPayload(target, rule, subject)
            ForwardType.HTTP -> if (target.body.isBlank() && target.method.uppercase() == "GET") ""
            else if (target.body.isNotBlank()) render(target.body, rule, subject)
            else defaultJson(rule, subject)
        }
        val headers = parseHeaders(target.headers)
        if (target.type == ForwardType.FEISHU || payload.isNotEmpty()) {
            if (headers.none { it.first.equals("Content-Type", ignoreCase = true) }) {
                headers += "Content-Type" to "application/json; charset=utf-8"
            }
        }
        request(target.url.trim(), target.method.uppercase(), headers, payload)
    }

    private fun request(url: String, method: String, headers: List<Pair<String, String>>, body: String) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = method == "GET"
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            if (method != "GET" && method != "HEAD" && body.isNotEmpty()) {
                connection.doOutput = true
                connection.outputStream.use { stream: OutputStream -> stream.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            connection.inputStream?.close()
        } finally {
            connection.disconnect()
        }
    }

    private fun defaultJson(rule: AlarmRule, subject: SmsSubject): String = JSONObject().apply {
        put("event", "sms_matched")
        put("rule", rule.name)
        put("ruleId", rule.id)
        put("sender", subject.sender)
        put("localNumber", subject.localNumber)
        put("text", subject.body)
        put("time", now())
    }.toString()

    private fun feishuPayload(target: ForwardTarget, rule: AlarmRule, subject: SmsSubject): String {
        val text = if (target.body.isNotBlank()) render(target.body, rule, subject) else buildString {
            append("【").append(rule.name).append("】短信命中")
            if (rule.description.isNotBlank()) append("\n").append(rule.description)
            append("\n号码：").append(subject.sender.ifBlank { "未知" })
            append("\n内容：").append(subject.body)
            append("\n时间：").append(now())
        }
        return JSONObject().apply {
            if (target.secret.isNotBlank()) {
                val timestamp = (System.currentTimeMillis() / 1000).toString()
                put("timestamp", timestamp)
                put("sign", feishuSign(timestamp, target.secret))
            }
            put("msg_type", "text")
            put("content", JSONObject().put("text", text))
        }.toString()
    }

    /** Feishu bot signing: HMAC-SHA256 over "timestamp\nsecret", base64 encoded. */
    internal fun feishuSign(timestamp: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("$timestamp\n$secret".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(ByteArray(0)), Base64.NO_WRAP)
    }

    /** Replaces {text} {sender} {local} {rule} {time}; unknown braces stay untouched. */
    internal fun render(template: String, rule: AlarmRule, subject: SmsSubject): String =
        template
            .replace("{text}", subject.body)
            .replace("{sender}", subject.sender)
            .replace("{local}", subject.localNumber)
            .replace("{rule}", rule.name)
            .replace("{time}", now())

    private fun parseHeaders(raw: String): MutableList<Pair<String, String>> = raw.lineSequence()
        .map(String::trim).filter(String::isNotEmpty)
        .mapNotNull { line ->
            val index = line.indexOf(':')
            if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
        }
        .toMutableList()

    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
}
