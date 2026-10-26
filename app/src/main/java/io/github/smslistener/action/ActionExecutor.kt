package io.github.smslistener.action

import android.content.Context
import io.github.smslistener.alarm.AlarmController
import io.github.smslistener.alarm.AlarmCommand
import io.github.smslistener.alarm.ActionCancellationTracker
import io.github.smslistener.alarm.ActionCancellationSnapshot
import io.github.smslistener.core.ActionOperation
import io.github.smslistener.alarm.AlarmSpec
import io.github.smslistener.core.ActionType
import io.github.smslistener.core.AlarmRule
import io.github.smslistener.core.SmsSubject
import io.github.smslistener.data.AppStore
import java.util.concurrent.Executors

/**
 * Local actions retain their rule identity and list order in a single command batch.
 * Forward targets use a separate background queue and never block the SMS broadcast.
 */
object ActionExecutor {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SmsListener-Actions").apply { isDaemon = true }
    }

    fun dispatch(context: Context, rules: List<AlarmRule>, subject: SmsSubject) {
        val store = AppStore(context)
        val generation = AlarmController.currentGeneration()
        AlarmController.execute(context, localCommands(rules, fromSms = true), generation)

        val state = store.state()
        val targets = rules.flatMap { rule ->
            rule.actions.filter { it.enabled && it.type == ActionType.FORWARD && it.operation == ActionOperation.START }
                .flatMap { action -> action.forwardIds.mapNotNull { id -> state.forward(id)?.let { rule to it } } }
        }
        if (targets.isEmpty()) return
        executor.execute {
            targets.forEach { (rule, target) ->
                try {
                    NetSender.send(target, rule, subject)
                    store.log("已转发：${target.type.label} / ${rule.name}")
                } catch (e: Exception) {
                    store.log("转发失败：${target.type.label} / ${rule.name}", e)
                }
            }
        }
    }

    /** Runs one rule woken by a screen event; there is no SMS content to forward. */
    fun dispatchTrigger(context: Context, rule: AlarmRule, cancellation: ActionCancellationSnapshot? = null) {
        val store = AppStore(context)
        val state = store.state()
        if (!state.enabled) return
        val current = state.rules.firstOrNull { it.id == rule.id } ?: return
        if (!current.enabled || current.matchRules != rule.matchRules || current.validationError() != null) return
        val generation = cancellation?.generation ?: AlarmController.currentGeneration()
        AlarmController.execute(context, localCommands(listOf(current), fromSms = false, cancellation = cancellation), generation)
        val targets = current.actions.filter { it.enabled && it.type == ActionType.FORWARD && it.operation == ActionOperation.START }
            .flatMap { action -> action.forwardIds.mapNotNull { id -> state.forward(id)?.let { current to it } } }
        if (targets.isEmpty()) return
        val subject = SmsSubject()
        executor.execute {
            targets.forEach { (r, target) ->
                try {
                    // The network queue may outlive the screen delay; recheck before sending.
                    val latest = store.state()
                    val active = latest.rules.firstOrNull { it.id == r.id } ?: return@forEach
                    if (!latest.enabled || !active.enabled || active.matchRules != r.matchRules ||
                        active.validationError() != null || active.actions.none {
                            it.enabled && it.type == ActionType.FORWARD && it.operation == ActionOperation.START && target.id in it.forwardIds
                        }
                    ) return@forEach
                    val destination = latest.forward(target.id) ?: return@forEach
                    NetSender.send(destination, active, subject)
                    store.log("已转发：${destination.type.label} / ${active.name}")
                } catch (e: Exception) {
                    store.log("转发失败：${target.type.label} / ${r.name}", e)
                }
            }
        }
    }

    /** Preserve rule/action identity so STOP can target one channel without muting other owners. */
    internal fun localCommands(rules: List<AlarmRule>, fromSms: Boolean, cancellation: ActionCancellationSnapshot? = null): List<AlarmCommand> =
        rules.flatMap { rule ->
            rule.actions.mapIndexedNotNull { index, action ->
                if (!rule.enabled || !action.enabled || !action.type.isLocal || action.validationError() != null) null
                else AlarmCommand(sourceRuleId = rule.id, slotId = index.toString(), type = action.type,
                    operation = action.operation, stopScope = action.stopScope, targetRuleId = action.targetRuleId,
                    delayMs = action.delayMs, durationSeconds = action.durationSeconds, repeatCount = action.repeatCount,
                    soundUri = action.soundUri, reason = "规则「" + rule.name + "」触发", fromSms = fromSms,
                    cancellationEpoch = cancellation?.takeIf { it.ruleId == rule.id }?.channels?.get(action.type)
                        ?: ActionCancellationTracker.current(rule.id, action.type))
            }
        }

    /** Feeds the local alarm tests from the tools screen. */
    fun runLocal(context: Context, spec: AlarmSpec, reason: String): Boolean =
        AlarmController.start(context, spec, reason)

    /**
     * Legacy summary helper for older callers/tests; actual rule execution uses ordered commands.
     * STOP must never be misrepresented as a new local alarm.
     */
    internal fun mergeLocal(rules: List<AlarmRule>): AlarmSpec? {
        var sound = false
        var vibration = false
        var flash = false
        var delay = -1L
        var repeat = 0
        var soundUri = ""
        val durations = mutableListOf<Int>()
        rules.forEach { rule ->
            rule.actions.filter { it.enabled && it.operation == ActionOperation.START }.forEach { action ->
                when (action.type) {
                    ActionType.RING -> sound = true
                    ActionType.VIBRATE -> vibration = true
                    ActionType.FLASH -> flash = true
                    else -> return@forEach
                }
                durations += action.durationSeconds
                // The earliest configured delay wins so nothing starts later than asked.
                delay = if (delay < 0) action.delayMs else minOf(delay, action.delayMs)
                if (action.repeatCount > 0) {
                    repeat = if (repeat == 0) action.repeatCount else minOf(repeat, action.repeatCount)
                }
                if (soundUri.isEmpty() && action.soundUri.isNotBlank()) soundUri = action.soundUri
            }
        }
        if (!sound && !vibration && !flash) return null
        val duration = when {
            durations.isEmpty() -> 0
            durations.any { it == 0 } -> 0
            else -> durations.max()
        }
        return AlarmSpec(
            sound = sound,
            vibration = vibration,
            flash = flash,
            durationSeconds = duration,
            delayMs = delay.coerceAtLeast(0L),
            repeatCount = repeat,
            soundUri = soundUri,
        )
    }
}
