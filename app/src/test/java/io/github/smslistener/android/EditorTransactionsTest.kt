package io.github.smslistener.android

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import io.github.smslistener.core.ActionOperation
import io.github.smslistener.core.ActionType
import io.github.smslistener.core.AlarmRule
import io.github.smslistener.core.ForwardTarget
import io.github.smslistener.core.ForwardType
import io.github.smslistener.core.MatchCondition
import io.github.smslistener.core.MatchMode
import io.github.smslistener.core.MatchRule
import io.github.smslistener.core.RuleAction
import io.github.smslistener.core.StopScope
import io.github.smslistener.core.TriggerSource
import io.github.smslistener.data.AppStore
import io.github.smslistener.data.RuleCodec
import io.github.smslistener.ui.ActionConfigActivity
import io.github.smslistener.ui.ForwardPickerActivity
import io.github.smslistener.ui.MatchRuleActivity
import io.github.smslistener.ui.RuleEditActivity
import io.github.smslistener.ui.TriggerTypeActivity
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivity.IntentForResult
import org.robolectric.shadows.ShadowDialog

/** Exercises view clicks, result callbacks and saved-state contracts, never private editor fields. */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class EditorTransactionsTest {
    private lateinit var app: Application
    private lateinit var store: AppStore
    private val controllers = mutableListOf<ActivityController<out Activity>>()

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        assertTrue(app.getSharedPreferences("smslistener", Context.MODE_PRIVATE).edit().clear().commit())
        store = AppStore(app)
    }

    @After fun tearDown() {
        ShadowDialog.getShownDialogs().toList().forEach { if (it.isShowing) it.dismiss() }
        controllers.asReversed().forEach { it.close() }
        controllers.clear()
        app.getSharedPreferences("smslistener", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun parentHasNoLegacyGlobalTriggerControls() {
        val parent = parent()
        val edits = views(root(parent)).filterIsInstance<EditText>()
        assertEquals(listOf("规则名称", "规则描述"), edits.map { it.contentDescription.toString() })
        val texts = labels(root(parent))
        assertFalse(texts.any { it == "触发方式" || it == "触发来源" || it == "触发延迟" })
        TriggerSource.entries.forEach { source ->
            assertNull(root(parent).findViewWithTag<View>("type_" + source.name))
        }
        assertNull(root(parent).findViewWithTag<View>("condition_delay"))
        assertRows(parent, 0, 0)
        val json = JSONObject(stateOf(parent).getString("editor_draft_json")!!)
        assertFalse(json.has("trigger"))
        assertFalse(json.has("triggerDelayMs"))
    }

    @Test fun newTriggerOpensIndependentTypePageAndCancelLeavesNoPlaceholder() {
        val parent = parent()
        val previousDialog = ShadowDialog.getLatestDialog()
        val request = beginMatch(parent)
        assertSame(previousDialog, ShadowDialog.getLatestDialog())
        assertRows(parent, 0, 0)
        // A second tap while the child is open must not start a competing transaction.
        click(parent, "+ 添加触发条件")
        assertNull(shadowOf(parent).nextStartedActivityForResult)
        val types = launch(TriggerTypeActivity::class.java, request.intent).get()
        val order = listOf(TriggerSource.SMS, TriggerSource.UNLOCK, TriggerSource.LOCK, TriggerSource.HTTP)
        val options = order.map { tagged<View>(types, "type_" + it.name) }
        assertTrue(options.all { it.parent === options.first().parent })
        assertEquals(listOf("短信", "解锁屏幕", "锁定屏幕", "HTTP(S)"), order.map { it.label })
        assertEquals(order.map { "type_" + it.name },
            views(root(types)).filter { it.tag?.toString()?.startsWith("type_") == true }.map { it.tag })
        options.dropLast(1).forEach { assertTrue(it.isEnabled); assertTrue(it.isClickable) }
        click(types, "返回")
        assertTrue(types.isFinishing)
        deliver(parent, request, types)
        assertRows(parent, 0, 0)
        assertTrue(draft(parent).matchRules.isEmpty())
        assertTrue(store.rules().isEmpty())
        assertEquals(0, stateOf(parent).getInt("pending_request"))
    }

    @Test fun triggerTypeDoubleTapAndRecreationKeepOnePendingEditor() {
        val parent = parent()
        val request = beginMatch(parent)
        var types = launch(TriggerTypeActivity::class.java, request.intent).get()
        val first = chooseType(types, TriggerSource.SMS)
        tagged<View>(types, "type_LOCK").performClick()
        assertNull(shadowOf(types).nextStartedActivityForResult)
        types = rebuild(types)
        tagged<View>(types, "type_UNLOCK").performClick()
        assertNull(shadowOf(types).nextStartedActivityForResult)
        types.onActivityResult(first.requestCode, Activity.RESULT_CANCELED, null)
        assertFalse(types.isFinishing)
        val next = chooseType(types, TriggerSource.LOCK)
        val config = launch(MatchRuleActivity::class.java, next.intent).get()
        click(config, "确定")
        types.onActivityResult(next.requestCode, shadowOf(config).resultCode, shadowOf(config).resultIntent)
        deliver(parent, request, types)
        assertRows(parent, 1, 0)
        assertEquals(TriggerSource.LOCK, draft(parent).matchRules.single().source)
    }

    @Test fun httpTypeIsGreyDisabledAndDoesNotNavigate() {
        val types = launch(TriggerTypeActivity::class.java).get()
        val http = tagged<View>(types, "type_HTTP")
        assertFalse(http.isEnabled)
        assertFalse(http.isClickable)
        assertFalse(http.isFocusable)
        assertTrue(http.alpha < tagged<View>(types, "type_SMS").alpha)
        assertFalse(http.performClick())
        idle()
        assertNull(shadowOf(types).nextStartedActivityForResult)
        assertFalse(types.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(types).resultCode)
    }

    @Test fun blankSmsAddsOneUnrestrictedTrigger() {
        val parent = parent()
        val request = beginMatch(parent)
        val types = launch(TriggerTypeActivity::class.java, request.intent).get()
        val configRequest = chooseType(types, TriggerSource.SMS)
        val config = launch(MatchRuleActivity::class.java, configRequest.intent).get()
        MatchFieldTags.forEach { assertNotNull(tagged<EditText>(config, it)) }
        assertNull(root(config).findViewWithTag<View>("condition_delay"))
        click(config, "确定")
        assertTrue(config.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(config).resultCode)
        val saved = matchResult(shadowOf(config).resultIntent)
        assertEquals(TriggerSource.SMS, saved.source)
        assertTrue(saved.conditions().isEmpty())
        assertNull(saved.validationError())
        types.onActivityResult(configRequest.requestCode, shadowOf(config).resultCode, shadowOf(config).resultIntent)
        deliver(parent, request, types)
        assertEquals(listOf(saved), draft(parent).matchRules)
        assertRows(parent, 1, 0)
    }

    @Test fun invalidSmsStaysInConfigAndBackNeverAddsATrigger() {
        val parent = parent()
        val request = beginMatch(parent)
        val types = launch(TriggerTypeActivity::class.java, request.intent).get()
        val configRequest = chooseType(types, TriggerSource.SMS)
        val config = launch(MatchRuleActivity::class.java, configRequest.intent).get()
        tagged<EditText>(config, "condition_BODY").setText("[")
        assertTrue(tagged<View>(config, "mode_BODY_REGEX").performClick())
        click(config, "确定")
        assertValidationDialog(config, "正则格式有误")
        assertRows(parent, 0, 0)
        tagged<EditText>(config, "condition_BODY").setText("未提交的内容")
        click(config, "返回")
        types.onActivityResult(configRequest.requestCode, shadowOf(config).resultCode, shadowOf(config).resultIntent)
        assertFalse(types.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(types).resultCode)
        click(types, "返回")
        deliver(parent, request, types)
        assertRows(parent, 0, 0)
        assertTrue(draft(parent).matchRules.isEmpty())
    }

    @Test fun confirmingSmsAddsExactlyOneAndDuplicateDeliveryDoesNotAppend() {
        val parent = parent()
        val request = beginMatch(parent)
        val types = launch(TriggerTypeActivity::class.java, request.intent).get()
        val configRequest = chooseType(types, TriggerSource.SMS)
        val config = launch(MatchRuleActivity::class.java, configRequest.intent).get()
        tagged<EditText>(config, "condition_LOCAL_NUMBER").setText("13800138000")
        tagged<EditText>(config, "condition_SENDER_NUMBER").setText("95588")
        tagged<EditText>(config, "condition_BODY").setText("余额|消费")
        assertTrue(tagged<View>(config, "mode_LOCAL_NUMBER_EQUALS").performClick())
        assertTrue(tagged<View>(config, "mode_BODY_REGEX").performClick())
        assertRows(parent, 0, 0)
        click(config, "确定")
        assertTrue(config.isFinishing)
        types.onActivityResult(configRequest.requestCode, shadowOf(config).resultCode, shadowOf(config).resultIntent)
        assertTrue(types.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(types).resultCode)
        deliver(parent, request, types)
        val saved = draft(parent).matchRules.single()
        assertEquals(TriggerSource.SMS, saved.source)
        assertEquals(MatchCondition(MatchMode.EQUALS, "13800138000"), saved.localNumber)
        assertEquals(MatchCondition(MatchMode.CONTAINS, "95588"), saved.senderNumber)
        assertEquals(MatchCondition(MatchMode.REGEX, "余额|消费"), saved.body)
        deliver(parent, request, types)
        assertEquals(listOf(saved), draft(parent).matchRules)
        assertRows(parent, 1, 0)
        assertTrue(store.rules().isEmpty())
    }

    @Test fun lockAndUnlockConfigurationDefaultToZeroDelay() {
        listOf(TriggerSource.UNLOCK, TriggerSource.LOCK).forEach { source ->
            val types = launch(TriggerTypeActivity::class.java).get()
            val request = chooseType(types, source)
            val config = launch(MatchRuleActivity::class.java, request.intent).get()
            assertEquals("0", tagged<EditText>(config, "condition_delay").text.toString())
            assertEquals(1, views(root(config)).filterIsInstance<EditText>().size)
            click(config, "确定")
            assertEquals(Activity.RESULT_OK, shadowOf(config).resultCode)
            val result = matchResult(shadowOf(config).resultIntent)
            assertEquals(source, result.source)
            assertEquals(0L, result.delayMs)
            assertTrue(result.conditions().isEmpty())
        }
    }

    @Test fun screenDelayValidationShowsDialogAndDoesNotFinishOrReturnAResult() {
        listOf(TriggerSource.UNLOCK, TriggerSource.LOCK).forEach { source ->
            val config = launch(MatchRuleActivity::class.java,
                Intent(app, MatchRuleActivity::class.java).putExtra(MatchRuleActivity.EXTRA_SOURCE, source.name)).get()
            val delay = tagged<EditText>(config, "condition_delay")
            listOf("", "-1", "600001", "9223372036854775808", "abc", "1.5").forEach { invalid ->
                delay.setText(invalid)
                click(config, "确定")
                assertValidationDialog(config, "延迟")
                assertNull(shadowOf(config).resultIntent)
            }
            delay.setText("600000")
            click(config, "确定")
            assertEquals(Activity.RESULT_OK, shadowOf(config).resultCode)
            assertEquals(600000L, matchResult(shadowOf(config).resultIntent).delayMs)
        }
    }

    @Test fun localActionSheetCancelAndEachLocalConfigCancelLeaveNoRows() {
        val parent = parent()
        click(parent, "+ 添加结果动作")
        val sheet = requireNotNull(ShadowDialog.getLatestDialog())
        click(requireNotNull(sheet.window).decorView, "取消")
        assertFalse(sheet.isShowing)
        assertNull(shadowOf(parent).nextStartedActivityForResult)
        assertRows(parent, 0, 0)
        listOf(ActionType.RING, ActionType.VIBRATE, ActionType.FLASH).forEach { type ->
            val request = beginAction(parent, type)
            assertRows(parent, 0, 0)
            val config = launch(ActionConfigActivity::class.java, request.intent).get()
            field(config, "延迟毫秒").setText("57")
            field(config, "重复次数").setText("3")
            click(config, "返回")
            assertTrue(config.isFinishing)
            assertEquals(Activity.RESULT_CANCELED, shadowOf(config).resultCode)
            deliver(parent, request, config)
            assertRows(parent, 0, 0)
            assertTrue(draft(parent).actions.isEmpty())
            assertEquals(0, stateOf(parent).getInt("pending_request"))
        }
        assertTrue(store.rules().isEmpty())
    }

    @Test fun localActionsAreAddedOnlyAfterSaveAndEachResultIsConsumedOnce() {
        val parent = parent()
        val expected = mutableListOf<RuleAction>()
        listOf(ActionType.RING, ActionType.VIBRATE, ActionType.FLASH).forEach { type ->
            val request = beginAction(parent, type)
            val config = launch(ActionConfigActivity::class.java, request.intent).get()
            field(config, "延迟毫秒").setText("42")
            field(config, "持续时间秒").setText("9")
            field(config, "重复次数").setText("2")
            assertEquals(expected, draft(parent).actions)
            click(config, "确定")
            assertEquals(Activity.RESULT_OK, shadowOf(config).resultCode)
            deliver(parent, request, config)
            expected += RuleAction(type, delayMs = 42, durationSeconds = 9, repeatCount = 2)
            assertEquals(expected, draft(parent).actions)
            deliver(parent, request, config)
            assertEquals(expected, draft(parent).actions)
            assertRows(parent, 0, expected.size)
        }
    }

    @Test fun localActionValidationFailureAndBackDoNotCommit() {
        val parent = parent()
        val request = beginAction(parent, ActionType.VIBRATE)
        val config = launch(ActionConfigActivity::class.java, request.intent).get()
        field(config, "重复次数").setText("1000")
        click(config, "确定")
        assertValidationDialog(config, "次数")
        assertRows(parent, 0, 0)
        click(config, "返回")
        deliver(parent, request, config)
        assertRows(parent, 0, 0)
    }

    @Test fun emptyForwardPickerCancelDoesNotCreateAnEmptyAction() {
        val parent = parent()
        val request = beginAction(parent, ActionType.FORWARD)
        assertRows(parent, 0, 0)
        val picker = launch(ForwardPickerActivity::class.java, request.intent).get()
        assertTrue(labels(root(picker)).contains("暂无转发规则"))
        click(picker, "返回")
        deliver(parent, request, picker)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(picker).resultCode)
        assertRows(parent, 0, 0)
        assertTrue(draft(parent).actions.isEmpty())
    }

    @Test fun forwardPickerReturnsOneExistingTargetOnlyAfterConfirm() {
        val targets = seedForwards()
        val parent = parent()
        val request = beginAction(parent, ActionType.FORWARD)
        val picker = launch(ForwardPickerActivity::class.java, request.intent).get()
        assertTrue(labels(root(picker)).contains("确定"))
        assertRows(parent, 0, 0)
        click(picker, targets[1].name)
        assertFalse(picker.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(picker).resultCode)
        assertSelected(picker, targets[1])
        assertRows(parent, 0, 0)
        click(picker, "确定")
        assertTrue(picker.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(picker).resultCode)
        assertEquals(listOf(targets[1].id), shadowOf(picker).resultIntent
            .getStringArrayListExtra(ForwardPickerActivity.EXTRA_SELECTED))
        deliver(parent, request, picker)
        deliver(parent, request, picker)
        assertRows(parent, 0, 1)
        assertEquals(listOf(RuleAction(ActionType.FORWARD, forwardIds = listOf(targets[1].id))), draft(parent).actions)
    }

    @Test fun selectingForwardThenEitherBackDoesNotAddAnAction() {
        val targets = seedForwards()
        listOf(false, true).forEach { systemBack ->
            val parent = parent()
            val request = beginAction(parent, ActionType.FORWARD)
            val picker = launch(ForwardPickerActivity::class.java, request.intent).get()
            click(picker, targets[0].name)
            click(picker, targets[1].name)
            assertSelected(picker, targets[1])
            assertFalse(picker.isFinishing)
            assertRows(parent, 0, 0)
            if (systemBack) picker.onBackPressed() else click(picker, "返回")
            assertEquals(Activity.RESULT_CANCELED, shadowOf(picker).resultCode)
            deliver(parent, request, picker)
            assertRows(parent, 0, 0)
            assertTrue(draft(parent).actions.isEmpty())
        }
    }

    @Test fun forwardConfirmRequiresAnExistingSelectedTarget() {
        val targets = seedForwards()
        val parent = parent()
        val request = beginAction(parent, ActionType.FORWARD)
        val picker = launch(ForwardPickerActivity::class.java, request.intent).get()
        fun confirmButton() = views(root(picker)).filterIsInstance<TextView>().single { it.text.toString() == "确定" }
        assertFalse(confirmButton().isEnabled)
        click(picker, targets[0].name)
        assertTrue(confirmButton().isEnabled)
        assertTrue(store.deleteForward(targets[0].id))
        click(picker, "确定")
        assertValidationDialog(picker, "已被删除")
        assertFalse(confirmButton().isEnabled)
        assertRows(parent, 0, 0)
        click(picker, targets[1].name)
        click(picker, "确定")
        deliver(parent, request, picker)
        assertEquals(listOf(targets[1].id), draft(parent).actions.single().forwardIds)
    }

    @Test fun matchEditCancelPreservesOriginalAndSaveReplacesByIdNotAppend() {
        val original = fixture()
        val parent = parent(original)
        var request = editMatch(parent, 0)
        var config = launch(MatchRuleActivity::class.java, request.intent).get()
        tagged<EditText>(config, "condition_BODY").setText("discard this")
        click(config, "返回")
        deliver(parent, request, config)
        assertEquals(original.matchRules, draft(parent).matchRules)
        request = editMatch(parent, 0)
        config = launch(MatchRuleActivity::class.java, request.intent).get()
        assertEquals("original", tagged<EditText>(config, "condition_BODY").text.toString())
        tagged<EditText>(config, "condition_BODY").setText("replacement")
        click(config, "确定")
        deliver(parent, request, config)
        deliver(parent, request, config)
        val expected = original.matchRules.toMutableList().apply {
            this[0] = this[0].copy(body = MatchCondition(MatchMode.CONTAINS, "replacement"))
        }
        assertEquals(expected, draft(parent).matchRules)
        assertRows(parent, 2, 1)
        assertEquals(original, store.rule(original.id))
        click(parent, "保存")
        assertEquals(expected, store.rule(original.id)!!.matchRules)
        assertEquals(1, store.rules().size)
    }

    @Test fun actionEditCancelPreservesOriginalAndSaveReplacesNotDuplicates() {
        val original = fixture()
        val parent = parent(original)
        var request = editAction(parent, 0, ActionConfigActivity::class.java)
        var config = launch(ActionConfigActivity::class.java, request.intent).get()
        field(config, "延迟毫秒").setText("99")
        selectMusic(config, "content://test.music/discarded")
        click(config, "返回")
        deliver(parent, request, config)
        assertEquals(original.actions, draft(parent).actions)
        request = editAction(parent, 0, ActionConfigActivity::class.java)
        config = launch(ActionConfigActivity::class.java, request.intent).get()
        assertEquals("217", field(config, "延迟毫秒").text.toString())
        assertTrue(labels(root(config)).any { it.contains(original.actions.single().soundUri) })
        field(config, "延迟毫秒").setText("93")
        field(config, "持续时间秒").setText("11")
        field(config, "重复次数").setText("6")
        assertTrue(views(root(config)).filterIsInstance<Switch>().isEmpty())
        assertTrue(tagged<View>(config, "operation_START").isSelected)
        selectMusic(config, "content://test.music/committed")
        click(config, "确定")
        deliver(parent, request, config)
        deliver(parent, request, config)
        val expected = RuleAction(ActionType.RING, operation = ActionOperation.START, delayMs = 93,
            durationSeconds = 11, repeatCount = 6, soundUri = "content://test.music/committed")
        assertEquals(listOf(expected), draft(parent).actions)
        assertRows(parent, 2, 1)
        assertEquals(original, store.rule(original.id))
        click(parent, "保存")
        assertEquals(listOf(expected), store.rule(original.id)!!.actions)
    }

    @Test fun forwardEditCancelPreservesSelectionAndSaveReplacesOnlyThatAction() {
        val targets = seedForwards()
        val original = fixture().let { it.copy(actions = it.actions +
            RuleAction(ActionType.FORWARD, enabled = false, forwardIds = listOf(targets[0].id))) }
        val parent = parent(original)
        var request = editAction(parent, 1, ForwardPickerActivity::class.java)
        var picker = launch(ForwardPickerActivity::class.java, request.intent).get()
        assertSelected(picker, targets[0])
        click(picker, targets[1].name)
        assertFalse(picker.isFinishing)
        click(picker, "返回")
        deliver(parent, request, picker)
        assertEquals(original.actions, draft(parent).actions)
        request = editAction(parent, 1, ForwardPickerActivity::class.java)
        picker = launch(ForwardPickerActivity::class.java, request.intent).get()
        click(picker, targets[1].name)
        click(picker, "确定")
        deliver(parent, request, picker)
        deliver(parent, request, picker)
        val expected = listOf(original.actions[0], original.actions[1].copy(enabled = true, forwardIds = listOf(targets[1].id)))
        assertEquals(expected, draft(parent).actions)
        assertRows(parent, 2, 2)
        assertEquals(original, store.rule(original.id))
    }

    @Test fun parentRecreationPreservesPendingNewMatchAndUnsavedDraft() {
        val original = fixture()
        var parent = parent(original)
        changeMetadata(parent)
        val request = beginMatch(parent)
        var types = launch(TriggerTypeActivity::class.java, request.intent).get()
        val configRequest = chooseType(types, TriggerSource.LOCK)
        var config = launch(MatchRuleActivity::class.java, configRequest.intent).get()
        tagged<EditText>(config, "condition_delay").setText("275")
        assertPending(parent, request, -1)
        val before = draft(parent)
        parent = rebuild(parent)
        types = rebuild(types)
        config = rebuild(config)
        assertEquals(before, draft(parent))
        assertMetadata(parent)
        assertPending(parent, request, -1)
        assertEquals("275", tagged<EditText>(config, "condition_delay").text.toString())
        assertRows(parent, 2, 1)
        click(config, "确定")
        types.onActivityResult(configRequest.requestCode, shadowOf(config).resultCode, shadowOf(config).resultIntent)
        deliver(parent, request, types)
        deliver(parent, request, types)
        val saved = draft(parent)
        assertEquals(original.matchRules, saved.matchRules.take(2))
        assertEquals(TriggerSource.LOCK, saved.matchRules.last().source)
        assertEquals(275L, saved.matchRules.last().delayMs)
        assertEquals(original.actions, saved.actions)
        assertRows(parent, 3, 1)
        click(parent, "保存")
        assertEquals(saved, store.rule(original.id))
    }

    @Test fun recreationPreservesPendingMatchEditIdAndSmsFieldsAndModes() {
        val original = fixture()
        var parent = parent(original)
        changeMetadata(parent)
        val request = editMatch(parent, 0)
        var config = launch(MatchRuleActivity::class.java, request.intent).get()
        tagged<EditText>(config, "condition_LOCAL_NUMBER").setText("13900139000")
        tagged<EditText>(config, "condition_SENDER_NUMBER").setText("95533")
        tagged<EditText>(config, "condition_BODY").setText("balance.*")
        assertTrue(tagged<View>(config, "mode_LOCAL_NUMBER_EQUALS").performClick())
        assertTrue(tagged<View>(config, "mode_BODY_REGEX").performClick())
        assertPending(parent, request, 0, matchId = original.matchRules[0].id)
        parent = rebuild(parent)
        config = rebuild(config)
        assertMetadata(parent)
        assertPending(parent, request, 0, matchId = original.matchRules[0].id)
        assertEquals(original.matchRules, draft(parent).matchRules)
        assertEquals("13900139000", tagged<EditText>(config, "condition_LOCAL_NUMBER").text.toString())
        assertEquals("95533", tagged<EditText>(config, "condition_SENDER_NUMBER").text.toString())
        assertEquals("balance.*", tagged<EditText>(config, "condition_BODY").text.toString())
        assertTrue(tagged<View>(config, "mode_LOCAL_NUMBER_EQUALS").isSelected)
        assertTrue(tagged<View>(config, "mode_BODY_REGEX").isSelected)
        click(config, "确定")
        deliver(parent, request, config)
        deliver(parent, request, config)
        assertEquals(listOf(original.matchRules[0].copy(
            localNumber = MatchCondition(MatchMode.EQUALS, "13900139000"),
            senderNumber = MatchCondition(MatchMode.CONTAINS, "95533"),
            body = MatchCondition(MatchMode.REGEX, "balance.*")), original.matchRules[1]), draft(parent).matchRules)
        assertRows(parent, 2, 1)
    }

    @Test fun recreationPreservesPendingActionForBothAddAndEditIncludingMusic() {
        listOf(false, true).forEach { editing ->
            val original = fixture("action-recreate-" + editing)
            var parent = parent(original)
            changeMetadata(parent)
            val index = if (editing) 0 else -1
            val request = if (editing) editAction(parent, index, ActionConfigActivity::class.java)
                else beginAction(parent, ActionType.RING)
            var config = launch(ActionConfigActivity::class.java, request.intent).get()
            field(config, "延迟毫秒").setText("345")
            field(config, "持续时间秒").setText("19")
            field(config, "重复次数").setText("7")
            assertTrue(views(root(config)).filterIsInstance<Switch>().isEmpty())
            assertTrue(tagged<View>(config, "operation_START").isSelected)
            selectMusic(config, "content://test.music/recreated")
            val before = draft(parent)
            assertPending(parent, request, index, type = ActionType.RING)
            parent = rebuild(parent)
            config = rebuild(config)
            assertEquals(before, draft(parent))
            assertMetadata(parent)
            assertPending(parent, request, index, type = ActionType.RING)
            assertEquals("345", field(config, "延迟毫秒").text.toString())
            assertEquals("19", field(config, "持续时间秒").text.toString())
            assertEquals("7", field(config, "重复次数").text.toString())
            assertTrue(views(root(config)).filterIsInstance<Switch>().isEmpty())
            assertTrue(tagged<View>(config, "operation_START").isSelected)
            assertTrue(labels(root(config)).any { it.contains("content://test.music/recreated") })
            click(config, "确定")
            deliver(parent, request, config)
            deliver(parent, request, config)
            val result = RuleAction(ActionType.RING, operation = ActionOperation.START, delayMs = 345,
                durationSeconds = 19, repeatCount = 7, soundUri = "content://test.music/recreated")
            val expected = if (editing) listOf(result) else original.actions + result
            assertEquals(expected, draft(parent).actions)
            assertRows(parent, 2, expected.size)
            click(parent, "保存")
            assertEquals(expected, store.rule(original.id)!!.actions)
            assertEquals("旋转后的草稿名称", store.rule(original.id)!!.name)
            assertEquals("尚未提交的描述", store.rule(original.id)!!.description)
        }
    }

    @Test fun recreationPreservesPendingForwardForBothAddAndEditAndSelectedTarget() {
        val targets = seedForwards()
        listOf(false, true).forEach { editing ->
            val original = fixture("forward-recreate-" + editing).let { it.copy(actions = it.actions +
                RuleAction(ActionType.FORWARD, enabled = false, forwardIds = listOf(targets[0].id))) }
            var parent = parent(original)
            changeMetadata(parent)
            val index = if (editing) 1 else -1
            val request = if (editing) editAction(parent, index, ForwardPickerActivity::class.java)
                else beginAction(parent, ActionType.FORWARD)
            var picker = launch(ForwardPickerActivity::class.java, request.intent).get()
            val before = draft(parent)
            assertPending(parent, request, index, type = ActionType.FORWARD)
            parent = rebuild(parent)
            picker = rebuild(picker)
            assertEquals(before, draft(parent))
            assertMetadata(parent)
            assertPending(parent, request, index, type = ActionType.FORWARD)
            if (editing) assertSelected(picker, targets[0])
            else assertFalse(views(root(picker)).any { it.contentDescription?.toString()?.endsWith("，已选择") == true })
            click(picker, targets[1].name)
            picker = rebuild(picker)
            assertSelected(picker, targets[1])
            assertFalse(picker.isFinishing)
            assertEquals(before, draft(parent))
            click(picker, "确定")
            deliver(parent, request, picker)
            deliver(parent, request, picker)
            val expected = if (editing) listOf(original.actions[0],
                original.actions[1].copy(enabled = true, forwardIds = listOf(targets[1].id)))
            else original.actions + RuleAction(ActionType.FORWARD, forwardIds = listOf(targets[1].id))
            assertEquals(expected, draft(parent).actions)
            assertRows(parent, 2, expected.size)
            click(parent, "保存")
            assertEquals(expected, store.rule(original.id)!!.actions)
        }
    }

    @Test fun nullMalformedAndInvalidMatchResultsConsumePendingWithoutAppending() {
        val parent = parent()
        val valid = matchIntent(MatchRule(id = "valid", body = MatchCondition(MatchMode.CONTAINS, "ok")))
        val invalidResults = listOf(null, Intent(),
            Intent().putExtra(MatchRuleActivity.EXTRA_MATCH, "not-json"),
            Intent().putExtra(MatchRuleActivity.EXTRA_MATCH, "[]"),
            Intent().putExtra(MatchRuleActivity.EXTRA_MATCH, "{\"source\":\"UNKNOWN\"}"),
            matchIntent(MatchRule(source = TriggerSource.HTTP)),
            matchIntent(MatchRule(source = TriggerSource.LOCK, delayMs = -1)),
            matchIntent(MatchRule(body = MatchCondition(MatchMode.REGEX, "["))))
        invalidResults.forEach { data ->
            val request = beginMatch(parent)
            parent.onActivityResult(request.requestCode, Activity.RESULT_OK, data)
            assertRows(parent, 0, 0)
            assertTrue(draft(parent).matchRules.isEmpty())
            assertEquals(0, stateOf(parent).getInt("pending_request"))
            dismissDialog()
            parent.onActivityResult(request.requestCode, Activity.RESULT_OK, valid)
            assertRows(parent, 0, 0)
        }
    }

    @Test fun nullMalformedWrongTypeAndOutOfRangeActionResultsNeverAppend() {
        val parent = parent()
        val valid = actionIntent(ActionType.RING, parent)
        val invalidResults = listOf(null, Intent(),
            Intent().putExtra(ActionConfigActivity.EXTRA_TYPE, "UNKNOWN"),
            actionIntent(ActionType.VIBRATE, parent), actionIntent(ActionType.FORWARD, parent),
            actionIntent(ActionType.RING, parent).putExtra(ActionConfigActivity.EXTRA_DELAY, -1L),
            actionIntent(ActionType.RING, parent).putExtra(ActionConfigActivity.EXTRA_DELAY, 600001L),
            actionIntent(ActionType.RING, parent).putExtra(ActionConfigActivity.EXTRA_DURATION, 3601),
            actionIntent(ActionType.RING, parent).putExtra(ActionConfigActivity.EXTRA_REPEAT, 1000))
        invalidResults.forEach { data ->
            val request = beginAction(parent, ActionType.RING)
            parent.onActivityResult(request.requestCode, Activity.RESULT_OK, data)
            assertRows(parent, 0, 0)
            assertTrue(draft(parent).actions.isEmpty())
            assertEquals(0, stateOf(parent).getInt("pending_request"))
            dismissDialog()
            parent.onActivityResult(request.requestCode, Activity.RESULT_OK, valid)
            assertRows(parent, 0, 0)
        }
    }

    @Test fun nullEmptyMissingAndMultipleForwardResultsNeverAppend() {
        val targets = seedForwards()
        val parent = parent()
        val invalidResults = listOf(null, Intent(), forwardIntent(), forwardIntent("missing"),
            forwardIntent(""), forwardIntent(targets[0].id, targets[1].id))
        invalidResults.forEach { data ->
            val request = beginAction(parent, ActionType.FORWARD)
            parent.onActivityResult(request.requestCode, Activity.RESULT_OK, data)
            assertRows(parent, 0, 0)
            assertTrue(draft(parent).actions.isEmpty())
            assertEquals(0, stateOf(parent).getInt("pending_request"))
            parent.onActivityResult(request.requestCode, Activity.RESULT_OK, forwardIntent(targets[0].id))
            assertRows(parent, 0, 0)
        }
        // A target deleted while the picker is open is not a valid selection anymore.
        val request = beginAction(parent, ActionType.FORWARD)
        assertTrue(store.deleteForward(targets[0].id))
        parent.onActivityResult(request.requestCode, Activity.RESULT_OK, forwardIntent(targets[0].id))
        assertRows(parent, 0, 0)
    }

    @Test fun canceledAndUnrelatedResultsDoNotCommitOrConsumeAnotherRequest() {
        val parent = parent()
        val request = beginAction(parent, ActionType.RING)
        val valid = actionIntent(ActionType.RING, parent)
        parent.onActivityResult(request.requestCode + 99, Activity.RESULT_OK, valid)
        assertRows(parent, 0, 0)
        assertPending(parent, request, -1, type = ActionType.RING)
        parent.onActivityResult(request.requestCode, Activity.RESULT_CANCELED, valid)
        assertRows(parent, 0, 0)
        parent.onActivityResult(request.requestCode, Activity.RESULT_OK, valid)
        assertRows(parent, 0, 0)
        val next = beginAction(parent, ActionType.RING)
        parent.onActivityResult(next.requestCode, Activity.RESULT_OK, valid)
        assertRows(parent, 0, 1)
    }

    private fun fixture(id: String = "rule-existing") = AlarmRule(
        id = id, name = "已有规则", description = "已有描述",
        matchRules = listOf(
            MatchRule(id = "sms-original", body = MatchCondition(MatchMode.CONTAINS, "original")),
            MatchRule(id = "screen-original", source = TriggerSource.UNLOCK, delayMs = 81)),
        actions = listOf(RuleAction(ActionType.RING, delayMs = 217, durationSeconds = 13,
            repeatCount = 4, soundUri = "content://test.music/original")),
    )

    private fun seedForwards(): List<ForwardTarget> = listOf(
        ForwardTarget(id = "forward-one", name = "目标甲", type = ForwardType.HTTP, url = "https://one.example.test/hook"),
        ForwardTarget(id = "forward-two", name = "目标乙", type = ForwardType.HTTP, url = "https://two.example.test/hook"),
    ).also { targets -> targets.forEach { assertTrue(store.saveForward(it)) } }

    private fun parent(original: AlarmRule? = null): RuleEditActivity {
        val intent = Intent(app, RuleEditActivity::class.java)
        if (original != null) {
            assertTrue(store.saveRule(original))
            intent.putExtra(RuleEditActivity.EXTRA_ID, original.id)
        }
        return launch(RuleEditActivity::class.java, intent).get()
    }

    private fun <T : Activity> launch(type: Class<T>, intent: Intent = Intent(app, type),
        state: Bundle? = null): ActivityController<T> {
        val controller = Robolectric.buildActivity(type, intent)
        controllers += controller
        if (state == null) controller.setup() else controller.setup(state)
        idle()
        return controller
    }

    private fun stateOf(activity: Activity) = Bundle().also { state ->
        controllers.single { it.get() === activity }.saveInstanceState(state)
    }

    private fun <T : Activity> rebuild(activity: T): T {
        val controller = controllers.single { it.get() === activity }
        val state = Bundle()
        controller.pause().saveInstanceState(state).stop().destroy()
        controllers.remove(controller)
        // A fresh instance (not the original intent alone) must reconstruct its pending draft.
        return launch(activity.javaClass, Intent(activity.intent), state).get()
    }

    private fun draft(parent: RuleEditActivity): AlarmRule = requireNotNull(
        RuleCodec.decodeRule(JSONObject(requireNotNull(stateOf(parent).getString("editor_draft_json")))))

    private fun beginMatch(parent: RuleEditActivity): IntentForResult {
        click(parent, "+ 添加触发条件")
        return nextRequest(parent, TriggerTypeActivity::class.java)
    }

    private fun chooseType(types: TriggerTypeActivity, source: TriggerSource): IntentForResult {
        assertTrue(tagged<View>(types, "type_" + source.name).performClick())
        idle()
        return nextRequest(types, MatchRuleActivity::class.java).also {
            assertEquals(source.name, it.intent.getStringExtra(MatchRuleActivity.EXTRA_SOURCE))
        }
    }

    private fun beginAction(parent: RuleEditActivity, type: ActionType): IntentForResult {
        click(parent, "+ 添加结果动作")
        val sheet = requireNotNull(ShadowDialog.getLatestDialog())
        assertTrue(sheet.isShowing)
        click(requireNotNull(sheet.window).decorView, type.label)
        assertFalse(sheet.isShowing)
        val target = if (type == ActionType.FORWARD) ForwardPickerActivity::class.java else ActionConfigActivity::class.java
        return nextRequest(parent, target).also {
            if (type.isLocal) {
                assertEquals(type.name, it.intent.getStringExtra(ActionConfigActivity.EXTRA_TYPE))
                assertEquals(draft(parent).id, it.intent.getStringExtra(ActionConfigActivity.EXTRA_OWNER_RULE_ID))
                assertEquals(ActionOperation.START.name, it.intent.getStringExtra(ActionConfigActivity.EXTRA_OPERATION))
                assertEquals(StopScope.THIS_RULE.name, it.intent.getStringExtra(ActionConfigActivity.EXTRA_STOP_SCOPE))
                assertEquals("", it.intent.getStringExtra(ActionConfigActivity.EXTRA_TARGET_RULE_ID))
            }
        }
    }

    private fun editMatch(parent: RuleEditActivity, index: Int): IntentForResult {
        assertTrue(rows(parent, "trigger_rows", "trigger_row")[index].performClick())
        return nextRequest(parent, MatchRuleActivity::class.java)
    }

    private fun editAction(parent: RuleEditActivity, index: Int, target: Class<out Activity>): IntentForResult {
        assertTrue(rows(parent, "action_rows", "action_row")[index].performClick())
        return nextRequest(parent, target)
    }

    private fun nextRequest(activity: Activity, target: Class<out Activity>): IntentForResult =
        requireNotNull(shadowOf(activity).nextStartedActivityForResult).also {
            assertEquals(target.name, it.intent.component?.className)
        }

    private fun deliver(parent: RuleEditActivity, request: IntentForResult, child: Activity) {
        parent.onActivityResult(request.requestCode, shadowOf(child).resultCode, shadowOf(child).resultIntent)
        idle()
    }

    private fun assertPending(parent: RuleEditActivity, request: IntentForResult, index: Int,
        matchId: String? = null, type: ActionType? = null) {
        val state = stateOf(parent)
        assertEquals(request.requestCode, state.getInt("pending_request"))
        assertEquals(index, state.getInt("pending_index"))
        assertEquals(matchId, state.getString("pending_match_id"))
        assertEquals(type?.name, state.getString("pending_action_type"))
    }

    private fun changeMetadata(parent: RuleEditActivity) {
        field(parent, "规则名称").setText("旋转后的草稿名称")
        field(parent, "规则描述").setText("尚未提交的描述")
    }

    private fun assertMetadata(parent: RuleEditActivity) {
        assertEquals("旋转后的草稿名称", field(parent, "规则名称").text.toString())
        assertEquals("尚未提交的描述", field(parent, "规则描述").text.toString())
    }

    private fun selectMusic(config: ActionConfigActivity, uri: String) {
        click(config, "选择本地音乐")
        val request = requireNotNull(shadowOf(config).nextStartedActivityForResult)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
        assertEquals("audio/*", request.intent.type)
        shadowOf(config).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(Uri.parse(uri)))
        idle()
    }

    private fun assertSelected(picker: ForwardPickerActivity, target: ForwardTarget) {
        assertTrue(views(root(picker)).any { it.contentDescription?.toString() == target.displayName + "，已选择" })
    }

    private fun assertValidationDialog(activity: Activity, messageFragment: String) {
        assertFalse(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        val dialog = requireNotNull(ShadowDialog.getLatestDialog()) as AlertDialog
        assertTrue(dialog.isShowing)
        assertTrue(labels(requireNotNull(dialog.window).decorView).any { it.contains(messageFragment) })
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick())
        idle()
    }

    private fun dismissDialog() {
        ShadowDialog.getLatestDialog()?.let { if (it.isShowing) it.dismiss() }
        idle()
    }

    private fun matchIntent(rule: MatchRule) = Intent()
        .putExtra(MatchRuleActivity.EXTRA_MATCH, RuleCodec.encodeMatchRule(rule).toString())

    private fun matchResult(data: Intent): MatchRule = requireNotNull(
        RuleCodec.decodeMatchRule(JSONObject(requireNotNull(data.getStringExtra(MatchRuleActivity.EXTRA_MATCH)))))

    private fun actionIntent(type: ActionType, parent: RuleEditActivity) = Intent()
        .putExtra(ActionConfigActivity.EXTRA_OWNER_RULE_ID, draft(parent).id)
        .putExtra(ActionConfigActivity.EXTRA_TYPE, type.name)
        .putExtra(ActionConfigActivity.EXTRA_ENABLED, true)
        .putExtra(ActionConfigActivity.EXTRA_OPERATION, ActionOperation.START.name)
        .putExtra(ActionConfigActivity.EXTRA_STOP_SCOPE, StopScope.THIS_RULE.name)
        .putExtra(ActionConfigActivity.EXTRA_TARGET_RULE_ID, "")
        .putExtra(ActionConfigActivity.EXTRA_DELAY, 0L)
        .putExtra(ActionConfigActivity.EXTRA_DURATION, 0)
        .putExtra(ActionConfigActivity.EXTRA_REPEAT, 0)
        .putExtra(ActionConfigActivity.EXTRA_SOUND, "")

    private fun forwardIntent(vararg ids: String) = Intent()
        .putStringArrayListExtra(ForwardPickerActivity.EXTRA_SELECTED, ArrayList(ids.toList()))

    private fun assertRows(parent: RuleEditActivity, matches: Int, actions: Int) {
        assertEquals("trigger rows", matches, rows(parent, "trigger_rows", "trigger_row").size)
        assertEquals("action rows", actions, rows(parent, "action_rows", "action_row").size)
    }

    private fun rows(activity: Activity, host: String, tag: String) =
        views(tagged<ViewGroup>(activity, host)).filter { it.tag == tag }

    private fun <T : View> tagged(activity: Activity, tag: String): T =
        requireNotNull(root(activity).findViewWithTag<T>(tag)) { "Missing view tag: " + tag }

    private fun field(activity: Activity, description: String): EditText =
        views(root(activity)).filterIsInstance<EditText>().single { it.contentDescription?.toString() == description }

    private fun click(activity: Activity, text: String) = click(root(activity), text)

    private fun click(root: View, text: String) {
        var target: View = views(root).filterIsInstance<TextView>().firstOrNull { it.text.toString() == text }
            ?: error("Missing click label: " + text)
        // Sheet / picker labels live inside clickable rows; click that real row, not a private callback.
        while (!target.isClickable) target = target.parent as? View ?: error("No clickable ancestor: " + text)
        assertTrue("Disabled click target: " + text, target.isEnabled)
        assertTrue("Click not handled: " + text, target.performClick())
        idle()
    }

    private fun labels(view: View) = views(view).filterIsInstance<TextView>().map { it.text.toString() }
    private fun root(activity: Activity): View = activity.window.decorView
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private companion object {
        val MatchFieldTags = listOf("condition_LOCAL_NUMBER", "condition_SENDER_NUMBER", "condition_BODY")
    }
}
