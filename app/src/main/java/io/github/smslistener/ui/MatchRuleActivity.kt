package io.github.smslistener.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import io.github.smslistener.core.MatchCondition
import io.github.smslistener.core.MatchField
import io.github.smslistener.core.MatchMode
import io.github.smslistener.core.MatchRule
import io.github.smslistener.core.TriggerSource
import io.github.smslistener.data.RuleCodec
import org.json.JSONObject
import java.util.UUID

/** Draft-only configuration; saving returns one validated condition to the caller. */
class MatchRuleActivity : Activity() {
    private lateinit var ui: UiKit
    private val editors = mutableMapOf<MatchField, EditText>()
    private val modeButtons = mutableMapOf<MatchField, List<Button>>()
    private val chosenModes = mutableMapOf<MatchField, MatchMode>()
    private var delayField: EditText? = null
    private var ruleId = UUID.randomUUID().toString()
    private var source = TriggerSource.SMS
    private var editing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        val original = intent.getStringExtra(EXTRA_MATCH)?.let {
            runCatching { RuleCodec.decodeMatchRule(JSONObject(it)) }.getOrNull()
        }
        editing = original != null
        ruleId = savedInstanceState?.getString(KEY_ID) ?: original?.id ?: ruleId
        val sourceName = savedInstanceState?.getString(KEY_SOURCE) ?: original?.source?.name
            ?: intent.getStringExtra(EXTRA_SOURCE) ?: TriggerSource.SMS.name
        source = TriggerSource.entries.firstOrNull { it.name == sourceName } ?: run { finish(); return }
        if (source == TriggerSource.HTTP) { finish(); return }
        val content = EditorPage.create(this, (if (editing) "编辑" else "新建") + source.label + "条件", "确定", ::confirm)
        val card = ui.card(content)
        if (source == TriggerSource.SMS) {
            ui.section(card, "短信", "留空表示不限制该项；填写的项目需要同时满足；三项都留空表示命中所有短信。")
            MatchField.entries.forEachIndexed { index, field ->
                val old = when (field) {
                    MatchField.LOCAL_NUMBER -> original?.localNumber
                    MatchField.SENDER_NUMBER -> original?.senderNumber
                    MatchField.BODY -> original?.body
                }
                chosenModes[field] = savedInstanceState?.getString("mode_" + field.name)?.let { name ->
                    MatchMode.entries.firstOrNull { it.name == name }
                } ?: old?.mode ?: MatchMode.CONTAINS
                if (index > 0) { ui.gap(card, 18); ui.divider(card); ui.gap(card, 18) }
                card.addView(ui.label(field.label, 14, UiKit.INK, true)); ui.gap(card, 6)
                card.addView(ui.label(field.hint, 12, UiKit.SECONDARY)); ui.gap(card, 10)
                val row = ui.row().apply { background = ui.rounded(UiKit.BACKGROUND, 10) }
                modeButtons[field] = MatchMode.entries.map { mode ->
                    ui.button(mode.label, UiKit.BACKGROUND, UiKit.SECONDARY) {
                        chosenModes[field] = mode; renderModes()
                    }.apply {
                        tag = "mode_" + field.name + "_" + mode.name
                        textSize = 13f
                        row.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                }
                card.addView(row); ui.gap(card, 10)
                val input = ui.field("留空表示不限制", field == MatchField.BODY, field.label).apply {
                    tag = "condition_" + field.name
                    setText(savedInstanceState?.getString("value_" + field.name) ?: old?.value.orEmpty())
                }
                editors[field] = input
                card.addView(input)
            }
            renderModes()
        } else {
            ui.section(card, source.label, if (source == TriggerSource.LOCK)
                "检测屏幕关闭事件；熄屏不一定代表系统已经完成密码锁定。" else "检测用户解锁事件，不会主动解锁手机。")
            card.addView(ui.label("多少毫秒后触发", 14, UiKit.INK, true)); ui.gap(card, 8)
            card.addView(ui.label("0 表示立即，最大 600000 毫秒。", 12, UiKit.SECONDARY)); ui.gap(card, 10)
            delayField = ui.field("0", false, "触发延迟毫秒").apply {
                tag = "condition_delay"
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(savedInstanceState?.getString(KEY_DELAY) ?: (original?.delayMs ?: 0L).toString())
                card.addView(this)
            }
        }
        ui.gap(content, 8)
        content.addView(ui.label("点击右上角“确定”后加入规则；返回会放弃本次修改。", 12, UiKit.MUTED))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_ID, ruleId)
        outState.putString(KEY_SOURCE, source.name)
        outState.putString(KEY_DELAY, delayField?.text?.toString())
        editors.forEach { (field, input) ->
            outState.putString("value_" + field.name, input.text.toString())
            outState.putString("mode_" + field.name, chosenModes[field]?.name)
        }
        super.onSaveInstanceState(outState)
    }

    private fun renderModes() {
        modeButtons.forEach { (field, buttons) ->
            buttons.forEachIndexed { index, button ->
                val selected = MatchMode.entries[index] == chosenModes[field]
                button.isSelected = selected
                button.background = ui.ripple(if (selected) UiKit.SOFT else UiKit.BACKGROUND, 9)
                button.setTextColor(if (selected) UiKit.ACCENT else UiKit.SECONDARY)
            }
        }
    }

    private fun condition(field: MatchField): MatchCondition? {
        val value = editors[field]?.text?.toString()?.trim().orEmpty()
        return if (value.isEmpty()) null else MatchCondition(chosenModes[field] ?: MatchMode.CONTAINS, value)
    }

    private fun confirm() {
        val delay = if (source == TriggerSource.SMS) 0L else delayField?.text?.toString()?.trim()?.toLongOrNull()
        if (delay == null) { error("请输入有效的延迟毫秒数"); return }
        val updated = MatchRule(id = ruleId, source = source, delayMs = delay,
            localNumber = condition(MatchField.LOCAL_NUMBER),
            senderNumber = condition(MatchField.SENDER_NUMBER), body = condition(MatchField.BODY))
        updated.validationError()?.let { error(it); return }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_MATCH, RuleCodec.encodeMatchRule(updated).toString()))
        finish()
    }

    private fun error(message: String) {
        AlertDialog.Builder(this).setTitle("请检查触发条件").setMessage(message).setPositiveButton("知道了", null).show()
    }

    companion object {
        const val EXTRA_MATCH = "match_json"
        const val EXTRA_SOURCE = "trigger_source"
        private const val KEY_ID = "draft_id"
        private const val KEY_SOURCE = "draft_source"
        private const val KEY_DELAY = "draft_delay"
    }
}
