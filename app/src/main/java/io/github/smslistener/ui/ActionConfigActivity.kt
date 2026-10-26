package io.github.smslistener.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.smslistener.core.ActionOperation
import io.github.smslistener.core.ActionType
import io.github.smslistener.core.RuleAction
import io.github.smslistener.core.StopScope
import io.github.smslistener.data.AppStore
import io.github.smslistener.ui.UiKit.Companion.ACCENT
import io.github.smslistener.ui.UiKit.Companion.BACKGROUND
import io.github.smslistener.ui.UiKit.Companion.INK
import io.github.smslistener.ui.UiKit.Companion.MUTED
import io.github.smslistener.ui.UiKit.Companion.SECONDARY
import io.github.smslistener.ui.UiKit.Companion.SOFT

/** Draft-only editor. Only explicit confirmation returns a new action to its owning rule. */
class ActionConfigActivity : Activity() {
    private lateinit var ui: UiKit
    private lateinit var store: AppStore
    private lateinit var delayField: EditText
    private lateinit var durationField: EditText
    private lateinit var repeatField: EditText
    private lateinit var startFields: LinearLayout
    private lateinit var stopFields: LinearLayout
    private lateinit var targetPicker: Button
    private lateinit var targetLabel: TextView
    private lateinit var soundLabel: TextView
    private var soundCard: LinearLayout? = null
    private val operationButtons = mutableMapOf<ActionOperation, Button>()
    private val scopeButtons = mutableMapOf<StopScope, Button>()
    private var type = ActionType.VIBRATE
    private var operation = ActionOperation.START
    private var stopScope = StopScope.THIS_RULE
    private var targetRuleId = ""
    private var ownerRuleId = ""
    private var legacyDisabled = false
    private var soundUri = ""
    private var soundPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        store = AppStore(this)
        type = ActionType.entries.firstOrNull { it.name ==
            (savedInstanceState?.getString(KEY_TYPE) ?: intent.getStringExtra(EXTRA_TYPE)) } ?: ActionType.VIBRATE
        val operationName = savedInstanceState?.getString(KEY_OPERATION)
            ?: if (intent.hasExtra(EXTRA_OPERATION)) intent.getStringExtra(EXTRA_OPERATION) else ActionOperation.START.name
        val scopeName = savedInstanceState?.getString(KEY_SCOPE)
            ?: if (intent.hasExtra(EXTRA_STOP_SCOPE)) intent.getStringExtra(EXTRA_STOP_SCOPE) else StopScope.THIS_RULE.name
        operation = ActionOperation.entries.firstOrNull { it.name == operationName } ?: run { finish(); return }
        stopScope = StopScope.entries.firstOrNull { it.name == scopeName } ?: run { finish(); return }
        if (!type.isLocal) { finish(); return }
        ownerRuleId = intent.getStringExtra(EXTRA_OWNER_RULE_ID).orEmpty()
        targetRuleId = savedInstanceState?.getString(KEY_TARGET) ?: intent.getStringExtra(EXTRA_TARGET_RULE_ID).orEmpty()
        legacyDisabled = savedInstanceState?.getBoolean(KEY_LEGACY_DISABLED) ?: !intent.getBooleanExtra(EXTRA_ENABLED, true)
        soundUri = savedInstanceState?.getString(KEY_SOUND) ?: intent.getStringExtra(EXTRA_SOUND).orEmpty()
        soundPending = savedInstanceState?.getBoolean(KEY_SOUND_PENDING) ?: false
        if (operation == ActionOperation.START) { stopScope = StopScope.THIS_RULE; targetRuleId = "" }
        buildScreen()
        delayField.setText(savedInstanceState?.getString(KEY_DELAY) ?: intent.getLongExtra(EXTRA_DELAY, 0L).toString())
        durationField.setText(savedInstanceState?.getString(KEY_DURATION) ?: intent.getIntExtra(EXTRA_DURATION, 0).toString())
        repeatField.setText(savedInstanceState?.getString(KEY_REPEAT) ?: intent.getIntExtra(EXTRA_REPEAT, 0).toString())
        renderOperation()
        renderSound()
    }

    override fun onResume() {
        super.onResume()
        if (::targetLabel.isInitialized) renderTarget()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::delayField.isInitialized) {
            outState.putString(KEY_TYPE, type.name)
            outState.putString(KEY_OPERATION, operation.name)
            outState.putString(KEY_SCOPE, stopScope.name)
            outState.putString(KEY_TARGET, targetRuleId)
            outState.putBoolean(KEY_LEGACY_DISABLED, legacyDisabled)
            outState.putString(KEY_DELAY, delayField.text.toString())
            outState.putString(KEY_DURATION, durationField.text.toString())
            outState.putString(KEY_REPEAT, repeatField.text.toString())
            outState.putString(KEY_SOUND, soundUri)
            outState.putBoolean(KEY_SOUND_PENDING, soundPending)
        }
        super.onSaveInstanceState(outState)
    }

    private fun buildScreen() = with(ui) {
        val root = column().apply { setBackgroundColor(BACKGROUND) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        val bar = row().apply { setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(Color.WHITE) }
        bar.addView(plainButton("返回", INK) { finish() })
        bar.addView(label(type.label, 17, INK, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(plainButton("确定") { save() })
        root.addView(bar)
        divider(root)
        val content = column().apply { setPadding(dp(18), dp(16), dp(18), dp(28)) }
        root.addView(ScrollView(this@ActionConfigActivity).apply {
            isFillViewport = true; clipToPadding = false; addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        val main = card(content)
        section(main, type.label, "选择触发时启动或停止此类本机动作。停止不是禁用配置。")
        if (legacyDisabled) {
            main.addView(label("此前已禁用，确认后按所选操作执行", 13, UiKit.AMBER).apply { tag = "legacy_disabled_hint" })
            gap(main, 12)
        }
        val operations = row()
        ActionOperation.entries.forEach { value ->
            val choice = button(value.label, SOFT, ACCENT) {
                operation = value
                if (value == ActionOperation.START) { stopScope = StopScope.THIS_RULE; targetRuleId = "" }
                renderOperation()
            }.apply {
                tag = "operation_" + value.name
                contentDescription = value.label
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) }
            }
            operationButtons[value] = choice
            operations.addView(choice)
        }
        main.addView(operations)
        gap(main, 16)
        main.addView(label("延迟执行（毫秒）", 13, INK, true)); gap(main, 6)
        main.addView(label("0 表示收到触发后立刻执行；启动和停止均支持延迟。", 11, MUTED)); gap(main, 8)
        delayField = field("0", false, "延迟毫秒").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        main.addView(delayField); gap(main, 16)

        startFields = column().apply { tag = "start_parameters" }
        main.addView(startFields)
        startFields.addView(label("持续时间（秒）", 13, INK, true)); gap(startFields, 6)
        startFields.addView(label("0 表示一直持续到手动停止", 11, MUTED)); gap(startFields, 8)
        durationField = field("0", false, "持续时间秒").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        startFields.addView(durationField); gap(startFields, 16)
        startFields.addView(label("重复次数", 13, INK, true)); gap(startFields, 6)
        startFields.addView(label("0 表示不限次数，按持续时间停止", 11, MUTED)); gap(startFields, 8)
        repeatField = field("0", false, "重复次数").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        startFields.addView(repeatField)

        stopFields = column().apply { tag = "stop_parameters" }
        main.addView(stopFields)
        stopFields.addView(label("停止范围", 13, INK, true)); gap(stopFields, 8)
        StopScope.entries.forEach { value ->
            val choice = button(value.label, SOFT, ACCENT) {
                stopScope = value
                if (value != StopScope.SPECIFIC_RULE) targetRuleId = ""
                renderOperation()
            }.apply { tag = "scope_" + value.name; contentDescription = value.label }
            scopeButtons[value] = choice
            stopFields.addView(choice); gap(stopFields, 8)
        }
        targetLabel = label("", 12, SECONDARY).apply { tag = "target_rule_label" }
        stopFields.addView(targetLabel); gap(stopFields, 8)
        targetPicker = button("选择目标规则", SOFT, ACCENT, ::chooseTarget).apply { tag = "target_rule_picker" }
        stopFields.addView(targetPicker)
        gap(stopFields, 10)
        stopFields.addView(label("仅停止所选规则的" + type.label + "，其他动作类型保持运行。", 11, MUTED))

        if (type == ActionType.RING) {
            val sound = card(content).also { soundCard = it }
            section(sound, "提示音", "默认使用应用内置的双音提示音。")
            soundLabel = label("", 13, SECONDARY)
            sound.addView(soundLabel); gap(sound, 12)
            sound.addView(button("选择本地音乐", SOFT, ACCENT) { pickSound() })
            gap(sound, 10)
            sound.addView(button("恢复默认提示音", BACKGROUND, SECONDARY) { soundUri = ""; renderSound() })
        }
        setContentView(root)
    }

    private fun renderOperation() {
        operationButtons.forEach { (value, button) -> styleChoice(button, value == operation) }
        scopeButtons.forEach { (value, button) -> styleChoice(button, value == stopScope) }
        startFields.visibility = if (operation == ActionOperation.START) View.VISIBLE else View.GONE
        stopFields.visibility = if (operation == ActionOperation.STOP) View.VISIBLE else View.GONE
        soundCard?.visibility = if (operation == ActionOperation.START) View.VISIBLE else View.GONE
        renderTarget()
    }

    private fun styleChoice(button: Button, selected: Boolean) {
        button.isSelected = selected
        button.background = ui.ripple(if (selected) SOFT else BACKGROUND, 12)
        button.setTextColor(if (selected) ACCENT else SECONDARY)
    }

    private fun renderTarget() {
        val specific = stopScope == StopScope.SPECIFIC_RULE
        targetPicker.visibility = if (specific) View.VISIBLE else View.GONE
        targetLabel.text = when (stopScope) {
            StopScope.THIS_RULE -> "仅本规则触发的" + type.label
            StopScope.ALL_RULES -> "停止所有规则的" + type.label
            StopScope.SPECIFIC_RULE -> {
                val target = store.rule(targetRuleId)
                when {
                    targetRuleId.isBlank() -> "尚未选择目标规则"
                    target != null -> target.name + "\nID：" + target.id
                    targetRuleId == ownerRuleId -> "本规则（正在编辑）\nID：" + ownerRuleId
                    else -> "目标规则已删除，请重新选择\nID：" + targetRuleId
                }
            }
        }
    }

    private fun chooseTarget() {
        val candidates = store.rules().map { it.id to it.name }.toMutableList()
        if (ownerRuleId.isNotBlank() && candidates.none { it.first == ownerRuleId }) {
            candidates += ownerRuleId to "本规则（正在编辑）"
        }
        if (candidates.isEmpty()) { showError("暂无可选规则，请先创建目标规则"); return }
        val labels = candidates.map { it.second + "\nID：" + it.first }.toTypedArray()
        AlertDialog.Builder(this).setTitle("选择要停止的规则")
            .setSingleChoiceItems(labels, candidates.indexOfFirst { it.first == targetRuleId }) { dialog, index ->
                targetRuleId = candidates[index].first
                renderTarget()
                dialog.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    private fun renderSound() {
        if (!::soundLabel.isInitialized) return
        soundLabel.text = if (soundUri.isBlank()) "当前：内置提示音" else "当前：自定义音乐\n" + soundUri.take(80)
        soundLabel.setTextColor(if (soundUri.isBlank()) SECONDARY else ACCENT)
    }

    private fun pickSound() {
        if (soundPending || operation != ActionOperation.START) return
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        soundPending = true
        runCatching { startActivityForResult(picker, REQUEST_SOUND) }.onFailure {
            soundPending = false
            showError("没有可用的文件选择器")
        }
    }

    @Deprecated("Platform callback; AndroidX is not a dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SOUND || !soundPending) return
        soundPending = false
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        soundUri = uri.toString()
        renderSound()
    }

    private fun save() {
        val stop = operation == ActionOperation.STOP
        val draft = RuleAction(
            type = type, enabled = true,
            delayMs = delayField.text.toString().trim().toLongOrNull() ?: -1L,
            durationSeconds = if (stop) 0 else durationField.text.toString().trim().toIntOrNull() ?: -1,
            repeatCount = if (stop) 0 else repeatField.text.toString().trim().toIntOrNull() ?: -1,
            soundUri = if (stop) "" else soundUri,
            operation = operation,
            stopScope = if (stop) stopScope else StopScope.THIS_RULE,
            targetRuleId = if (stop && stopScope == StopScope.SPECIFIC_RULE) targetRuleId else "",
        )
        draft.validationError()?.let { showError(it); return }
        if (stop && draft.stopScope == StopScope.SPECIFIC_RULE && draft.targetRuleId != ownerRuleId && store.rule(draft.targetRuleId) == null) {
            showError("目标规则已删除，请重新选择")
            renderTarget()
            return
        }
        setResult(RESULT_OK, Intent()
            .putExtra(EXTRA_TYPE, draft.type.name).putExtra(EXTRA_ENABLED, true)
            .putExtra(EXTRA_DELAY, draft.delayMs).putExtra(EXTRA_DURATION, draft.durationSeconds)
            .putExtra(EXTRA_REPEAT, draft.repeatCount).putExtra(EXTRA_SOUND, draft.soundUri)
            .putExtra(EXTRA_OPERATION, draft.operation.name).putExtra(EXTRA_STOP_SCOPE, draft.stopScope.name)
            .putExtra(EXTRA_TARGET_RULE_ID, draft.targetRuleId).putExtra(EXTRA_OWNER_RULE_ID, ownerRuleId))
        finish()
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this).setTitle("还差一点").setMessage(message).setPositiveButton("去修改", null).show()
    }

    companion object {
        const val EXTRA_TYPE = "action_type"
        const val EXTRA_ENABLED = "action_enabled"
        const val EXTRA_DELAY = "action_delay"
        const val EXTRA_DURATION = "action_duration"
        const val EXTRA_REPEAT = "action_repeat"
        const val EXTRA_SOUND = "action_sound"
        const val EXTRA_OPERATION = "action_operation"
        const val EXTRA_STOP_SCOPE = "action_stop_scope"
        const val EXTRA_TARGET_RULE_ID = "action_target_rule_id"
        const val EXTRA_OWNER_RULE_ID = "owner_rule_id"
        private const val REQUEST_SOUND = 3001
        private const val KEY_TYPE = "draft_type"
        private const val KEY_OPERATION = "draft_operation"
        private const val KEY_SCOPE = "draft_stop_scope"
        private const val KEY_TARGET = "draft_target_rule"
        private const val KEY_LEGACY_DISABLED = "draft_legacy_disabled"
        private const val KEY_DELAY = "draft_delay"
        private const val KEY_DURATION = "draft_duration"
        private const val KEY_REPEAT = "draft_repeat"
        private const val KEY_SOUND = "draft_sound"
        private const val KEY_SOUND_PENDING = "draft_sound_pending"
    }
}
