package io.github.smslistener.alarm

import io.github.smslistener.core.ActionOperation
import io.github.smslistener.core.ActionType
import io.github.smslistener.core.StopScope

/** One rule action accepted at an explicit monotonic millisecond timestamp. */
data class AlarmCommand(
    val sourceRuleId: String,
    val slotId: String,
    val type: ActionType,
    val operation: ActionOperation = ActionOperation.START,
    val stopScope: StopScope = StopScope.THIS_RULE,
    val targetRuleId: String = "",
    val delayMs: Long = 0,
    val durationSeconds: Int = 0,
    val repeatCount: Int = 0,
    val soundUri: String = "",
    val reason: String = "",
    val fromSms: Boolean = false,
    /** Transport metadata; interpreted by the service, not by the session reducer. */
    val cancellationEpoch: Long? = null,
)

/** Immutable desired output; the caller drives hardware only after processing the entire batch. */
data class AlarmOutput(
    val key: String,
    val sourceRuleId: String,
    val type: ActionType,
    val soundUri: String,
    val repeatCount: Int,
    val startedAt: Long,
    val slotId: String = "",
    val reason: String = "",
    val fromSms: Boolean = false,
)

/**
 * Android-free desired-state reducer. All supplied times use the same monotonic millisecond clock.
 * A backward timestamp is clamped to the latest observed time. Deadlines are based on scheduled
 * start, not wake-up time: advancing across an entire run never resurrects an expired output.
 *
 * Each live/pending START owns one (rule, slot, channel) contribution. Repeated STARTs leave it
 * unchanged. STOPs are one-shot events, cancelling matching active AND pending contributions but
 * not later submissions. At equal due times, a later-ordered START survives an earlier STOP; a
 * START scheduled strictly after the STOP is cancelled even when it was submitted later.
 * Delayed STOPs are deduplicated by their own (rule, slot, channel), retaining
 * the earliest event. No stale timer queue accumulates when starts are cancelled or retriggered.
 * [onStopApplied] runs synchronously once when a STOP executes (after local removal), including
 * stops with no local matches. A callback failure never discards unrelated contributions.
 */
class AlarmSessionEngine(private val onStopApplied: (AlarmCommand) -> Unit = {}) {
    private data class Slot(val rule: String, val slot: String, val type: ActionType) {
        // Length-prefixed components also distinguish rule/slot IDs containing ':' or '|'.
        val outputKey: String get() = rule.length.toString() + ":" + rule + slot.length + ":" + slot + ":" + type.name
    }
    private data class Contribution(
        val command: AlarmCommand,
        val order: Long,
        val startsAt: Long,
        var endsAt: Long?,
        var active: Boolean = false,
    )
    private data class PendingStop(val command: AlarmCommand, val dueAt: Long, val order: Long)
    private enum class Kind { START, STOP, EXPIRE }
    private data class Event(val key: Slot, val dueAt: Long, val order: Long, val kind: Kind)

    private val contributions = linkedMapOf<Slot, Contribution>()
    private val stops = linkedMapOf<Slot, PendingStop>()
    private val soundPeriods = mutableMapOf<String, Long>()
    private var clock = 0L
    private var sequence = 0L

    @Synchronized fun submit(commands: List<AlarmCommand>, now: Long) {
        // Reject malformed local batches before performing any of their commands.
        commands.filter { it.type.isLocal }.forEach {
            require(it.delayMs >= 0 && (it.operation == ActionOperation.STOP ||
                (it.durationSeconds >= 0 && it.repeatCount >= 0))) { "Negative alarm timing" }
        }
        advance(now)
        commands.forEach { command ->
            if (!command.type.isLocal) return@forEach
            val key = Slot(command.sourceRuleId, command.slotId, command.type)
            when (command.operation) {
                ActionOperation.START -> if (key !in contributions) {
                    val startsAt = addTime(clock, command.delayMs)
                    contributions[key] = Contribution(command, sequence++, startsAt, deadline(command, startsAt))
                }
                ActionOperation.STOP -> {
                    val dueAt = addTime(clock, command.delayMs)
                    val previous = stops[key]
                    if (previous == null || dueAt < previous.dueAt) {
                        stops[key] = PendingStop(command, dueAt, sequence++)
                    }
                }
            }
            // Immediate commands observe list order without publishing intermediate hardware state.
            drain()
        }
    }

    @Synchronized fun advance(now: Long) {
        require(now >= 0) { "Negative alarm clock" }
        clock = maxOf(clock, now)
        drain()
    }

