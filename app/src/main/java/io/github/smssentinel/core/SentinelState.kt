package io.github.smssentinel.core

/** Global monitoring switch plus every configured rule. */
data class SentinelState(
    /** Monitoring runs whenever the app is installed; kept for migrations only. */
    val enabled: Boolean = true,
    val rules: List<AlarmRule> = emptyList(),
    /** Shared forward library referenced by [RuleAction.forwardIds]. */
    val forwards: List<ForwardTarget> = emptyList(),
) {
    fun activeRules(): List<AlarmRule> = rules.filter { it.enabled }

    fun forward(id: String): ForwardTarget? = forwards.firstOrNull { it.id == id }

    fun forwardsFor(action: RuleAction): List<ForwardTarget> =
        action.forwardIds.mapNotNull { forward(it) }

    /** Null when the configuration can be used for real monitoring. */
    fun validationError(): String? {
        if (rules.isEmpty()) return "请先创建一条规则"
        if (!rules.any { it.enabled }) return "请至少启用一条规则"
        return null
    }
}
