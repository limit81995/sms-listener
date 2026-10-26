package io.github.smslistener.monitor

import android.app.Application
import android.app.Service
import android.content.Context
import android.os.Looper
import io.github.smslistener.action.ActionExecutor
import io.github.smslistener.alarm.*
import io.github.smslistener.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.time.Duration

/** Crosses the condition-delay boundary with the production cancellation snapshot and service STOP. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class ScreenCancellationTest {
    private lateinit var app: Application
    private var service: ServiceController<AlarmService>? = null
    private var state = SentinelState()
    private val clock = Clock()
    private val errors = mutableListOf<Throwable>()
    private val dispatchers = mutableListOf<ScreenTriggerDispatcher>()

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

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("smslistener", Context.MODE_PRIVATE).edit().clear().commit()
        AlarmController.stop(app)
        shadowOf(AlarmVibration.vibrator(app)).setHasVibrator(true)
    }

    @After fun tearDown() {
        dispatchers.forEach { it.close() }
        service?.destroy()
        AlarmController.stop(app)
        AlarmVibration.stop(app)
    }

    private fun rule(id: String, actions: List<RuleAction>) = AlarmRule(id = id, name = id,
        matchRules = listOf(MatchRule(id = "$id-lock", source = TriggerSource.LOCK, delayMs = 5000)),
        actions = actions)

    private fun dispatcher(callback: (AlarmRule, ActionCancellationSnapshot) -> Unit) = ScreenTriggerDispatcher(
        readState = { state }, schedule = clock::schedule,
        dispatch = { error("Expected the snapshot-aware dispatch path") }, onError = { errors += it },
        capture = { ActionCancellationTracker.capture(it, AlarmController.currentGeneration()) },
        dispatchPending = callback,
    ).also { dispatchers += it }

    private fun applyStop(command: AlarmCommand) {
        assertEquals(ActionOperation.STOP, command.operation)
        assertTrue(AlarmController.execute(app, listOf(command)))
        val intent = requireNotNull(shadowOf(app).nextStartedService)
        val controller = service ?: Robolectric.buildService(AlarmService::class.java).create().also { service = it }
        assertEquals(Service.START_NOT_STICKY, controller.get().onStartCommand(intent, 0, 1))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun pendingLockConditionLosesOnlyTheSpecificallyStoppedChannelNotOtherOwnerOrForward() {
        val a = rule("A", listOf(RuleAction(ActionType.VIBRATE), RuleAction(ActionType.RING),
            RuleAction(ActionType.FORWARD, forwardIds = listOf("forward-fixture"))))
        val b = rule("B", listOf(RuleAction(ActionType.VIBRATE)))
        state = SentinelState(rules = listOf(a, b))
        val accepted = mutableListOf<AlarmCommand>()
        val forwarded = mutableListOf<String>()
        val snapshots = mutableListOf<ActionCancellationSnapshot>()
        val dispatcher = dispatcher { current, snapshot ->
            snapshots += snapshot
            accepted += ActionExecutor.localCommands(listOf(current), false, snapshot)
                .filter(ActionCancellationTracker::accepts)
            // Test forwarding eligibility, not a real network request.
            forwarded += current.actions.filter { it.enabled && it.type == ActionType.FORWARD }
                .flatMap { it.forwardIds }
        }
        dispatcher.onEvent(TriggerSource.LOCK)
        assertEquals(2, clock.tasks.size)
        applyStop(AlarmCommand("stop-rule", "stop", ActionType.VIBRATE, operation = ActionOperation.STOP,
            stopScope = StopScope.SPECIFIC_RULE, targetRuleId = "A"))
        clock.advance(4999)
        assertTrue(accepted.isEmpty())
        clock.advance(1)
        assertEquals(listOf("A" to ActionType.RING, "B" to ActionType.VIBRATE),
            accepted.map { it.sourceRuleId to it.type })
        assertEquals(listOf("forward-fixture"), forwarded)
        assertEquals(listOf("A", "B"), snapshots.map { it.ruleId })
        assertTrue(errors.isEmpty())
        // A later event captures the new epoch and can start A's vibration again.
        accepted.clear()
        dispatcher.onEvent(TriggerSource.LOCK)
        clock.advance(5000)
        assertTrue(accepted.any { it.sourceRuleId == "A" && it.type == ActionType.VIBRATE })
    }

    @Test fun globalStopInvalidatesOldConditionSnapshotGenerationButNotAFreshEvent() {
        val a = rule("A", listOf(RuleAction(ActionType.VIBRATE)))
        state = SentinelState(rules = listOf(a))
        val generations = mutableListOf<Long>()
        val submitted = mutableListOf<Boolean>()
        val dispatcher = dispatcher { current, snapshot ->
            generations += snapshot.generation
            submitted += AlarmController.execute(app,
                ActionExecutor.localCommands(listOf(current), false, snapshot), snapshot.generation)
        }
        val oldGeneration = AlarmController.currentGeneration()
        dispatcher.onEvent(TriggerSource.LOCK)
        AlarmController.stop(app)
        clock.advance(5000)
        assertEquals(listOf(oldGeneration), generations)
        assertEquals(listOf(false), submitted)
        assertNull(shadowOf(app).nextStartedService)
        dispatcher.onEvent(TriggerSource.LOCK)
        clock.advance(5000)
        assertEquals(listOf(false, true), submitted)
        assertEquals(AlarmController.currentGeneration(), generations.last())
        val fresh = requireNotNull(shadowOf(app).nextStartedService)
        assertTrue(AlarmCommandTransport.read(fresh).all(ActionCancellationTracker::accepts))
        assertTrue(errors.isEmpty())
    }

    @Test fun delayedStopAdvancesCancellationEpochOnlyWhenTheServiceAppliesIt() {
        val a = rule("A", listOf(RuleAction(ActionType.VIBRATE), RuleAction(ActionType.RING)))
        val snapshot = ActionCancellationTracker.capture(a.id, AlarmController.currentGeneration())
        val old = ActionExecutor.localCommands(listOf(a), false, snapshot)
        applyStop(AlarmCommand("A", "stop", ActionType.VIBRATE, operation = ActionOperation.STOP, delayMs = 5000))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4999))
        assertTrue(old.all(ActionCancellationTracker::accepts))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertFalse(ActionCancellationTracker.accepts(old.single { it.type == ActionType.VIBRATE }))
        assertTrue(ActionCancellationTracker.accepts(old.single { it.type == ActionType.RING }))
        assertTrue(ActionExecutor.localCommands(listOf(a), false).all(ActionCancellationTracker::accepts))
    }

    @Test fun stopAllInvalidatesOnlyThatChannelAcrossOwnersAndEpochSurvivesTransport() {
        val a = rule("A", listOf(RuleAction(ActionType.RING), RuleAction(ActionType.VIBRATE)))
        val b = rule("B", a.actions)
        val old = listOf(a, b).flatMap { current ->
            ActionExecutor.localCommands(listOf(current), false,
                ActionCancellationTracker.capture(current.id, AlarmController.currentGeneration()))
        }
        assertTrue(AlarmController.execute(app, old))
        val queued = requireNotNull(shadowOf(app).nextStartedService)
        assertEquals(old, AlarmCommandTransport.read(queued))
        applyStop(AlarmCommand("operator", "stop", ActionType.RING, operation = ActionOperation.STOP,
            stopScope = StopScope.ALL_RULES))
        val accepted = AlarmCommandTransport.read(queued).filter(ActionCancellationTracker::accepts)
        assertEquals(listOf("A" to ActionType.VIBRATE, "B" to ActionType.VIBRATE),
            accepted.map { it.sourceRuleId to it.type })
        assertTrue(ActionExecutor.localCommands(listOf(a, b), false).all(ActionCancellationTracker::accepts))
    }
}
