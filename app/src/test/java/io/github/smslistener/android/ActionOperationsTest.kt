package io.github.smslistener.android

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import io.github.smslistener.core.*
import io.github.smslistener.data.AppStore
import io.github.smslistener.data.RuleCodec
import io.github.smslistener.ui.ActionConfigActivity
import io.github.smslistener.ui.RuleEditActivity
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

/** Exercise actual views and public activity results, including both sides of a recreated transaction. */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class ActionOperationsTest {
    private lateinit var app: Application
    private lateinit var store: AppStore
    private val controllers = mutableListOf<ActivityController<out Activity>>()
    private val localTypes = listOf(ActionType.RING, ActionType.VIBRATE, ActionType.FLASH)

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

    @Test fun everyLocalChannelDefaultsToStartAndSwitchingOperationsOnlyChangesRelevantControls() {
        for (type in localTypes) {
            val action = RuleAction(type, delayMs = 12, durationSeconds = 34, repeatCount = 5,
                soundUri = if (type == ActionType.RING) "content://fixture/tone" else "")
            val config = launch(ActionConfigActivity::class.java, configIntent(action)).get()
            assertTrue(views(root(config)).filterIsInstance<Switch>().isEmpty())
            assertSelected(config, "operation_START")
            assertFalse(tagged<View>(config, "operation_STOP").isSelected)
            assertTrue(visible(field(config, "持续时间秒")))
            assertTrue(visible(field(config, "重复次数")))
            if (type == ActionType.RING) assertTrue(visible(text(config, "选择本地音乐")))
            tap(config, "operation_STOP")
            assertSelected(config, "operation_STOP")
            assertSelected(config, "scope_THIS_RULE")
            StopScope.entries.forEach { assertTrue(visible(tagged<View>(config, "scope_" + it.name))) }
            assertFalse(visible(field(config, "持续时间秒")))
            assertFalse(visible(field(config, "重复次数")))
            if (type == ActionType.RING) assertFalse(visible(text(config, "选择本地音乐")))
            assertTrue(visible(field(config, "延迟毫秒")))
            tap(config, "operation_START")
            assertSelected(config, "operation_START")
            assertEquals("34", field(config, "持续时间秒").text.toString())
            assertEquals("5", field(config, "重复次数").text.toString())
            assertTrue(visible(field(config, "持续时间秒")))
            assertFalse(visible(tagged<View>(config, "scope_ALL_RULES")))
            click(config, "确定")
            assertEquals(action, result(config))
        }
    }

    @Test fun aNewLocalEditorWithoutOperationExtrasStartsWithStartSelected() {
        for (type in localTypes) {
            val intent = Intent(app, ActionConfigActivity::class.java)
                .putExtra(ActionConfigActivity.EXTRA_TYPE, type.name)
                .putExtra(ActionConfigActivity.EXTRA_OWNER_RULE_ID, "new-owner")
            val config = launch(ActionConfigActivity::class.java, intent).get()
            assertSelected(config, "operation_START")
            assertTrue(views(root(config)).filterIsInstance<Switch>().isEmpty())
            click(config, "确定")
            assertEquals(RuleAction(type), result(config))
        }
    }

    @Test fun stopNormalizesHiddenInvalidStartParametersButKeepsDelayForEveryChannel() {
        for (type in localTypes) {
            val config = launch(ActionConfigActivity::class.java,
                configIntent(RuleAction(type, durationSeconds = 17, repeatCount = 9,
                    soundUri = "content://fixture/discard-on-stop"))).get()
            field(config, "持续时间秒").setText("not-a-number")
            field(config, "重复次数").setText("")
            field(config, "延迟毫秒").setText("456")
            tap(config, "operation_STOP")
            tap(config, "scope_ALL_RULES")
            click(config, "确定")
            assertEquals(RuleAction(type, delayMs = 456, operation = ActionOperation.STOP,
                stopScope = StopScope.ALL_RULES), result(config))
            assertEquals("owner", shadowOf(config).resultIntent.getStringExtra(ActionConfigActivity.EXTRA_OWNER_RULE_ID))
        }
    }

    @Test fun switchingBackToStartClearsHiddenScopeAndTargetOnSaveWithoutLosingStartParameters() {
        val target = seed("target")
        val action = RuleAction(ActionType.RING, durationSeconds = 8, repeatCount = 2,
            soundUri = "content://fixture/retained")
        val config = launch(ActionConfigActivity::class.java, configIntent(action)).get()
        tap(config, "operation_STOP")
        tap(config, "scope_SPECIFIC_RULE")
        chooseTarget(config, target)
        tap(config, "operation_START")
        click(config, "确定")
        assertEquals(action, result(config))
        assertEquals(StopScope.THIS_RULE, result(config).stopScope)
        assertEquals("", result(config).targetRuleId)
    }

    @Test fun changingSpecificScopeToThisOrAllClearsTheHiddenTargetAtCommit() {
        val target = seed("target")
        for (scope in listOf(StopScope.THIS_RULE, StopScope.ALL_RULES)) {
            val action = RuleAction(ActionType.FLASH, operation = ActionOperation.STOP,
                stopScope = StopScope.SPECIFIC_RULE, targetRuleId = target.id, delayMs = 45)
            val config = launch(ActionConfigActivity::class.java, configIntent(action)).get()
            assertSelected(config, "scope_SPECIFIC_RULE")
            tap(config, "scope_" + scope.name)
            assertFalse(visible(tagged<View>(config, "target_rule_picker")))
            click(config, "确定")
            assertEquals(action.copy(stopScope = scope, targetRuleId = ""), result(config))
        }
    }

    @Test fun stopDelayIsStillValidatedAndNeverSilentlyClamped() {
        val config = launch(ActionConfigActivity::class.java,
            configIntent(RuleAction(ActionType.VIBRATE, operation = ActionOperation.STOP))).get()
        for (bad in listOf("-1", "600001", "", "999999999999999999999")) {
            field(config, "延迟毫秒").setText(bad)
            click(config, "确定")
            validation(config, "延迟")
        }
        field(config, "延迟毫秒").setText("600000")
        click(config, "确定")
        assertEquals(600_000L, result(config).delayMs)
        assertEquals(ActionOperation.STOP, result(config).operation)
    }

    @Test fun specificTargetIsRequiredAndDuplicateDisplayNamesUseStableRuleIds() {
        val first = seed("first", "同名规则")
        val second = seed("second", "同名规则")
        val config = launch(ActionConfigActivity::class.java,
            configIntent(RuleAction(ActionType.RING, operation = ActionOperation.STOP,
                stopScope = StopScope.SPECIFIC_RULE))).get()
        click(config, "确定")
        validation(config, "规则")
        chooseTarget(config, second)
        val label = tagged<TextView>(config, "target_rule_label").text.toString()
        assertTrue(label.contains(second.id))
        assertTrue(label.contains(second.name))
        assertFalse(label.contains(first.id))
        click(config, "确定")
        assertEquals(second.id, result(config).targetRuleId)
        assertEquals(StopScope.SPECIFIC_RULE, result(config).stopScope)
    }

    @Test fun cancelingTargetPickerKeepsThePreviouslySelectedTarget() {
        val target = seed("target")
        seed("other")
        val action = RuleAction(ActionType.RING, operation = ActionOperation.STOP,
            stopScope = StopScope.SPECIFIC_RULE, targetRuleId = target.id)
        val config = launch(ActionConfigActivity::class.java, configIntent(action)).get()
        tap(config, "target_rule_picker")
        val dialog = requireNotNull(ShadowDialog.getLatestDialog()) as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertFalse(dialog.isShowing)
        assertTrue(tagged<TextView>(config, "target_rule_label").text.toString().contains(target.id))
        click(config, "确定")
        assertEquals(action, result(config))
    }

    @Test fun unsavedOwningRuleKeepsItsStableIdAcrossRotationAndCanBeSelectedExplicitly() {
        var parent = parent()
        val owner = draft(parent).id
        assertTrue(owner.isNotBlank())
        assertNull(store.rule(owner))
        val request = beginAction(parent, ActionType.VIBRATE)
        assertEquals(owner, request.intent.getStringExtra(ActionConfigActivity.EXTRA_OWNER_RULE_ID))
        var config = launch(ActionConfigActivity::class.java, request.intent).get()
        tap(config, "operation_STOP")
        tap(config, "scope_SPECIFIC_RULE")
        chooseTarget(config, fixture(owner).copy(name = "本规则（正在编辑）"))
        parent = rebuild(parent)
        config = rebuild(config)
        assertEquals(owner, draft(parent).id)
        assertTrue(tagged<TextView>(config, "target_rule_label").text.toString().contains(owner))
        click(config, "确定")
        deliver(parent, request, config)
        assertEquals(listOf(RuleAction(ActionType.VIBRATE, operation = ActionOperation.STOP,
            stopScope = StopScope.SPECIFIC_RULE, targetRuleId = owner)), draft(parent).actions)
        assertNull(store.rule(owner))
    }

    @Test fun deletedTargetSurvivesRecreationAsMissingAndNeverFallsBackToAllRules() {
        val target = seed("deleted-target")
        var config = launch(ActionConfigActivity::class.java,
            configIntent(RuleAction(ActionType.FLASH, operation = ActionOperation.STOP,
                stopScope = StopScope.SPECIFIC_RULE, targetRuleId = target.id))).get()
        assertTrue(store.deleteRule(target.id))
        config = rebuild(config)
        assertSelected(config, "operation_STOP")
        assertSelected(config, "scope_SPECIFIC_RULE")
        assertTrue(tagged<TextView>(config, "target_rule_label").text.toString().contains("已删除"))
        click(config, "确定")
        validation(config, "规则")
        val replacement = seed("replacement")
        chooseTarget(config, replacement)
        click(config, "确定")
        assertEquals(replacement.id, result(config).targetRuleId)
        assertEquals(StopScope.SPECIFIC_RULE, result(config).stopScope)
    }

    @Test fun legacyDisabledCancelAndSystemBackPreserveTheOriginalWithoutInventingStop() {
        for (type in localTypes) {
            for (systemBack in listOf(false, true)) {
                val original = fixture("disabled-$type-$systemBack", RuleAction(type, enabled = false, delayMs = 123))
                val parent = parent(original)
                val request = editAction(parent)
                var config = launch(ActionConfigActivity::class.java, request.intent).get()
                assertLegacyNotice(config)
                assertSelected(config, "operation_START")
                tap(config, "operation_STOP")
                tap(config, "scope_ALL_RULES")
                config = rebuild(config)
                assertLegacyNotice(config)
                assertSelected(config, "operation_STOP")
                if (systemBack) config.onBackPressed() else click(config, "返回")
                assertEquals(Activity.RESULT_CANCELED, shadowOf(config).resultCode)
                deliver(parent, request, config)
                assertEquals(original, draft(parent))
                assertEquals(original, store.rule(original.id))
                assertEquals(ActionOperation.START, draft(parent).actions.single().operation)
                assertFalse(draft(parent).actions.single().enabled)
            }
        }
    }

    @Test fun legacyDisabledConfirmationActivatesExactlyTheChosenOperationAndWaitsForParentSave() {
        for (operation in ActionOperation.entries) {
            val original = fixture("confirm-$operation", RuleAction(ActionType.RING, enabled = false,
                durationSeconds = 8, repeatCount = 2, soundUri = "content://fixture/old"))
            val parent = parent(original)
            val request = editAction(parent)
            val config = launch(ActionConfigActivity::class.java, request.intent).get()
            assertLegacyNotice(config)
            tap(config, "operation_" + operation.name)
            assertEquals(original, draft(parent))
            click(config, "确定")
            val expected = if (operation == ActionOperation.START) original.actions.single().copy(enabled = true)
                else RuleAction(ActionType.RING, operation = ActionOperation.STOP)
            assertEquals(expected, result(config))
            assertEquals(original, draft(parent))
            deliver(parent, request, config)
            deliver(parent, request, config)
            assertEquals(listOf(expected), draft(parent).actions)
            assertEquals(original, store.rule(original.id))
            click(parent, "保存")
            assertEquals(listOf(expected), store.rule(original.id)!!.actions)
        }
    }

    @Test fun everyStopScopeAndOwnerSurviveParentChildRotationForAddAndEditAndCommitOnlyOnce() {
        val target = seed("target")
        for (editing in listOf(false, true)) {
            for (scope in StopScope.entries) {
                val original = fixture("rotate-$editing-$scope")
                var parent = parent(original)
                field(parent, "规则名称").setText("未保存名称")
                val request = if (editing) editAction(parent) else beginAction(parent, ActionType.RING)
                assertEquals(original.id, request.intent.getStringExtra(ActionConfigActivity.EXTRA_OWNER_RULE_ID))
                var config = launch(ActionConfigActivity::class.java, request.intent).get()
                tap(config, "operation_STOP")
                tap(config, "scope_" + scope.name)
                if (scope == StopScope.SPECIFIC_RULE) chooseTarget(config, target)
                field(config, "延迟毫秒").setText("333")
                val before = draft(parent)
                parent = rebuild(parent)
                config = rebuild(config)
                assertEquals(before, draft(parent))
                assertEquals(request.requestCode, stateOf(parent).getInt("pending_request"))
                assertEquals(if (editing) 0 else -1, stateOf(parent).getInt("pending_index"))
                assertSelected(config, "operation_STOP")
                assertSelected(config, "scope_" + scope.name)
                assertEquals("333", field(config, "延迟毫秒").text.toString())
                if (scope == StopScope.SPECIFIC_RULE) {
                    assertTrue(tagged<TextView>(config, "target_rule_label").text.toString().contains(target.id))
                }
                click(config, "确定")
                assertEquals(original.id, shadowOf(config).resultIntent.getStringExtra(ActionConfigActivity.EXTRA_OWNER_RULE_ID))
                val expected = RuleAction(ActionType.RING, operation = ActionOperation.STOP, stopScope = scope,
                    targetRuleId = if (scope == StopScope.SPECIFIC_RULE) target.id else "", delayMs = 333)
                assertEquals(expected, result(config))
                parent.onActivityResult(request.requestCode + 100, Activity.RESULT_OK, shadowOf(config).resultIntent)
                assertEquals(before, draft(parent))
                assertEquals(request.requestCode, stateOf(parent).getInt("pending_request"))
                deliver(parent, request, config)
                deliver(parent, request, config)
                val expectedActions = if (editing) listOf(expected) else original.actions + expected
                assertEquals(expectedActions, draft(parent).actions)
                assertEquals(0, stateOf(parent).getInt("pending_request"))
                assertEquals(original, store.rule(original.id))
                click(parent, "保存")
                assertEquals(expectedActions, store.rule(original.id)!!.actions)
                assertEquals("未保存名称", store.rule(original.id)!!.name)
            }
        }
    }

    @Test fun existingStopAllMetadataIsSentToChildAndCancelDoesNotModifyParent() {
        val target = seed("target")
        val stop = RuleAction(ActionType.VIBRATE, operation = ActionOperation.STOP,
            stopScope = StopScope.SPECIFIC_RULE, targetRuleId = target.id, delayMs = 97)
        val original = fixture("owner", stop)
        val parent = parent(original)
        val request = editAction(parent)
        assertEquals(original.id, request.intent.getStringExtra(ActionConfigActivity.EXTRA_OWNER_RULE_ID))
        assertEquals("STOP", request.intent.getStringExtra(ActionConfigActivity.EXTRA_OPERATION))
        assertEquals("SPECIFIC_RULE", request.intent.getStringExtra(ActionConfigActivity.EXTRA_STOP_SCOPE))
        assertEquals(target.id, request.intent.getStringExtra(ActionConfigActivity.EXTRA_TARGET_RULE_ID))
        val config = launch(ActionConfigActivity::class.java, request.intent).get()
        assertSelected(config, "operation_STOP")
        assertSelected(config, "scope_SPECIFIC_RULE")
        assertEquals("97", field(config, "延迟毫秒").text.toString())
        tap(config, "operation_START")
        click(config, "返回")
        deliver(parent, request, config)
        assertEquals(original, draft(parent))
        assertEquals(original, store.rule(original.id))
    }

    @Test fun malformedOperationScopeTargetAndOwnerResultsNeverAppendOrConsumeTwice() {
        val parent = parent()
        val owner = draft(parent).id
        val valid = configIntent(RuleAction(ActionType.RING), owner)
        val malformed = listOf(
            Intent(valid).putExtra(ActionConfigActivity.EXTRA_OPERATION, "UNKNOWN"),
            Intent(valid).putExtra(ActionConfigActivity.EXTRA_OPERATION, 1),
            Intent(valid).apply { removeExtra(ActionConfigActivity.EXTRA_OPERATION) },
            Intent(valid).putExtra(ActionConfigActivity.EXTRA_STOP_SCOPE, "UNKNOWN"),
            Intent(valid).putExtra(ActionConfigActivity.EXTRA_STOP_SCOPE, true),
            Intent(valid).apply { removeExtra(ActionConfigActivity.EXTRA_STOP_SCOPE) },
            Intent(valid).putExtra(ActionConfigActivity.EXTRA_TARGET_RULE_ID, 1),
            Intent(valid).apply { removeExtra(ActionConfigActivity.EXTRA_TARGET_RULE_ID) },
            Intent(valid).putExtra(ActionConfigActivity.EXTRA_OWNER_RULE_ID, "someone-else"),
            Intent(valid).apply { removeExtra(ActionConfigActivity.EXTRA_OWNER_RULE_ID) },
            configIntent(RuleAction(ActionType.RING, operation = ActionOperation.STOP,
                stopScope = StopScope.SPECIFIC_RULE, targetRuleId = "deleted"), owner),
            configIntent(RuleAction(ActionType.RING, operation = ActionOperation.STOP,
                stopScope = StopScope.SPECIFIC_RULE), owner),
        )
        for (data in malformed) {
            val request = beginAction(parent, ActionType.RING)
            parent.onActivityResult(request.requestCode, Activity.RESULT_OK, data)
            assertTrue(draft(parent).actions.isEmpty())
            assertEquals(0, stateOf(parent).getInt("pending_request"))
            dismissDialog()
            parent.onActivityResult(request.requestCode, Activity.RESULT_OK, valid)
            assertTrue(draft(parent).actions.isEmpty())
        }
        val request = beginAction(parent, ActionType.RING)
        parent.onActivityResult(request.requestCode, Activity.RESULT_OK, valid)
        assertEquals(listOf(RuleAction(ActionType.RING)), draft(parent).actions)
    }

    @Test fun targetDeletedAfterChildConfirmationDoesNotReplaceTheOriginalAction() {
        val target = seed("target")
        val original = fixture("owner")
        val parent = parent(original)
        val request = editAction(parent)
        val config = launch(ActionConfigActivity::class.java, request.intent).get()
        tap(config, "operation_STOP")
        tap(config, "scope_SPECIFIC_RULE")
        chooseTarget(config, target)
        click(config, "确定")
        assertEquals(target.id, result(config).targetRuleId)
        assertTrue(store.deleteRule(target.id))
        deliver(parent, request, config)
        assertEquals(original, draft(parent))
        assertEquals(original, store.rule(original.id))
        assertEquals(0, stateOf(parent).getInt("pending_request"))
    }

    @Test fun targetDeletedAfterDraftCommitBlocksParentSaveWithoutChangingStoredOwner() {
        val target = seed("target")
        val original = fixture("owner")
        val parent = parent(original)
        val request = editAction(parent)
        val config = launch(ActionConfigActivity::class.java, request.intent).get()
        tap(config, "operation_STOP")
        tap(config, "scope_SPECIFIC_RULE")
        chooseTarget(config, target)
        click(config, "确定")
        deliver(parent, request, config)
        assertEquals(target.id, draft(parent).actions.single().targetRuleId)
        assertTrue(store.deleteRule(target.id))
        click(parent, "保存")
        validation(parent, "规则")
        assertEquals(original, store.rule(original.id))
        assertEquals(StopScope.SPECIFIC_RULE, draft(parent).actions.single().stopScope)
        assertEquals(target.id, draft(parent).actions.single().targetRuleId)
    }

    private fun fixture(id: String, action: RuleAction = RuleAction(ActionType.RING)) = AlarmRule(
        id = id, name = "规则-$id", matchRules = listOf(MatchRule(id = "$id-group",
            body = MatchCondition(MatchMode.CONTAINS, "fixture"))), actions = listOf(action))

    private fun seed(id: String, name: String = "目标-$id") = fixture(id).copy(name = name).also {
        assertTrue(store.saveRule(it))
    }

    private fun parent(original: AlarmRule? = null): RuleEditActivity {
        val intent = Intent(app, RuleEditActivity::class.java)
        if (original != null) {
            assertTrue(store.saveRule(original))
            intent.putExtra(RuleEditActivity.EXTRA_ID, original.id)
        }
        return launch(RuleEditActivity::class.java, intent).get()
    }

    private fun configIntent(action: RuleAction, owner: String = "owner") = Intent(app, ActionConfigActivity::class.java)
        .putExtra(ActionConfigActivity.EXTRA_TYPE, action.type.name)
        .putExtra(ActionConfigActivity.EXTRA_ENABLED, action.enabled)
        .putExtra(ActionConfigActivity.EXTRA_DELAY, action.delayMs)
        .putExtra(ActionConfigActivity.EXTRA_DURATION, action.durationSeconds)
        .putExtra(ActionConfigActivity.EXTRA_REPEAT, action.repeatCount)
        .putExtra(ActionConfigActivity.EXTRA_SOUND, action.soundUri)
        .putExtra(ActionConfigActivity.EXTRA_OPERATION, action.operation.name)
        .putExtra(ActionConfigActivity.EXTRA_STOP_SCOPE, action.stopScope.name)
        .putExtra(ActionConfigActivity.EXTRA_TARGET_RULE_ID, action.targetRuleId)
        .putExtra(ActionConfigActivity.EXTRA_OWNER_RULE_ID, owner)

    private fun result(config: ActionConfigActivity): RuleAction {
        assertTrue(config.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(config).resultCode)
        val data = requireNotNull(shadowOf(config).resultIntent)
        for (key in listOf(ActionConfigActivity.EXTRA_TYPE, ActionConfigActivity.EXTRA_ENABLED,
            ActionConfigActivity.EXTRA_DELAY, ActionConfigActivity.EXTRA_DURATION, ActionConfigActivity.EXTRA_REPEAT,
            ActionConfigActivity.EXTRA_SOUND, ActionConfigActivity.EXTRA_OPERATION,
            ActionConfigActivity.EXTRA_STOP_SCOPE, ActionConfigActivity.EXTRA_TARGET_RULE_ID,
            ActionConfigActivity.EXTRA_OWNER_RULE_ID)) assertTrue("Missing result field: $key", data.hasExtra(key))
        return RuleAction(ActionType.valueOf(requireNotNull(data.getStringExtra(ActionConfigActivity.EXTRA_TYPE))),
            enabled = data.getBooleanExtra(ActionConfigActivity.EXTRA_ENABLED, false),
            delayMs = data.getLongExtra(ActionConfigActivity.EXTRA_DELAY, -1),
            durationSeconds = data.getIntExtra(ActionConfigActivity.EXTRA_DURATION, -1),
            repeatCount = data.getIntExtra(ActionConfigActivity.EXTRA_REPEAT, -1),
            soundUri = requireNotNull(data.getStringExtra(ActionConfigActivity.EXTRA_SOUND)),
            operation = ActionOperation.valueOf(requireNotNull(data.getStringExtra(ActionConfigActivity.EXTRA_OPERATION))),
            stopScope = StopScope.valueOf(requireNotNull(data.getStringExtra(ActionConfigActivity.EXTRA_STOP_SCOPE))),
            targetRuleId = requireNotNull(data.getStringExtra(ActionConfigActivity.EXTRA_TARGET_RULE_ID)))
    }

    private fun beginAction(parent: RuleEditActivity, type: ActionType): IntentForResult {
        click(parent, "+ 添加结果动作")
        val sheet = requireNotNull(ShadowDialog.getLatestDialog())
        click(requireNotNull(sheet.window).decorView, type.label)
        return requireNotNull(shadowOf(parent).nextStartedActivityForResult)
    }

    private fun editAction(parent: RuleEditActivity): IntentForResult {
        val rows = views(tagged<ViewGroup>(parent, "action_rows")).filter { it.tag == "action_row" }
        assertTrue(rows.single().performClick())
        return requireNotNull(shadowOf(parent).nextStartedActivityForResult)
    }

    private fun chooseTarget(config: ActionConfigActivity, target: AlarmRule) {
        tap(config, "target_rule_picker")
        val dialog = requireNotNull(ShadowDialog.getLatestDialog()) as AlertDialog
        assertTrue(dialog.isShowing)
        val list = dialog.listView
        val adapter = list.adapter
        val index = (0 until adapter.count).single {
            val label = adapter.getItem(it).toString()
            label.contains(target.id) && label.contains(target.name)
        }
        assertTrue(list.performItemClick(adapter.getView(index, null, list), index, adapter.getItemId(index)))
        idle()
        assertFalse(dialog.isShowing)
        assertTrue(tagged<TextView>(config, "target_rule_label").text.toString().contains(target.id))
    }

    private fun assertLegacyNotice(config: ActionConfigActivity) {
        assertTrue(views(root(config)).filterIsInstance<TextView>().any {
            visible(it) && it.text.toString().contains("此前已禁用") && it.text.toString().contains("确认")
        })
        assertTrue(views(root(config)).filterIsInstance<Switch>().isEmpty())
    }

    private fun validation(config: Activity, fragment: String) {
        assertFalse(config.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(config).resultCode)
        val dialog = requireNotNull(ShadowDialog.getLatestDialog()) as AlertDialog
        assertTrue(dialog.isShowing)
        assertTrue(views(requireNotNull(dialog.window).decorView).filterIsInstance<TextView>()
            .any { it.text.toString().contains(fragment) })
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
    }

    private fun deliver(parent: RuleEditActivity, request: IntentForResult, child: Activity) {
        parent.onActivityResult(request.requestCode, shadowOf(child).resultCode, shadowOf(child).resultIntent)
        idle()
    }

    private fun <T : Activity> launch(type: Class<T>, intent: Intent, state: Bundle? = null): ActivityController<T> {
        val controller = Robolectric.buildActivity(type, intent)
        controllers += controller
        if (state == null) controller.setup() else controller.setup(state)
        idle()
        return controller
    }

    private fun stateOf(activity: Activity) = Bundle().also {
        controllers.single { controller -> controller.get() === activity }.saveInstanceState(it)
    }

    private fun <T : Activity> rebuild(activity: T): T {
        val controller = controllers.single { it.get() === activity }
        val state = Bundle()
        controller.pause().saveInstanceState(state).stop().destroy()
        controllers.remove(controller)
        return launch(activity.javaClass, Intent(activity.intent), state).get()
    }

    private fun draft(parent: RuleEditActivity) = requireNotNull(RuleCodec.decodeRule(
        JSONObject(requireNotNull(stateOf(parent).getString("editor_draft_json")))))

    private fun <T : View> tagged(activity: Activity, tag: String): T =
        requireNotNull(root(activity).findViewWithTag<T>(tag)) { "Missing view tag: $tag" }

    private fun field(activity: Activity, description: String) = views(root(activity)).filterIsInstance<EditText>()
        .single { it.contentDescription?.toString() == description }

    private fun text(activity: Activity, text: String) = views(root(activity)).filterIsInstance<TextView>()
        .first { it.text.toString() == text }

    private fun assertSelected(activity: Activity, tag: String) = assertTrue(tag, tagged<View>(activity, tag).isSelected)
    private fun tap(activity: Activity, tag: String) {
        assertTrue(tag, tagged<View>(activity, tag).performClick())
        idle()
    }
    private fun click(activity: Activity, label: String) = click(root(activity), label)
    private fun click(root: View, label: String) {
        var view: View = views(root).filterIsInstance<TextView>().first { it.text.toString() == label }
        while (!view.isClickable) view = view.parent as? View ?: error("No clickable ancestor: $label")
        assertTrue(view.isEnabled)
        assertTrue(view.performClick())
        idle()
    }
    private fun visible(view: View): Boolean = view.visibility == View.VISIBLE &&
        ((view.parent as? View)?.let(::visible) ?: true)
    private fun dismissDialog() {
        ShadowDialog.getLatestDialog()?.let { if (it.isShowing) it.dismiss() }
        idle()
    }
    private fun root(activity: Activity): View = activity.window.decorView
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
