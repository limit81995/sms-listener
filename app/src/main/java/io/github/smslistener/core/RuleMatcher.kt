package io.github.smslistener.core

enum class RuleMatch(val description: String) {
    MATCHED("命中"),
    DISABLED("规则已停用"),
    CONDITION_MISSED("条件不满足"),
    INVALID("规则配置有误"),
}

data class RuleVerdict(val rule: AlarmRule, val match: RuleMatch)

/** Pure matching logic: no Android dependency, so it runs in plain JVM tests. */
object RuleMatcher {
    fun evaluate(rule: AlarmRule, subject: SmsSubject): RuleMatch =
        evaluate(rule, TriggerSource.SMS, subject)

    fun evaluate(rule: AlarmRule, source: TriggerSource, subject: SmsSubject = SmsSubject()): RuleMatch = when {
        !rule.enabled -> RuleMatch.DISABLED
        rule.validationError() != null -> RuleMatch.INVALID
        firstMatch(rule, source, subject) == null -> RuleMatch.CONDITION_MISSED
        else -> RuleMatch.MATCHED
    }

    /** One event picks at most one group per rule, preserving the configured OR order. */
    fun firstMatch(rule: AlarmRule, source: TriggerSource, subject: SmsSubject = SmsSubject()): MatchRule? {
        if (!rule.enabled || rule.validationError() != null) return null
        return rule.matchRules.firstOrNull { group ->
            group.source == source && when (source) {
                TriggerSource.SMS -> group.matches(subject)
                TriggerSource.LOCK, TriggerSource.UNLOCK -> true
                TriggerSource.HTTP -> false
            }
        }
    }

    fun matched(rules: List<AlarmRule>, subject: SmsSubject): List<AlarmRule> =
        rules.filter { evaluate(it, subject) == RuleMatch.MATCHED }

    fun diagnose(rules: List<AlarmRule>, subject: SmsSubject): List<RuleVerdict> =
        rules.map { RuleVerdict(it, evaluate(it, subject)) }

    /** Formats a phone number for comparison: digits only, leading 86 dropped. */
    fun normalizeNumber(value: String): String {
        val digits = value.filter(Char::isDigit)
        return if (digits.length > 11 && digits.startsWith("86")) digits.substring(2) else digits
    }
}
