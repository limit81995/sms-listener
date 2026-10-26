package io.github.smslistener.monitor

import io.github.smslistener.core.*
import org.junit.Assert.*
import org.junit.Test

class ScreenTriggerDispatcherTest {
    private class Clock {
        class Task(val at: Long, val run: () -> Unit) { var cancelled = false }
        var now = 0L
        val tasks = mutableListOf<Task>()
        fun schedule(delay: Long, run: () -> Unit): () -> Unit {
            val task = Task(now + delay, run)
            tasks += task
            return { task.cancelled = true }
        }
        fun advance(ms: Long) {
            now += ms
            while (true) {
                val task = tasks.filter { it.at <= now }.minByOrNull { it.at } ?: break
                tasks.remove(task)
                if (!task.cancelled) task.run()
            }
        }
    }
    private val sms = MatchRule(id = "sms", body = MatchCondition(MatchMode.CONTAINS, "alert"))
    private val unlock = MatchRule(id = "unlock", source = TriggerSource.UNLOCK, delayMs = 600_000)
    private val lock = MatchRule(id = "lock", source = TriggerSource.LOCK, delayMs = 10)
    private fun rule(vararg groups: MatchRule, id: String = "r") = AlarmRule(
        id = id, name = id, matchRules = groups.toList(), actions = listOf(RuleAction(ActionType.VIBRATE)),
    )
    private val clock = Clock()
    private var state = SentinelState()
    private val fired = mutableListOf<AlarmRule>()
    private val errors = mutableListOf<Throwable>()
    private fun dispatcher(dispatch: (AlarmRule) -> Unit = { fired += it }) = ScreenTriggerDispatcher(
        readState = { state }, schedule = clock::schedule, dispatch = dispatch, onError = { errors += it },
    )

    @Test fun typedEventsUseOnlyTheFirstSameSourceDelay() {
        state = SentinelState(rules = listOf(rule(sms, unlock, unlock.copy(id = "u2", delayMs = 0), lock)))
        val dispatcher = dispatcher()
        dispatcher.onEvent(TriggerSource.UNLOCK)
        assertEquals(listOf(600_000L), clock.tasks.map { it.at })
        dispatcher.onEvent(TriggerSource.LOCK)
        assertEquals(listOf(600_000L, 10L), clock.tasks.map { it.at })
        dispatcher.onEvent(TriggerSource.SMS)
        dispatcher.onEvent(TriggerSource.HTTP)
        assertEquals(2, clock.tasks.size)
        clock.advance(9)
        assertTrue(fired.isEmpty())
        clock.advance(1)
        assertEquals(1, fired.size)
        clock.advance(599_990)
        assertEquals(2, fired.size)
        assertTrue(clock.tasks.isEmpty())
        clock.advance(600_000)
        assertEquals(2, fired.size) // A timer never reschedules itself.
    }

    @Test fun oneEventDispatchesEachRuleOnceAndZeroDelayIsAllowed() {
        val immediate = lock.copy(delayMs = 0)
        state = SentinelState(rules = listOf(
            rule(immediate, immediate.copy(id = "second"), id = "a"),
            rule(sms, immediate, id = "b"),
        ))
        dispatcher().onEvent(TriggerSource.LOCK)
        clock.advance(0)
        assertEquals(listOf("a", "b"), fired.map { it.id })
    }

    @Test fun separateEventsRemainSeparateOneShotTasks() {
        state = SentinelState(rules = listOf(rule(lock)))
        val dispatcher = dispatcher()
        repeat(2) { dispatcher.onEvent(TriggerSource.LOCK) }
        clock.advance(10)
        assertEquals(2, fired.size)
    }

    @Test fun deletionIsRecheckedAtExecutionEvenWithoutAChangeNotification() {
        state = SentinelState(rules = listOf(rule(lock)))
        dispatcher().onEvent(TriggerSource.LOCK)
        state = SentinelState()
        clock.advance(10)
        assertTrue(fired.isEmpty())
    }

    @Test fun disabledRuleAndGlobalDisableAreRecheckedAtExecution() {
        val original = rule(lock)
        for (next in listOf(
            SentinelState(enabled = false, rules = listOf(original)),
            SentinelState(rules = listOf(original.copy(enabled = false))),
        )) {
            state = SentinelState(rules = listOf(original))
            dispatcher().onEvent(TriggerSource.LOCK)
            state = next
            clock.advance(10)
            assertTrue(fired.isEmpty())
        }
    }

