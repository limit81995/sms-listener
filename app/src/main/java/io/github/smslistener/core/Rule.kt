package io.github.smslistener.core

import java.util.UUID

/** Source of one condition group; a rule may mix several sources. */
enum class TriggerSource(val label: String, val hint: String) {
    SMS("短信", "短信到达并满足触发条件"),
    UNLOCK("解锁屏幕", "每次解锁都触发，可设置延迟"),
    LOCK("锁定屏幕", "每次锁屏都触发，可设置延迟"),
    HTTP("HTTP(S)", "HTTP(S) 触发尚未开放"),
    ;

    val usesConditions: Boolean get() = this == SMS
}

/** What a matched rule does. Several actions can fire together. */
enum class ActionType(val label: String, val hint: String) {
    FORWARD("转发", "把短信推送到 HTTP(S) 服务或飞书群"),
    RING("响铃", "循环响铃，直到手动停止或到达设定时长"),
    VIBRATE("振动", "原生循环振动，0.8 秒振动、0.4 秒间隔"),
    FLASH("闪光灯", "手电筒闪烁，适合静音或夜间场景"),
    ;

    val isLocal: Boolean get() = this != FORWARD
    val isRemote: Boolean get() = this == FORWARD
}

enum class ActionOperation(val label: String) { START("启动"), STOP("停止") }

enum class StopScope(val label: String) {
    THIS_RULE("本规则"), SPECIFIC_RULE("指定规则"), ALL_RULES("所有规则"),
}

/** One result action. Legacy disabled actions remain inactive, never become STOP operations. */
data class RuleAction(
    val type: ActionType,
    /** Off means the action stays in the list but never runs. */
    val enabled: Boolean = true,
    /** Milliseconds to wait before this action starts. */
    val delayMs: Long = 0,
    /** RING / VIBRATE / FLASH: 0 means keep going until the user stops it. */
    val durationSeconds: Int = 0,
    /** How many times the pattern repeats; 0 means unlimited. */
    val repeatCount: Int = 0,
    /** Custom audio for RING; blank uses the built-in tone. */
    val soundUri: String = "",
    /** References into [SentinelState.forwards]; the library owns the actual target. */
    val forwardIds: List<String> = emptyList(),
    val operation: ActionOperation = ActionOperation.START,
    val stopScope: StopScope = StopScope.THIS_RULE,
    val targetRuleId: String = "",
) {
    fun validationError(): String? = when {
        type == ActionType.FORWARD && operation != ActionOperation.START -> "转发动作仅支持启动"
        type.isLocal && delayMs !in 0..600_000 -> "${type.label}延迟需在 0～600000 毫秒之间"
        type.isLocal && operation == ActionOperation.START && durationSeconds !in 0..3600 ->
            "${type.label}时长请输入 0～3600 秒；0 表示手动停止"
        type.isLocal && operation == ActionOperation.START && repeatCount !in 0..999 ->
            "${type.label}次数需在 0～999 之间；0 表示不限"
        operation == ActionOperation.STOP && stopScope == StopScope.SPECIFIC_RULE && targetRuleId.isBlank() ->
            "请选择要停止的规则"
        type == ActionType.FORWARD && forwardIds.isEmpty() -> "请至少选择一个转发目标"
        type == ActionType.FORWARD && forwardIds.size > 20 -> "转发目标最多 20 个"
        else -> null
    }

    fun summary(available: Int = forwardIds.size, targetRuleName: String? = null): String = buildString {
        append(type.label + " · " + operation.label)
        if (!enabled) append(" · 未执行（旧禁用）")
        if (type == ActionType.FORWARD) {
            append(if (forwardIds.isEmpty()) " · 尚未选择" else " · " + forwardIds.size + " 个目标")
            if (available < forwardIds.size) append("（" + (forwardIds.size - available) + " 个已删除）")
        } else if (operation == ActionOperation.STOP) {
            append(" · " + stopScope.label)
            if (stopScope == StopScope.SPECIFIC_RULE) {
                append("：" + (targetRuleName ?: targetRuleId.ifBlank { "目标未选择" }))
            }
            if (delayMs > 0) append(" · 延迟 " + delayMs + "ms")
        } else {
            append(" · " + if (durationSeconds == 0) "手动停止" else durationSeconds.toString() + " 秒")
            append(if (repeatCount > 0) " · " + repeatCount + " 次" else " · 不限次")
            if (delayMs > 0) append(" · 延迟 " + delayMs + "ms")
            if (type == ActionType.RING && soundUri.isNotBlank()) append(" · 自定义音效")
        }
    }

    companion object {
        fun local(type: ActionType) = RuleAction(type = type)
    }
}

/**
 * One independent trigger. [matchRules] are alternatives (any one may fire);
 * the conditions inside a single [MatchRule] must all hold.
 */
data class AlarmRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val matchRules: List<MatchRule> = emptyList(),
    val actions: List<RuleAction> = emptyList(),
) {
    fun validationError(): String? {
        if (name.isBlank()) return "请填写规则名称"
        if (name.length > 60) return "规则名称最多 60 个字"
        if (description.length > 200) return "描述最多 200 个字"
        if (matchRules.isEmpty()) return "请至少添加一条触发条件"
        if (matchRules.size > 20) return "触发条件最多 20 条"
        matchRules.forEachIndexed { index, matchRule ->
            matchRule.validationError()?.let { return "触发条件 ${index + 1}：$it" }
        }
        if (actions.isEmpty()) return "请至少添加一个结果动作"
        if (actions.size > 20) return "结果动作最多 20 个"
        actions.forEach { action -> action.validationError()?.let { return it } }
        return null
    }

    fun matches(subject: SmsSubject): Boolean =
        matchRules.firstOrNull { it.matches(subject) } != null

    val hasLocalAction: Boolean get() = actions.any { it.type.isLocal && it.enabled }
    val hasForwardAction: Boolean get() = actions.any { it.type == ActionType.FORWARD && it.enabled }

    fun localActions(): List<RuleAction> = actions.filter { it.type.isLocal && it.enabled }

    fun summary(): String = buildString {
        append(matchRules.map { it.source.label }.distinct().joinToString(" / ").ifEmpty { "无条件" })
        append(" · ")
        append(if (actions.isEmpty()) "无动作" else actions.joinToString("、") {
            it.operation.label + it.type.label + if (it.enabled) "" else "（未执行/旧禁用）"
        })
    }
}
