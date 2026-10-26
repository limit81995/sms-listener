package io.github.smslistener.android

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Looper
import io.github.smslistener.action.ActionExecutor
import io.github.smslistener.alarm.*
import io.github.smslistener.core.*
import io.github.smslistener.data.AppStore
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
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class AlarmOperationsServiceTest {
    private lateinit var app: Application
    private var controller: ServiceController<AlarmService>? = null
    private val players = mutableListOf<ShadowMediaPlayer>()
    private var startId = 0
    private val vibrator get() = shadowOf(AlarmVibration.vibrator(app))
    private val service get() = (controller ?: Robolectric.buildService(AlarmService::class.java).create()
        .also { controller = it }).get()

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("smslistener", Context.MODE_PRIVATE).edit().clear().commit()
        AlarmController.stop(app)
        vibrator.setHasVibrator(true)
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(1000, 0) }
        ShadowMediaPlayer.setCreateListener { _, shadow -> players += shadow }
        shadowOf(app.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    @After fun tearDown() {
        controller?.destroy()
        controller = null
        AlarmVibration.stop(app)
        AlarmController.stop(app)
        // This shadow rejects a null provider; reset also clears the create listener and media fixtures.
        ShadowMediaPlayer.resetStaticState()
    }

    private fun start(owner: String = "A", type: ActionType = ActionType.VIBRATE, slot: String = "0") =
        AlarmCommand(owner, slot, type)
    private fun stop(owner: String = "A", type: ActionType = ActionType.VIBRATE,
        scope: StopScope = StopScope.THIS_RULE, target: String = "") =
        AlarmCommand(owner, "stop", type, operation = ActionOperation.STOP, stopScope = scope, targetRuleId = target)
    private fun queued(vararg commands: AlarmCommand): Intent {
        assertTrue(AlarmController.execute(app, commands.toList()))
        return requireNotNull(shadowOf(app).nextStartedService)
    }
    private fun deliver(intent: Intent) {
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, ++startId))
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun send(vararg commands: AlarmCommand) = deliver(queued(*commands))
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun assertIdle() {
        assertFalse(vibrator.isVibrating)
        assertFalse(AlarmService.active)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun stoppingOneVibrationOwnerPreservesTheOtherUntilTheLastOwnerStops() {
        send(start("A"), start("B"))
        assertTrue(vibrator.isVibrating)
        send(stop("A"))
        assertTrue(vibrator.isVibrating)
        assertTrue(AlarmService.active)
        assertFalse(shadowOf(service).isStoppedBySelf)
        send(stop("B"))
        assertIdle()
    }

    @Test fun stoppingAllVibrationLeavesRealMediaPlaybackRunning() {
        send(start("A", ActionType.RING), start("B"))
        val player = players.single()
        assertTrue(player.isReallyPlaying)
        assertTrue(vibrator.isVibrating)
        send(stop("operator", scope = StopScope.ALL_RULES))
        assertFalse(vibrator.isVibrating)
        assertTrue(player.isReallyPlaying)
        assertTrue(AlarmService.active)
        send(stop("operator", ActionType.RING, StopScope.ALL_RULES))
        assertEquals(ShadowMediaPlayer.State.END, player.state)
        assertIdle()
    }

    @Test fun stoppingAllRingingLeavesVibrationRunning() {
        send(start("A", ActionType.RING), start("B"))
        val player = players.single()
        send(stop("operator", ActionType.RING, StopScope.ALL_RULES))
        assertEquals(ShadowMediaPlayer.State.END, player.state)
        assertTrue(vibrator.isVibrating)
        assertTrue(AlarmService.active)
        send(stop("B"))
        assertIdle()
    }

    @Test fun stopOnlyNeverStartsVibrationOrCreatesMedia() {
        send(stop("operator", scope = StopScope.ALL_RULES))
        assertIdle()
        assertTrue(players.isEmpty())
        assertFalse(AppStore(app).logs().contains("已提交闹钟用途"))
    }

    @Test fun startThenStopInOneBatchDoesNotProduceATransientHardwarePulse() {
        send(start(), stop())
        assertIdle()
        assertFalse(AppStore(app).logs().contains("已提交闹钟用途"))
    }

    @Test fun stopThenStartInOneBatchStartsNormally() {
        send(stop(), start())
        assertTrue(vibrator.isVibrating)
        assertTrue(AlarmService.active)
    }

    @Test fun targetedStopCancelsDelayedStartButAFutureNewStartStillWorks() {
        send(start().copy(delayMs = 5000))
        assertFalse(vibrator.isVibrating)
        send(stop("operator", scope = StopScope.SPECIFIC_RULE, target = "A"))
        advance(6000)
        assertIdle()
        controller!!.destroy(); controller = null
        send(start())
        assertTrue(vibrator.isVibrating)
        assertTrue(AlarmService.active)
    }

    @Test fun delayedStopOnlyStopsAtItsDeadline() {
        send(start(), stop().copy(delayMs = 5000))
        advance(4999)
        assertTrue(vibrator.isVibrating)
        advance(1)
        assertIdle()
    }

    @Test fun separateSourceDeadlinesDoNotShortenOrExtendEachOther() {
        send(start("A").copy(durationSeconds = 2), start("B").copy(durationSeconds = 5))
        advance(2000)
        assertTrue(vibrator.isVibrating)
        assertTrue(AlarmService.active)
        advance(2999)
        assertTrue(vibrator.isVibrating)
        advance(1)
        assertIdle()
    }

    @Test fun finiteRepeatContributionDoesNotTruncateAnUnlimitedContribution() {
        send(start("A").copy(repeatCount = 1), start("B"))
        advance(1200)
        assertTrue(vibrator.isVibrating)
        assertTrue(AlarmService.active)
        send(stop("B"))
        assertIdle()
    }

    @Test fun malformedCommandBatchDoesNotClearTheExistingReminder() {
        send(start())
        val malformed = Intent(app, AlarmService::class.java).putParcelableArrayListExtra(
            AlarmCommandTransport.COMMANDS, arrayListOf(Bundle().apply {
                putString("rule", "A"); putString("slot", "stop"); putString("type", "VIBRATE")
                putString("operation", "FUTURE"); putString("scope", "ALL_RULES")
            }))
        deliver(malformed)
        assertTrue(vibrator.isVibrating)
        assertTrue(AlarmService.active)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun overdueStopIsAppliedBeforeFilteringAnOldEpochIntentWhenTheTickHasNotRun() {
        send(start("A"), stop("A").copy(delayMs = 5000))
        val rule = AlarmRule(id = "A", name = "A", actions = listOf(RuleAction(ActionType.VIBRATE)))
        val old = queued(*ActionExecutor.localCommands(listOf(rule), false).toTypedArray())
        // Move uptime without running the main-looper STOP callback.
        ShadowSystemClock.advanceBy(Duration.ofMillis(5000))
        assertTrue(vibrator.isVibrating)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(old, 0, ++startId))
        assertIdle()
        shadowOf(Looper.getMainLooper()).idle()
        assertIdle()
    }

    @Test fun globalStopInvalidatesQueuedIntentsAndTheNextTickBeforeOnDestroy() {
        send(start().copy(durationSeconds = 5))
        val old = queued(start("B"))
        val generation = AlarmController.currentGeneration()
        AlarmController.stop(app)
        assertTrue(AlarmController.currentGeneration() > generation)
        // Shadow stopService does not deliver onDestroy: the scheduled service tick must also clear it.
        advance(5000)
        assertIdle()
        deliver(old)
        assertIdle()
        assertFalse(AlarmController.execute(app, listOf(start("C")), generation))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun pausedSmsAndLegacyDisabledActionsDoNotStartHardware() {
        assertTrue(AppStore(app).saveState(SentinelState(enabled = false)))
        send(start().copy(fromSms = true))
        assertIdle()
        val disabled = AlarmRule(id = "disabled", name = "旧禁用", actions = listOf(
            RuleAction(ActionType.VIBRATE, enabled = false), RuleAction(ActionType.RING, enabled = false)))
        val commands = ActionExecutor.localCommands(listOf(disabled), fromSms = true)
        assertTrue(commands.isEmpty())
        assertFalse(AlarmController.execute(app, commands))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun stopCancelsAnAlreadyIssuedIntentOnlyForItsOwnerAndChannel() {
        val a = AlarmRule(id = "A", name = "A", actions = listOf(RuleAction(ActionType.VIBRATE)))
        val old = queued(*ActionExecutor.localCommands(listOf(a), false).toTypedArray())
        send(start("B"), stop("operator", scope = StopScope.SPECIFIC_RULE, target = "A"))
        deliver(old)
        assertTrue(vibrator.isVibrating)
        send(stop("B"))
        assertIdle() // If A's old intent resurrected it, vibration would continue here.
        controller!!.destroy(); controller = null
        send(*ActionExecutor.localCommands(listOf(a), false).toTypedArray())
        assertTrue(vibrator.isVibrating)
    }
}