    @Synchronized fun activeOutputs(): List<AlarmOutput> = contributions.entries.mapNotNull { (key, item) ->
        if (!item.active) null else AlarmOutput(key.outputKey, item.command.sourceRuleId, item.command.type,
            item.command.soundUri, item.command.repeatCount, item.startsAt, item.command.slotId,
            item.command.reason, item.command.fromSms)
    }

    @Synchronized fun nextWakeAt(): Long? = nextEvent()?.dueAt

    @Synchronized fun hasWork(): Boolean = contributions.isNotEmpty() || stops.isNotEmpty()

    @Synchronized fun clear() {
        contributions.clear()
        stops.clear()
        soundPeriods.clear()
        clock = 0L
        sequence = 0L
    }

    /** A failed hardware contribution is removed without clearing another rule/channel or STOP. */
    @Synchronized fun removeOutput(key: String) {
        contributions.keys.removeAll { it.outputKey == key }
    }

    /** A known audio period can shorten, but never extend, an already accepted contribution. */
    @Synchronized fun setSoundPeriod(soundUri: String, periodMs: Long, now: Long) {
        advance(now)
        if (periodMs <= 0) return
        soundPeriods[soundUri] = periodMs
        contributions.values.forEach { item ->
            if (item.command.type == ActionType.RING && item.command.soundUri == soundUri && item.command.repeatCount > 0) {
                val repeatedUntil = addTime(item.startsAt, multiplyTime(periodMs, item.command.repeatCount.toLong()))
                item.endsAt = earlier(item.endsAt, repeatedUntil)
            }
        }
        drain()
    }

    private fun drain() {
        while (true) {
            val event = nextEvent() ?: return
            if (event.dueAt > clock) return
            when (event.kind) {
                Kind.START -> contributions[event.key]?.active = true
                Kind.EXPIRE -> contributions.remove(event.key)
                Kind.STOP -> stops.remove(event.key)?.let { pending ->
                    contributions.entries.removeAll { (_, item) ->
                        val startsLaterInSameTick = !item.active && item.startsAt == event.dueAt && item.order > event.order
                        matches(pending.command, item.command) && !startsLaterInSameTick
                    }
                    runCatching { onStopApplied(pending.command) }
                }
            }
        }
    }

    private fun nextEvent(): Event? {
        var next: Event? = null
        fun consider(event: Event) {
            val current = next
            if (current == null || event.dueAt < current.dueAt ||
                (event.dueAt == current.dueAt && event.order < current.order)) next = event
        }
        contributions.forEach { (key, item) ->
            if (!item.active) consider(Event(key, item.startsAt, item.order, Kind.START))
            else item.endsAt?.let { consider(Event(key, it, item.order, Kind.EXPIRE)) }
        }
        stops.forEach { (key, stop) -> consider(Event(key, stop.dueAt, stop.order, Kind.STOP)) }
        return next
    }

    private fun matches(stop: AlarmCommand, start: AlarmCommand): Boolean = stop.type == start.type && when (stop.stopScope) {
        StopScope.THIS_RULE -> start.sourceRuleId == stop.sourceRuleId
        StopScope.SPECIFIC_RULE -> stop.targetRuleId.isNotBlank() && start.sourceRuleId == stop.targetRuleId
        StopScope.ALL_RULES -> true
    }

    private fun deadline(command: AlarmCommand, startsAt: Long): Long? {
        val duration = command.durationSeconds.takeIf { it > 0 }?.let { addTime(startsAt, it.toLong() * 1000L) }
        val period = when (command.type) {
            ActionType.VIBRATE -> 1200L
            ActionType.FLASH -> 1000L // AlarmFlash: 700ms on + 300ms off.
            ActionType.RING -> soundPeriods[command.soundUri]
            ActionType.FORWARD -> null
        }
        val repetitions = if (command.repeatCount > 0 && period != null)
            addTime(startsAt, multiplyTime(period, command.repeatCount.toLong())) else null
        return earlier(duration, repetitions)
    }

    private fun earlier(left: Long?, right: Long?): Long? = when {
        left == null -> right
        right == null -> left
        else -> minOf(left, right)
    }

    private fun addTime(start: Long, duration: Long): Long =
        if (duration > Long.MAX_VALUE - start) Long.MAX_VALUE else start + duration

    private fun multiplyTime(period: Long, count: Long): Long =
        if (count > 0 && period > Long.MAX_VALUE / count) Long.MAX_VALUE else period * count
}
