package io.github.smslistener.android

import android.Manifest
import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.provider.Telephony
import io.github.smslistener.alarm.AlarmCommandTransport
import io.github.smslistener.alarm.AlarmController
import io.github.smslistener.alarm.AlarmService
import io.github.smslistener.alarm.AlarmVibration
import io.github.smslistener.core.ActionOperation
import io.github.smslistener.core.ActionType
import io.github.smslistener.core.AlarmRule
import io.github.smslistener.core.MatchCondition
import io.github.smslistener.core.MatchMode
import io.github.smslistener.core.MatchRule
import io.github.smslistener.core.RuleAction
import io.github.smslistener.core.SentinelState
import io.github.smslistener.core.StopScope
import io.github.smslistener.core.TriggerSource
import io.github.smslistener.data.AppStore
import io.github.smslistener.monitor.MonitorService
import io.github.smslistener.sms.SmsDatabaseWatcher
import io.github.smslistener.sms.SmsProcessor
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaPlayer
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Exercise the saved SMS rules and the real monitor receiver, not direct engine calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
@LooperMode(LooperMode.Mode.PAUSED)
class SmsUnlockFlowTest {
    private lateinit var app: Application
    private var monitorController: ServiceController<MonitorService>? = null
    private var alarmController: ServiceController<AlarmService>? = null
    private val players = mutableListOf<ShadowMediaPlayer>()
    private var startId = 0
    private val store get() = AppStore(app)
    private val vibrator get() = shadowOf(AlarmVibration.vibrator(app))

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        // Only sandbox preferences; no external document binding or forward target is installed.
        app.getSharedPreferences("smslistener", Context.MODE_PRIVATE).edit().clear().commit()
        // Screen events may ask for a catch-up query. These tests supply messages explicitly,
        // so never let that independent path read an inbox or create extra starts.
        shadowOf(app).denyPermissions(Manifest.permission.READ_SMS)
        AlarmController.stop(app)
        AlarmVibration.stop(app)
        vibrator.setHasVibrator(true)
        ShadowMediaPlayer.resetStaticState()
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(1000, 0) }
        ShadowMediaPlayer.setCreateListener { _, shadow -> players += shadow }
        shadowOf(app.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    @After fun tearDown() {
        try {
            monitorController?.let { controller ->
                val worker = worker()
                controller.destroy()
                assertTrue("Monitor worker must terminate before clearing preferences",
                    worker.awaitTermination(5, TimeUnit.SECONDS))
            }
        } finally {
            monitorController = null
            try {
                destroyAlarm()
            } finally {
                AlarmVibration.stop(app)
                AlarmController.stop(app)
                // Clears both the provider and create listener; a null provider is not accepted.
                ShadowMediaPlayer.resetStaticState()
                players.clear()
                app.getSharedPreferences("smslistener", Context.MODE_PRIVATE).edit().clear().commit()
                idleMain()
            }
        }
    }

    @Test fun savedSenderRestrictionRejectsOrdinarySmsAndUnlockOnlyStopsAnIdleService() {
        val saved = installRules(MatchCondition(MatchMode.CONTAINS, "12123"))
        assertEquals(MatchCondition(MatchMode.CONTAINS, "12123"),
            store.rules().first().matchRules.single().senderNumber)
        startMonitor()

        receiveSms { SmsProcessor(app).process(smsIntent("普通短信")) }
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(store.logs().contains("命中 0 条规则"))
        assertFalse(AlarmService.active)
        assertFalse(vibrator.isVibrating)
        assertTrue(players.isEmpty())
        assertNull(alarmController)

        screenEvent(Intent.ACTION_SCREEN_ON)
        assertNull(shadowOf(app).nextStartedService)
        screenEvent(Intent.ACTION_USER_PRESENT)
        deliverAlarm(takeAlarmIntent("B", ActionOperation.STOP, fromSms = false))

        assertStopped()
        assertTrue("An idle STOP must not even construct a media player", players.isEmpty())
        val logs = store.logs()
        assertTrue(logs.contains("已接收本机动作：启动 0 个，停止 2 个"))
        assertTrue(logs.contains(IDLE_STOP_LOG))
        assertFalse(logs.contains("已提交闹钟用途的循环振动请求"))
        assertFalse(logs.contains("已开始循环铃声"))
        assertFalse(logs.contains("本机提醒任务运行中"))
        assertFalse(logs.contains("报警任务已结束"))

        // Robolectric records stopSelf but does not automatically deliver onDestroy.
        destroyAlarm()
        assertTrue(store.logs().contains(SERVICE_EXIT_LOG))
        assertFalse(store.logs().contains("报警任务已结束"))
        assertEquals("Events must not rewrite either saved rule", saved, store.state())
    }

    @Test fun unrestrictedSmsPduStartsOnlyAAndRealUnlockStopsBothChannelsThenANewSmsWorks() {
        exerciseUnlockFlow { index ->
            // Same sender/body, but a new timestamp outside the shared deduplication bucket.
            SmsProcessor(app).process(smsIntent("普通短信", seconds = index * 20))
        }
    }

    @Test fun unrestrictedDatabaseSmsStartsOnlyAAndRealUnlockStopsBothChannelsThenANewSmsWorks() {
        exerciseUnlockFlow { index ->
            SmsDatabaseWatcher.handle(app, "106900", "普通短信",
                1_700_000_000_000L + index * 20_000L, 1)
        }
    }

    private fun exerciseUnlockFlow(process: (Int) -> Unit) {
        val saved = installRules()
        assertTrue(store.rules().first().matchRules.single().isEmpty)
        startMonitor()

        repeat(2) { index ->
            receiveSms { process(index) }
            assertTrue(store.logs().contains("命中 1 条规则"))
            deliverAlarm(takeAlarmIntent("A", ActionOperation.START, fromSms = true))
            assertEquals("Each new SMS creates one ring player, never one for rule B", index + 1, players.size)
            val player = players.last()
            assertRunning(player)

            screenEvent(Intent.ACTION_SCREEN_ON)
            assertNull("Screen-on is not an unlock STOP", shadowOf(app).nextStartedService)
            assertRunning(player)
            assertEquals(index + 1, players.size)

            screenEvent(Intent.ACTION_USER_PRESENT)
            val stop = takeAlarmIntent("B", ActionOperation.STOP, fromSms = false)
            // The worker queues commands; hardware changes only when Android delivers them on main.
            assertRunning(player)
            deliverAlarm(stop)
            assertStopped()
            assertEquals(ShadowMediaPlayer.State.END, player.state)
            assertEquals("STOP must not create replacement playback", index + 1, players.size)
            assertTrue(players.all { it.state == ShadowMediaPlayer.State.END })
            assertFalse("Stopping a running alarm is not an idle STOP", store.logs().contains(IDLE_STOP_LOG))

            destroyAlarm()
            assertTrue(store.logs().contains(SERVICE_EXIT_LOG))
            assertFalse(store.logs().contains("报警任务已结束"))
            assertEquals("STOP is an operation, not a permanent change to the SMS rule", saved, store.state())
            // Iteration two uses a new service and freshly captured cancellation epochs.
        }
    }

    private fun installRules(sender: MatchCondition? = null): SentinelState {
        val state = SentinelState(enabled = true, rules = listOf(
            AlarmRule(id = "A", name = "短信启动", matchRules = listOf(
                MatchRule(id = "a-sms", source = TriggerSource.SMS, senderNumber = sender),
            ), actions = listOf(
                RuleAction(ActionType.VIBRATE, operation = ActionOperation.START),
                RuleAction(ActionType.RING, operation = ActionOperation.START),
            )),
            AlarmRule(id = "B", name = "解锁停止", matchRules = listOf(
                MatchRule(id = "b-unlock", source = TriggerSource.UNLOCK, delayMs = 0),
            ), actions = listOf(
                RuleAction(ActionType.VIBRATE, operation = ActionOperation.STOP,
                    stopScope = StopScope.ALL_RULES, delayMs = 0),
                RuleAction(ActionType.RING, operation = ActionOperation.STOP,
                    stopScope = StopScope.ALL_RULES, delayMs = 0),
            )),
        ))
        state.rules.forEach { assertNull(it.validationError()) }
        assertTrue(store.saveState(state))
        assertEquals(state, store.state())
        return state
    }

    private fun startMonitor() {
        val controller = Robolectric.buildService(MonitorService::class.java).create()
        monitorController = controller
        assertEquals(Service.START_STICKY, controller.get().onStartCommand(null, 0, 1))
        drainMonitor()
        assertNull(shadowOf(app).nextStartedService)
    }

    private fun worker(): ScheduledThreadPoolExecutor = MonitorService::class.java
        .getDeclaredField("worker").apply { isAccessible = true }
        .get(requireNotNull(monitorController).get()) as ScheduledThreadPoolExecutor

    private fun drainMonitor() {
        idleMain()
        // Screen dispatch can enqueue its zero-delay action behind the first barrier.
        repeat(2) { worker().submit {}.get(5, TimeUnit.SECONDS) }
        idleMain()
    }

    private fun receiveSms(process: () -> Unit) {
        worker().submit { process() }.get(5, TimeUnit.SECONDS)
        drainMonitor()
    }

    private fun screenEvent(action: String) {
        app.sendBroadcast(Intent(action))
        drainMonitor()
    }

    private fun takeAlarmIntent(owner: String, operation: ActionOperation, fromSms: Boolean): Intent {
        val intent = requireNotNull(shadowOf(app).nextStartedService) { "Expected $operation from rule $owner" }
        assertEquals(AlarmService::class.java.name, intent.component?.className)
        assertEquals(fromSms, intent.getBooleanExtra(AlarmService.FROM_SMS, !fromSms))
        val commands = AlarmCommandTransport.read(intent)
        assertEquals(listOf(ActionType.VIBRATE, ActionType.RING), commands.map { it.type })
        assertEquals(listOf(owner, owner), commands.map { it.sourceRuleId })
        commands.forEach { command ->
            assertEquals(operation, command.operation)
            assertEquals(fromSms, command.fromSms)
            assertEquals(0L, command.delayMs)
            if (operation == ActionOperation.STOP) assertEquals(StopScope.ALL_RULES, command.stopScope)
        }
        assertNull("One event must enqueue exactly one batch", shadowOf(app).nextStartedService)
        return intent
    }

    private fun deliverAlarm(intent: Intent) {
        assertSame("Service intents must be delivered on main, not the monitor worker",
            Looper.getMainLooper(), Looper.myLooper())
        val controller = alarmController ?: Robolectric.buildService(AlarmService::class.java).create()
            .also { alarmController = it }
        assertEquals(Service.START_NOT_STICKY, controller.get().onStartCommand(intent, 0, ++startId))
        idleMain()
    }

    private fun assertRunning(player: ShadowMediaPlayer) {
        assertTrue(AlarmService.active)
        assertTrue(vibrator.isVibrating)
        assertTrue(player.isReallyPlaying)
        assertFalse(shadowOf(requireNotNull(alarmController).get()).isStoppedBySelf)
    }

    private fun assertStopped() {
        assertFalse(AlarmService.active)
        assertFalse(vibrator.isVibrating)
        assertTrue(shadowOf(requireNotNull(alarmController).get()).isStoppedBySelf)
    }

    private fun destroyAlarm() {
        alarmController?.destroy()
        alarmController = null
        idleMain()
    }

    // Do not runToEndOfTasks/idleFor: looping media and reminder deadlines are not work to drain.
    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun smsIntent(body: String, seconds: Int = 0): Intent {
        // Synthetic GSM SMS-DELIVER, UCS-2, sender 106900, as in AndroidBehaviorTest.
        require(seconds in 0..59)
        val encodedSeconds = (((seconds % 10) shl 4) or (seconds / 10)).toByte()
        val bytes = body.toByteArray(Charsets.UTF_16BE)
        val pdu = byteArrayOf(0, 0, 6, 0x81.toByte(), 0x01, 0x96.toByte(), 0, 0, 8,
            0x62, 0x01, 0x40, 0x21, 0, encodedSeconds, 0, bytes.size.toByte()) + bytes
        return Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
            .putExtra("format", "3gpp").putExtra("pdus", arrayOf(pdu)).putExtra("subscription", 1)
    }

    private companion object {
        const val IDLE_STOP_LOG = "停止指令已处理：当前服务没有运行或等待中的报警"
        const val SERVICE_EXIT_LOG = "本机动作服务已退出，资源已释放"
    }
}
