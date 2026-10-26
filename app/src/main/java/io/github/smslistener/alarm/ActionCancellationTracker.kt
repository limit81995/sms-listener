package io.github.smslistener.alarm

import io.github.smslistener.core.ActionOperation
import io.github.smslistener.core.ActionType
import io.github.smslistener.core.StopScope

/** Snapshot carried across condition delays and service delivery, never persisted or replayed. */
data class ActionCancellationSnapshot(val ruleId: String, val generation: Long, val channels: Map<ActionType, Long>)

/** Monotonic per-channel cancellation versions also cover tasks outside AlarmService's queue. */
internal object ActionCancellationTracker {
    private var sequence = 0L
    private val all = mutableMapOf<ActionType, Long>()
    private val rules = mutableMapOf<Pair<String, ActionType>, Long>()

    @Synchronized fun current(ruleId: String, type: ActionType): Long =
        maxOf(all[type] ?: 0L, rules[ruleId to type] ?: 0L)

    @Synchronized fun capture(ruleId: String, generation: Long): ActionCancellationSnapshot =
        ActionCancellationSnapshot(ruleId, generation, ActionType.entries.filter { it.isLocal }
            .associateWith { current(ruleId, it) })

    @Synchronized fun recordStop(command: AlarmCommand) {
        if (command.operation != ActionOperation.STOP || !command.type.isLocal) return
        val version = ++sequence
        when (command.stopScope) {
            StopScope.ALL_RULES -> all[command.type] = version
            StopScope.THIS_RULE -> rules[command.sourceRuleId to command.type] = version
            StopScope.SPECIFIC_RULE -> if (command.targetRuleId.isNotBlank()) rules[command.targetRuleId to command.type] = version
        }
    }

    fun accepts(command: AlarmCommand): Boolean = command.operation != ActionOperation.START ||
        command.cancellationEpoch == null || command.cancellationEpoch == current(command.sourceRuleId, command.type)
}
