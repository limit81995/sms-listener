package io.github.smssentinel.action

import android.content.Context
import io.github.smssentinel.alarm.AlarmController
import io.github.smssentinel.alarm.AlarmSpec
import io.github.smssentinel.core.ActionType
import io.github.smssentinel.core.AlarmRule
import io.github.smssentinel.core.SmsSubject
import io.github.smssentinel.data.AppStore
import java.util.concurrent.Executors

/**
 * Executes every action of every matched rule. Local actions are merged into one
 * alarm run; forward targets are queued on a single background thread so they can
 * never block the SMS broadcast.
 */
object ActionExecutor {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SmsSentinel-Actions").apply { isDaemon = true }
    }

    fun dispatch(context: Context, rules: List<AlarmRule>, subject: SmsSubject) {
        val store = AppStore(context)
        val spec = mergeLocal(rules)
        val reason = when {
            rules.size == 1 -> "规则「${rules.first().name}」命中"
            else -> "${rules.size} 条规则命中"
        }
        if (spec != null) AlarmController.start(context, spec, reason, fromSms = true)

        val state = store.state()
        val targets = rules.flatMap { rule ->
            rule.actions.filter { it.type == ActionType.FORWARD }
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
    fun dispatchTrigger(context: Context, rule: AlarmRule) {
        val store = AppStore(context)
        val state = store.state()
        val spec = mergeLocal(listOf(rule))
        if (spec != null) AlarmController.start(context, spec, "规则「${rule.name}」触发")
        val targets = rule.actions.filter { it.type == ActionType.FORWARD }
            .flatMap { action -> action.forwardIds.mapNotNull { id -> state.forward(id)?.let { rule to it } } }
        if (targets.isEmpty()) return
        val subject = SmsSubject()
        executor.execute {
            targets.forEach { (r, target) ->
                try {
                    NetSender.send(target, r, subject)
                    store.log("已转发：${target.type.label} / ${r.name}")
                } catch (e: Exception) {
                    store.log("转发失败：${target.type.label} / ${r.name}", e)
                }
            }
        }
    }

    /** Feeds the local alarm tests from the tools screen. */
    fun runLocal(context: Context, spec: AlarmSpec, reason: String): Boolean =
        AlarmController.start(context, spec, reason)

    /**
     * Real phones can only run one alarm at a time. Any "manual stop" (0) wins;
     * otherwise the longest configured duration keeps the alarm audible.
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
            rule.actions.filter { it.enabled }.forEach { action ->
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
