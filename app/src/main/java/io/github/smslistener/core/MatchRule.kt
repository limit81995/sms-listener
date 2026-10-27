package io.github.smslistener.core

import java.util.UUID

/** How one condition compares the text it is bound to. */
enum class MatchMode(val label: String) {
    EQUALS("全等"),
    CONTAINS("包含"),
    REGEX("正则"),
}

/** Which part of an SMS a condition reads. */
enum class MatchField(val label: String, val hint: String) {
    LOCAL_NUMBER("本机手机号", "接收这条短信的 SIM 号码"),
    SENDER_NUMBER("对方手机号", "发送这条短信的号码"),
    BODY("正文内容", "短信正文"),
}

/** Everything a condition can be evaluated against. */
data class SmsSubject(
    val body: String = "",
    val sender: String = "",
    val localNumber: String = "",
)

data class MatchCondition(val mode: MatchMode, val value: String) {
    fun validationError(): String? = when {
        value.isBlank() -> "条件内容不能为空"
        value.length > 500 -> "条件内容最多 500 个字符"
        mode == MatchMode.REGEX && runCatching { Regex(value) }.isFailure ->
            "正则格式有误：" + (runCatching { Regex(value) }.exceptionOrNull()?.message?.take(50) ?: "无法解析")
        else -> null
    }

    /**
     * [phone] normalizes both sides to digits so "+86 138…" equals "138…";
     * regex always sees the raw text so patterns stay predictable.
     */
    fun matches(text: String, phone: Boolean = false): Boolean = when (mode) {
        MatchMode.EQUALS -> if (phone) {
            RuleMatcher.normalizeNumber(text) == RuleMatcher.normalizeNumber(value)
        } else text.trim().equals(value.trim(), ignoreCase = true)
        MatchMode.CONTAINS -> if (phone) {
            val wanted = RuleMatcher.normalizeNumber(value)
            wanted.isNotEmpty() && RuleMatcher.normalizeNumber(text).contains(wanted)
        } else text.contains(value, ignoreCase = true)
        MatchMode.REGEX -> runCatching {
            Regex(value, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).containsMatchIn(text)
        }.getOrDefault(false)
    }

    fun summary(field: MatchField): String = "${field.label} ${mode.label} ${value.take(24)}"
}

/**
 * One source-specific condition group. SMS fields combine with AND;
 * groups are ordered alternatives (OR), and only the first matching group fires.
 */
data class MatchRule(
    val id: String = UUID.randomUUID().toString(),
    val localNumber: MatchCondition? = null,
    val senderNumber: MatchCondition? = null,
    val body: MatchCondition? = null,
    val source: TriggerSource = TriggerSource.SMS,
    /** Used only by screen conditions; SMS always dispatches immediately. */
    val delayMs: Long = 0,
) {
    val isEmpty: Boolean get() = source == TriggerSource.SMS &&
        localNumber == null && senderNumber == null && body == null

    fun conditions(): List<Pair<MatchField, MatchCondition>> = listOfNotNull(
        localNumber?.let { MatchField.LOCAL_NUMBER to it },
        senderNumber?.let { MatchField.SENDER_NUMBER to it },
        body?.let { MatchField.BODY to it },
    )

    fun validationError(): String? {
        when (source) {
            TriggerSource.HTTP -> return "HTTP(S) 触发尚未开放"
            TriggerSource.LOCK, TriggerSource.UNLOCK -> {
                return if (delayMs in 0..600_000) null else "触发延迟需在 0～600000 毫秒之间"
            }
            TriggerSource.SMS -> Unit
        }
        conditions().forEach { (_, condition) ->
            condition.validationError()?.let { return it }
        }
        return null
    }

    fun matches(subject: SmsSubject): Boolean {
        if (source != TriggerSource.SMS) return false
        val results = conditions().map { (field, condition) ->
            when (field) {
                MatchField.LOCAL_NUMBER -> condition.matches(subject.localNumber, phone = true)
                MatchField.SENDER_NUMBER -> condition.matches(subject.sender, phone = true)
                MatchField.BODY -> condition.matches(subject.body)
            }
        }
        return results.all { it }
    }

    fun summary(): String = when (source) {
        TriggerSource.SMS -> conditions().joinToString(" + ") { (field, condition) -> condition.summary(field) }
            .ifEmpty { "不限制（命中所有短信）" }
        TriggerSource.LOCK, TriggerSource.UNLOCK -> source.label +
            if (delayMs > 0) " · 延迟 ${delayMs}ms" else " · 立即触发"
        TriggerSource.HTTP -> "HTTP(S) 触发尚未开放"
    }
}

data class ForwardTarget(
    val id: String = UUID.randomUUID().toString(),
    /** Shown in the library and in the picker; defaults to the host when left blank. */
    val name: String = "",
    val type: ForwardType,
    val url: String = "",
    val method: String = "POST",
    val headers: String = "",
    val body: String = "",
    val secret: String = "",
) {
    val displayName: String get() = name.ifBlank { type.label + " · " + url.trim().substringAfter("://").take(24) }
    fun validationError(): String? = when {
        !url.trim().startsWith("http://") && !url.trim().startsWith("https://") ->
            "${type.label}地址需以 http:// 或 https:// 开头"
        url.length > 2048 -> "${type.label}地址过长"
        type == ForwardType.HTTP && method.uppercase() !in METHODS -> "${type.label}请求方法不受支持"
        headers.length > 8192 -> "${type.label}请求头过长"
        body.length > 32768 -> "${type.label}正文过长"
        else -> null
    }

    fun summary(): String = type.label + " · " + url.trim().substringAfter("://").take(40)

    fun sanitized(): ForwardTarget = copy(url = url.trim(), headers = headers.trim(), secret = secret.trim(), name = name.trim())

    companion object {
        private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
    }
}

enum class ForwardType(val label: String) {
    HTTP("HTTP(S)"),
    FEISHU("飞书机器人"),
}