    @Test fun conditionEditsAndReorderingInvalidatePendingWork() {
        val original = rule(lock, lock.copy(id = "second", delayMs = 20))
        val edits = listOf(
            original.copy(matchRules = original.matchRules.reversed()),
            original.copy(matchRules = listOf(lock.copy(delayMs = 11))),
            original.copy(matchRules = listOf(lock.copy(source = TriggerSource.UNLOCK))),
            original.copy(matchRules = listOf(lock.copy(id = "replacement"))),
            original.copy(matchRules = emptyList()),
        )
        for (next in edits) {
            state = SentinelState(rules = listOf(original))
            dispatcher().onEvent(TriggerSource.LOCK)
            state = SentinelState(rules = listOf(next))
            clock.advance(10)
            assertTrue(fired.isEmpty())
        }
    }

    @Test fun configurationNotificationCancelsOnlyInvalidTasks() {
        val a = rule(unlock, id = "a")
        val b = rule(unlock, id = "b")
        state = SentinelState(rules = listOf(a, b))
        val dispatcher = dispatcher()
        dispatcher.onEvent(TriggerSource.UNLOCK)
        val staleCallback = clock.tasks.first().run
        state = SentinelState(rules = listOf(b))
        dispatcher.cancelInvalid()
        assertTrue(clock.tasks.first().cancelled)
        assertFalse(clock.tasks.last().cancelled)
        staleCallback() // Cancellation also rejects already-queued callbacks.
        clock.advance(600_000)
        assertEquals(listOf("b"), fired.map { it.id })
    }

    @Test fun lifecycleCloseCancelsAllDelaysAndRejectsCallbacksAndNewEvents() {
        state = SentinelState(rules = listOf(rule(unlock, lock)))
        val dispatcher = dispatcher()
        dispatcher.onEvent(TriggerSource.UNLOCK)
        dispatcher.onEvent(TriggerSource.LOCK)
        val callbacks = clock.tasks.map { it.run }
        dispatcher.close()
        dispatcher.close()
        assertTrue(clock.tasks.all { it.cancelled })
        callbacks.forEach { it() }
        dispatcher.onEvent(TriggerSource.UNLOCK)
        assertEquals(2, clock.tasks.size)
        clock.advance(600_000)
        assertTrue(fired.isEmpty())
    }

    @Test fun renamedRulesUseCurrentActionsWithoutReplayingOtherConditions() {
        val original = rule(lock)
        state = SentinelState(rules = listOf(original))
        val dispatcher = dispatcher()
        dispatcher.onEvent(TriggerSource.LOCK)
        val changed = original.copy(name = "renamed", actions = listOf(RuleAction(ActionType.RING)))
        state = SentinelState(rules = listOf(changed))
        dispatcher.cancelInvalid()
        assertFalse(clock.tasks.single().cancelled)
        clock.advance(10)
        assertEquals(listOf(changed), fired)
    }

    @Test fun invalidAndDisabledRulesNeverSchedule() {
        state = SentinelState(rules = listOf(
            rule(lock).copy(enabled = false), rule(lock.copy(delayMs = -1), id = "invalid"),
            rule(MatchRule(source = TriggerSource.HTTP), id = "future"), rule(sms, id = "sms"),
        ))
        dispatcher().onEvent(TriggerSource.LOCK)
        assertTrue(clock.tasks.isEmpty())
    }

    @Test fun failingActionDoesNotPreventOtherRulesFromDispatching() {
        state = SentinelState(rules = listOf(rule(lock, id = "a"), rule(lock, id = "b")))
        dispatcher { if (it.id == "a") error("broken action") else fired += it }.onEvent(TriggerSource.LOCK)
        clock.advance(10)
        assertEquals(1, errors.size)
        assertEquals(listOf("b"), fired.map { it.id })
    }

    @Test fun schedulingFailureDoesNotLeaveAnExecutableOrUncancellableTask() {
        state = SentinelState(rules = listOf(rule(lock)))
        val dispatcher = ScreenTriggerDispatcher(
            readState = { state }, schedule = { _, _ -> error("executor stopped") },
            dispatch = { fired += it }, onError = { errors += it },
        )
        dispatcher.onEvent(TriggerSource.LOCK)
        dispatcher.cancelInvalid()
        dispatcher.close()
        assertEquals(1, errors.size)
        assertTrue(fired.isEmpty())
    }
}
