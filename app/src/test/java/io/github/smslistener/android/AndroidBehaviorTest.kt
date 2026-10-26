package io.github.smslistener.android

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.os.VibrationAttributes
import android.provider.Telephony
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.smslistener.R
import io.github.smslistener.alarm.AlarmService
import io.github.smslistener.alarm.AlarmSpec
import io.github.smslistener.alarm.AlarmVibration
import io.github.smslistener.core.ActionOperation
import io.github.smslistener.core.ActionType
import io.github.smslistener.core.AlarmRule
import io.github.smslistener.core.ForwardTarget
import io.github.smslistener.core.ForwardType
import io.github.smslistener.core.MatchCondition
import io.github.smslistener.core.MatchMode
import io.github.smslistener.core.MatchRule
import io.github.smslistener.core.RuleAction
import io.github.smslistener.core.SentinelState
import io.github.smslistener.data.AppStore
import io.github.smslistener.data.RuleCodec
import org.json.JSONObject
import io.github.smslistener.sms.SmsProcessor
import io.github.smslistener.ui.MainActivity
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

/** Framework simulations verify contracts and lifecycle; they cannot validate a physical motor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class AndroidBehaviorTest {
    private lateinit var app: Application
    private var service: ServiceController<AlarmService>? = null

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("smslistener", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        service?.destroy()
        AlarmVibration.stop(app)
    }

    private fun rule(
        id: String = "r1",
        name: String = "测试规则",
        body: String = "告警",
        sender: String = "",
        actions: List<RuleAction> = listOf(RuleAction(ActionType.VIBRATE)),
    ) = AlarmRule(
        id = id,
        name = name,
        enabled = true,
        matchRules = listOf(
            MatchRule(
                senderNumber = sender.takeIf(String::isNotEmpty)
                    ?.let { MatchCondition(MatchMode.CONTAINS, it) },
                body = body.takeIf(String::isNotEmpty)
                    ?.let { MatchCondition(MatchMode.REGEX, it) },
            ),
        ),
        actions = actions,
    )

    private fun storeState(enabled: Boolean = true, vararg rules: AlarmRule): Boolean =
        AppStore(app).saveState(SentinelState(enabled = enabled, rules = rules.toList()))

    private fun launch(seconds: Int = 0): AlarmService {
        val controller = Robolectric.buildService(AlarmService::class.java).create()
        service = controller
        val instance = controller.get()
        instance.onStartCommand(alarmIntent(seconds), 0, 1)
        return instance
    }

    private fun alarmIntent(seconds: Int) = Intent(app, AlarmService::class.java)
        .putExtra(AlarmService.SOUND, false)
        .putExtra(AlarmService.VIBRATION, true)
        .putExtra(AlarmService.FLASH, false)
        .putExtra(AlarmService.DURATION, seconds)
        .putExtra(AlarmService.REASON, "自动化测试")

    @Test fun vibrationUsesAlarmAttributesAndIsCancelledOnStop() {
        assertTrue(AlarmVibration.start(app))
        val vibrator = shadowOf(AlarmVibration.vibrator(app))
        assertTrue(vibrator.isVibrating)
        assertEquals(0, vibrator.repeat)
        if (Build.VERSION.SDK_INT >= 33) {
            val attrs = vibrator.vibrationAttributesFromLastVibration as VibrationAttributes
            assertEquals(VibrationAttributes.USAGE_ALARM, attrs.usage)
        } else {
            assertEquals(android.media.AudioAttributes.USAGE_ALARM, requireNotNull(vibrator.audioAttributesFromLastVibration).usage)
        }
        AlarmVibration.stop(app)
        assertFalse(vibrator.isVibrating)
    }

    @Test fun noMotorDoesNotPretendToSubmitAVibration() {
        shadowOf(AlarmVibration.vibrator(app)).setHasVibrator(false)
        assertFalse(AlarmVibration.start(app))
    }

    @Test fun indefiniteAlarmSurvivesTimeAdvanceAndReleasesOnDestroy() {
        val instance = launch()
        assertTrue(AlarmService.active)
        assertNotNull(shadowOf(instance).lastForegroundNotification)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
        assertFalse(shadowOf(instance).isStoppedBySelf)
        assertTrue(shadowOf(AlarmVibration.vibrator(app)).isVibrating)
        service!!.destroy()
        service = null
        assertFalse(AlarmService.active)
        assertFalse(shadowOf(AlarmVibration.vibrator(app)).isVibrating)
        assertTrue(shadowOf(instance).isForegroundStopped)
    }

    @Test fun repeatedTriggerDoesNotExtendOriginalDeadline() {
        val instance = launch(5)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        instance.onStartCommand(alarmIntent(60), 0, 2)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(shadowOf(instance).isStoppedBySelf)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(shadowOf(instance).isStoppedBySelf)
    }

    @Test fun pausedMonitoringRejectsAnAlreadyQueuedSmsAlarm() {
        storeState(false, rule())
        val controller = Robolectric.buildService(AlarmService::class.java).create()
        service = controller
        val instance = controller.get()
        instance.onStartCommand(alarmIntent(0).putExtra(AlarmService.FROM_SMS, true), 0, 1)
        assertTrue(shadowOf(instance).isStoppedBySelf)
        assertFalse(AlarmService.active)
        assertFalse(shadowOf(AlarmVibration.vibrator(app)).isVibrating)
    }

    @Test fun foregroundFailureIsLoggedWithoutStartingVibration() {
        val controller = Robolectric.buildService(AlarmService::class.java).create()
        service = controller
        val instance = controller.get()
        shadowOf(instance).setThrowInStartForeground(SecurityException("test"))
        instance.onStartCommand(alarmIntent(0), 0, 1)
        assertTrue(shadowOf(instance).isStoppedBySelf)
        assertFalse(AlarmService.active)
        assertTrue(AppStore(app).logs().contains("SecurityException"))
        assertFalse(shadowOf(AlarmVibration.vibrator(app)).isVibrating)
    }

    @Test fun storageSurvivesRepositoryRecreationAndLogsAreBounded() {
        val state = SentinelState(enabled = true, rules = listOf(rule()))
        assertTrue(AppStore(app).saveState(state))
        assertEquals(state, AppStore(app).state())
        assertFalse(AppStore(app).isDuplicate("fingerprint"))
        assertTrue(AppStore(app).isDuplicate("fingerprint"))
        repeat(205) { AppStore(app).log("event-$it") }
        val log = AppStore(app).logs()
        assertEquals(200, log.split("\n\n").size)
        assertTrue(log.contains("event-204"))
        assertFalse(log.contains("event-0\n"))
    }

    @Test fun storedSchemaFiveDisabledActionsStayInactiveAndNextSaveWritesSix() {
        val old = SentinelState(rules = listOf(rule(actions = listOf(
            RuleAction(ActionType.VIBRATE, enabled = false, delayMs = 41, durationSeconds = 19, repeatCount = 3),
        ))))
        val raw = JSONObject(RuleCodec.encode(old)).put("schema", 5)
        raw.getJSONArray("rules").getJSONObject(0).getJSONArray("actions").getJSONObject(0).apply {
            remove("operation")
            remove("stopScope")
            remove("targetRuleId")
        }
        val prefs = app.getSharedPreferences("smslistener", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("state_v2", raw.toString()).commit())
        val restored = AppStore(app).state()
        assertEquals(old, restored)
        val action = restored.rules.single().actions.single()
        assertFalse(action.enabled)
        assertEquals(ActionOperation.START, action.operation)
        assertTrue(restored.rules.single().localActions().isEmpty())
        assertTrue(AppStore(app).saveState(restored))
        assertEquals(6, JSONObject(requireNotNull(prefs.getString("state_v2", null))).getInt("schema"))
        assertEquals(restored, AppStore(app).state())
    }

    @Test fun editingOneRuleKeepsTheOthers() {
        val store = AppStore(app)
        store.saveState(SentinelState(rules = listOf(rule(id = "a", name = "A"), rule(id = "b", name = "B"))))
        store.saveRule(rule(id = "b", name = "B 改名"))
        assertEquals(listOf("A", "B 改名"), store.rules().map { it.name })
        assertTrue(store.deleteRule("a"))
        assertEquals(listOf("B 改名"), store.rules().map { it.name })
    }

    @Test fun malformedStoredStateFailsClosed() {
        app.getSharedPreferences("smslistener", Context.MODE_PRIVATE).edit().putString("state_v2", "garbage").commit()
        // Monitoring defaults to on, but a damaged store must never invent rules.
        assertTrue(AppStore(app).state().rules.isEmpty())
        assertTrue(AppStore(app).state().forwards.isEmpty())
    }

    @Test fun parsedSmsPartsMatchAcrossBoundaryAndStartOnlyOneAlarm() {
        storeState(true, rule(body = "告警|故障"))
        val intent = smsIntent("测试：设备故障", "告警请处理")
        SmsProcessor(app).process(intent)
        val started = shadowOf(app).nextStartedService
        assertEquals(AlarmService::class.java.name, started.component?.className)
        assertTrue(started.getBooleanExtra(AlarmService.FROM_SMS, false))
        SmsProcessor(app).process(intent)
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(AppStore(app).logs().contains("重复"))
        assertFalse(AppStore(app).logs().contains("请处理"))
    }

    @Test fun unmatchedSmsDoesNotStartAnAlarm() {
        storeState(true, rule(body = "告警"))
        SmsProcessor(app).process(smsIntent("您的订单已送达"))
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(AppStore(app).logs().contains("命中 0 条规则"))
    }

    @Test fun senderFilterPreventsAnAlarm() {
        storeState(true, rule(sender = "95588", body = "告警"))
        SmsProcessor(app).process(smsIntent("告警：磁盘故障"))
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(AppStore(app).logs().contains("命中 0 条规则"))
    }

    @Test fun monitoringIsEnabledByDefaultWithoutAnySwitch() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertTrue(AppStore(app).state().enabled)
        assertTrue(activity.findViewById<View>(R.id.nav_home).isSelected)
        controller.pause().stop().destroy()
    }

    @Test fun ruleListRendersEverySavedRule() {
        val store = AppStore(app)
        store.saveRule(rule(id = "a", name = "服务器告警"))
        store.saveRule(rule(id = "b", name = "银行动账", body = "余额"))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.nav_rules).performClick()
        val list = activity.findViewById<ViewGroup>(R.id.rule_list)
        assertEquals(2, list.childCount)
        val names = views(list).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(names.contains("服务器告警"))
        assertTrue(names.contains("银行动账"))
        controller.pause().stop().destroy()
    }

    @Test fun httpForwardConfigSavesWithoutCrashing() {
        val intent = Intent(app, io.github.smslistener.ui.ForwardConfigActivity::class.java)
            .putExtra(io.github.smslistener.ui.ForwardConfigActivity.EXTRA_TYPE, ForwardType.HTTP.name)
        val controller = Robolectric.buildActivity(io.github.smslistener.ui.ForwardConfigActivity::class.java, intent).setup()
        val activity = controller.get()
        val edits = views(activity.window.decorView).filterIsInstance<android.widget.EditText>()
        assertTrue("form should expose name and url", edits.size >= 2)
        edits[0].setText("运维群")
        edits[1].setText("https://example.com/hook")
        // The HTTP form has no secret field; saving used to crash on that lateinit.
        val save = views(activity.window.decorView)
            .filterIsInstance<android.widget.TextView>()
            .first { it.text.toString() == "保存" }
        save.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val stored = AppStore(app).state().forwards
        assertEquals(1, stored.size)
        assertEquals("运维群", stored.first().name)
        assertEquals("https://example.com/hook", stored.first().url)
        controller.pause().stop().destroy()
    }

    @Test fun permissionsPageListsTheBatteryWhitelistEntry() {
        val controller = Robolectric.buildActivity(io.github.smslistener.ui.PermissionsActivity::class.java).setup()
        val labels = views(controller.get().window.decorView)
            .filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(labels.contains("电池优化白名单"))
        assertTrue(labels.any { it.contains("读取短信") })
        assertTrue(labels.any { it.contains("自启动") })
        controller.pause().stop().destroy()
    }

    @Test fun permissionRowsRefreshWhenWindowFocusReturns() {
        val controller = Robolectric.buildActivity(io.github.smslistener.ui.PermissionsActivity::class.java).setup()
        val activity = controller.get()
        val power = activity.getSystemService(android.os.PowerManager::class.java)
        val shadowPower = shadowOf(power)

        shadowPower.setIgnoringBatteryOptimizations(activity.packageName, false)
        activity.onWindowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        fun batteryRow(): String = views(activity.window.decorView)
            .filterIsInstance<TextView>().map { it.text.toString() }
            .first { it.startsWith("未加入") || it.startsWith("已加入") }
        assertTrue(batteryRow().startsWith("未加入"))

        // Simulate the user allowing the system dialog and coming back.
        shadowPower.setIgnoringBatteryOptimizations(activity.packageName, true)
        activity.onWindowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertTrue("row should refresh without reopening the page", batteryRow().startsWith("已加入"))
        controller.pause().stop().destroy()
    }

    @Test fun editorPrefillsAnExistingRule() {
        val store = AppStore(app)
        store.saveForward(ForwardTarget(id = "f1", name = "告警群", type = ForwardType.FEISHU, url = "https://open.feishu.cn/x"))
        store.saveRule(
            rule(id = "r1", name = "银行动账", body = "余额|消费", sender = "95588").copy(
                description = "储蓄卡",
                actions = listOf(
                    RuleAction(ActionType.VIBRATE, durationSeconds = 30),
                    RuleAction(ActionType.FORWARD, forwardIds = listOf("f1")),
                ),
            ),
        )
        val intent = Intent(app, io.github.smslistener.ui.RuleEditActivity::class.java)
            .putExtra(io.github.smslistener.ui.RuleEditActivity.EXTRA_ID, "r1")
        val controller = Robolectric.buildActivity(io.github.smslistener.ui.RuleEditActivity::class.java, intent).setup()
        val activity = controller.get()
        val edits = views(activity.window.decorView).filterIsInstance<android.widget.EditText>()
        // Only basic info remains here; conditions own their configuration pages.
        assertEquals(2, edits.size)
        assertEquals("银行动账", edits[0].text.toString())
        assertEquals("储蓄卡", edits[1].text.toString())
        val labels = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(labels.any { it.contains("振动") })
        assertTrue(labels.any { it.contains("转发") })
        assertTrue(labels.any { it.contains("95588") })
        controller.pause().stop().destroy()
    }

    @Test fun alarmBannerTracksLifecycleOnEveryTab() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.active_alarm_banner).visibility)
        launch()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.active_alarm_banner).visibility)
        for (id in listOf(R.id.nav_rules, R.id.nav_settings, R.id.nav_home)) {
            activity.findViewById<View>(id).performClick()
            assertTrue(activity.findViewById<View>(R.id.stop_alarm).isShown)
        }
        service!!.destroy(); service = null
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.active_alarm_banner).visibility)
        controller.pause().stop().destroy()
    }

    @Test fun databaseChannelTriggersAnAlarmAndDeduplicates() {
        storeState(true, rule(body = "验证码"))
        io.github.smslistener.sms.SmsDatabaseWatcher.handle(app, "19293594027", "验证码 1234", 1_700_000_000_000, 1)
        val started = shadowOf(app).nextStartedService
        assertEquals(AlarmService::class.java.name, started.component?.className)

        // Same message inside the same 10s bucket must not fire twice.
        io.github.smslistener.sms.SmsDatabaseWatcher.handle(app, "19293594027", "验证码 1234", 1_700_000_000_500, 1)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun databaseChannelIgnoresUnmatchedSms() {
        storeState(true, rule(body = "告警"))
        io.github.smslistener.sms.SmsDatabaseWatcher.handle(app, "19293594027", "验证码 1234", 1_700_000_100_000, 1)
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(AppStore(app).logs().contains("命中 0 条规则"))
    }

    @Test fun databaseChannelRespectsTheGlobalSwitch() {
        storeState(false, rule(body = "验证码"))
        io.github.smslistener.sms.SmsDatabaseWatcher.handle(app, "19293594027", "验证码 1234", 1_700_000_200_000, 1)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun revealedDeleteButtonReceivesTheTap() {
        var deleted = false
        val row = io.github.smslistener.ui.SwipeRevealRow(app, "删除") { deleted = true }
        row.setRow(View(app))
        row.measure(
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
        )
        row.layout(0, 0, 300, 120)
        row.open()
        shadowOf(Looper.getMainLooper()).idle()

        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 295f, 60f, 0)
        assertFalse("tap on the delete area must not be intercepted", row.onInterceptTouchEvent(down))
        down.recycle()

        val contentDown = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 40f, 60f, 0)
        assertTrue("tap on the row itself closes it", row.onInterceptTouchEvent(contentDown))
        contentDown.recycle()
    }

    @Test fun deletingARuleFromTheListRemovesItFromStorage() {
        val store = AppStore(app)
        store.saveRule(rule(id = "a", name = "规则甲"))
        store.saveRule(rule(id = "b", name = "规则乙"))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.nav_rules).performClick()
        val list = activity.findViewById<ViewGroup>(R.id.rule_list)
        assertEquals(2, list.childCount)

        val row = list.getChildAt(0) as io.github.smslistener.ui.SwipeRevealRow
        row.open()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        row.findViewWithTag<View>("delete_button").performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        dialog!!.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, store.rules().size)
        assertEquals("规则乙", store.rules().first().name)
        assertEquals(1, activity.findViewById<ViewGroup>(R.id.rule_list).childCount)
        controller.pause().stop().destroy()
    }

    @Test fun tappingTheRevealedDeleteButtonRunsTheCallback() {
        var deleted = false
        val row = io.github.smslistener.ui.SwipeRevealRow(app, "删除") { deleted = true }
        row.setRow(View(app))
        row.measure(
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
        )
        row.layout(0, 0, 300, 120)
        row.open()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))

        val button = row.findViewWithTag<View>("delete_button")
        assertNotNull(button)
        assertEquals(View.VISIBLE, button.visibility)
        button.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(deleted)
    }

    private fun smsIntent(vararg bodies: String): Intent {
        // Synthetic GSM SMS-DELIVER PDUs with UCS-2 encoding, sender 106900.
        val pdus = bodies.map { body ->
            val bytes = body.toByteArray(Charsets.UTF_16BE)
            byteArrayOf(0, 0, 6, 0x81.toByte(), 0x01, 0x96.toByte(), 0, 0, 8,
                0x62, 0x01, 0x40, 0x21, 0, 0, 0, bytes.size.toByte()) + bytes
        }.toTypedArray()
        return Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
            .putExtra("format", "3gpp").putExtra("pdus", pdus).putExtra("subscription", 1)
    }

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
