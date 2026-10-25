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
import io.github.smslistener.core.ActionType
import io.github.smslistener.core.AlarmRule
import io.github.smslistener.core.ForwardTarget
import io.github.smslistener.core.MatchCondition
import io.github.smslistener.core.MatchField
import io.github.smslistener.core.MatchMode
import io.github.smslistener.core.MatchRule
import io.github.smslistener.core.RuleAction
import io.github.smslistener.core.TriggerSource
import io.github.smslistener.data.AppStore
import io.github.smslistener.data.RuleCodec
import io.github.smslistener.ui.SentinelIcon.Kind
import io.github.smslistener.ui.UiKit.Companion.ACCENT
import io.github.smslistener.ui.UiKit.Companion.AMBER
import io.github.smslistener.ui.UiKit.Companion.BACKGROUND
import io.github.smslistener.ui.UiKit.Companion.DARK
import io.github.smslistener.ui.UiKit.Companion.INK
import io.github.smslistener.ui.UiKit.Companion.LINE
import io.github.smslistener.ui.UiKit.Companion.MUTED
import io.github.smslistener.ui.UiKit.Companion.RED
import io.github.smslistener.ui.UiKit.Companion.RED_SOFT
import io.github.smslistener.ui.UiKit.Companion.SECONDARY
import io.github.smslistener.ui.UiKit.Companion.SOFT
import java.util.UUID

/** Full-screen editor for one rule: ordered match rules plus result actions. */
class RuleEditActivity : Activity() {
    private lateinit var ui: UiKit
    private lateinit var store: AppStore
    private lateinit var nameField: EditText
    private lateinit var descriptionField: EditText
    private lateinit var matchHost: DragSortColumn
    private lateinit var actionHost: LinearLayout

    private val matchRules = mutableListOf<MatchRule>()
    private val actions = mutableListOf<RuleAction>()
    private var ruleId: String = UUID.randomUUID().toString()
    private var existing: AlarmRule? = null
    private var pendingForwardIndex = -1
    private var pendingActionIndex = -1
    private var trigger: TriggerSource = TriggerSource.SMS
    private var triggerDelayMs: Long = 0
    private lateinit var triggerDelayField: EditText
    private lateinit var matchCard: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        store = AppStore(this)
        ruleId = savedInstanceState?.getString(KEY_ID)
            ?: intent.getStringExtra(EXTRA_ID)
            ?: UUID.randomUUID().toString()
        existing = store.rule(ruleId)
        pendingForwardIndex = savedInstanceState?.getInt(KEY_FORWARD_INDEX, -1) ?: -1
        matchRules.clear()
        actions.clear()
        val saved = savedInstanceState
        if (saved != null) {
            restoreDraft(saved)
        } else existing?.let { rule ->
            matchRules += rule.matchRules
            actions += rule.actions
            trigger = rule.trigger
            triggerDelayMs = rule.triggerDelayMs
        }
        buildScreen()
        existing?.let { rule ->
            nameField.setText(rule.name)
            descriptionField.setText(rule.description)
        }
        renderMatches()
        renderActions()
        applyTriggerVisibility()
    }

    /** Screen triggers have no SMS conditions, so that card is hidden entirely. */
    private fun applyTriggerVisibility() {
        if (!::matchCard.isInitialized) return
        matchCard.visibility = if (trigger.usesConditions) android.view.View.VISIBLE else android.view.View.GONE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_ID, ruleId)
        outState.putInt(KEY_FORWARD_INDEX, pendingForwardIndex)
        outState.putStringArrayList(KEY_MATCH_ROWS, ArrayList(matchRules.map(::encodeMatch)))
        outState.putStringArrayList(KEY_ACTION_ROWS, ArrayList(actions.map(::encodeAction)))
        outState.putString(KEY_TRIGGER, trigger.name)
        outState.putString(KEY_TRIGGER_DELAY, triggerDelayMs.toString())
        super.onSaveInstanceState(outState)
    }

    private fun restoreDraft(state: Bundle) {
        state.getStringArrayList(KEY_MATCH_ROWS)?.forEach { raw -> decodeMatch(raw)?.let { matchRules += it } }
        state.getStringArrayList(KEY_ACTION_ROWS)?.forEach { raw -> decodeAction(raw)?.let { actions += it } }
        state.getString(KEY_TRIGGER)?.let { name ->
            trigger = TriggerSource.entries.firstOrNull { it.name == name } ?: TriggerSource.SMS
        }
        triggerDelayMs = state.getString(KEY_TRIGGER_DELAY)?.toLongOrNull() ?: 0L
    }

    // region draft encoding (survives rotation without touching storage)

    private fun encodeMatch(rule: MatchRule): String = listOf(
        rule.id,
        encodeCondition(rule.localNumber),
        encodeCondition(rule.senderNumber),
        encodeCondition(rule.body),
    ).joinToString(SEPARATOR)

    private fun decodeMatch(raw: String): MatchRule? {
        val parts = raw.split(SEPARATOR)
        if (parts.size != 4) return null
        return MatchRule(
            id = parts[0],
            localNumber = decodeCondition(parts[1]),
            senderNumber = decodeCondition(parts[2]),
            body = decodeCondition(parts[3]),
        )
    }

    private fun encodeCondition(condition: MatchCondition?): String =
        if (condition == null) "" else condition.mode.name + "\u0002" + condition.value

    private fun decodeCondition(raw: String): MatchCondition? {
        if (raw.isEmpty()) return null
        val parts = raw.split("\u0002", limit = 2)
        if (parts.size != 2) return null
        val mode = MatchMode.entries.firstOrNull { it.name == parts[0] } ?: return null
        return MatchCondition(mode, parts[1])
    }

    private fun encodeAction(action: RuleAction): String = listOf(
        action.type.name,
        action.enabled.toString(),
        action.delayMs.toString(),
        action.durationSeconds.toString(),
        action.repeatCount.toString(),
        action.soundUri,
        action.forwardIds.joinToString(","),
    ).joinToString(SEPARATOR)

    private fun decodeAction(raw: String): RuleAction? {
        val parts = raw.split(SEPARATOR)
        if (parts.size != 7) return null
        val type = ActionType.entries.firstOrNull { it.name == parts[0] } ?: return null
        return RuleAction(
            type = type,
            enabled = parts[1].toBooleanStrictOrNull() ?: true,
            delayMs = parts[2].toLongOrNull() ?: 0L,
            durationSeconds = parts[3].toIntOrNull() ?: 0,
            repeatCount = parts[4].toIntOrNull() ?: 0,
            soundUri = parts[5],
            forwardIds = parts[6].split(",").filter(String::isNotBlank),
        )
    }

    // endregion

    private fun buildScreen() = with(ui) {
        val root = column().apply { setBackgroundColor(BACKGROUND) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        val bar = row().apply { setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(Color.WHITE) }
        bar.addView(plainButton("返回", INK) { finish() }.apply {
            textSize = 13f; minHeight = dp(40); minimumHeight = dp(40)
            setPadding(dp(16), dp(8), dp(16), dp(8)); layoutParams = LinearLayout.LayoutParams(-2, -2)
        })
        bar.addView(label(if (existing == null) "新建规则" else "编辑规则", 17, INK, true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(plainButton("保存") { save() }.apply {
            textSize = 13f; minHeight = dp(40); minimumHeight = dp(40)
            setPadding(dp(18), dp(8), dp(18), dp(8)); layoutParams = LinearLayout.LayoutParams(-2, -2)
        })
        root.addView(bar)
        divider(root)

        val content = column().apply { setPadding(dp(18), dp(16), dp(18), dp(28)) }
        root.addView(ScrollView(this@RuleEditActivity).apply {
            isFillViewport = true; clipToPadding = false; addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        val basic = card(content)
        section(basic, "基本信息")
        basic.addView(label("名称", 13, INK, true)); gap(basic, 8)
        nameField = field("例如：服务器告警", false, "规则名称").apply { maxLines = 1 }
        basic.addView(nameField); gap(basic, 14)
        basic.addView(label("描述  ·  可选", 13, INK, true)); gap(basic, 8)
        descriptionField = field("例如：生产环境群发，需要立刻处理", false, "规则描述")
        basic.addView(descriptionField)

        val sourceCard = card(content)
        section(sourceCard, "触发方式", "决定这条规则由什么事件启动。")
        val sourceRow = row().apply { setPadding(dp(3), dp(3), dp(3), dp(3)); background = rounded(BACKGROUND, 10) }
        val sourceChips = mutableListOf<Button>()
        TriggerSource.entries.forEach { source ->
            val chip = button(source.label, BACKGROUND, SECONDARY) {
                trigger = source
                sourceChips.forEachIndexed { i, b -> styleChip(b, TriggerSource.entries[i] == trigger) }
                applyTriggerVisibility()
            }.apply {
                textSize = 12f; minHeight = dp(44); minimumHeight = dp(44)
                setPadding(dp(4), dp(9), dp(4), dp(9))
            }
            sourceChips += chip
            sourceRow.addView(chip, LinearLayout.LayoutParams(0, -2, 1f))
        }
        sourceChips.forEachIndexed { i, b -> styleChip(b, TriggerSource.entries[i] == trigger) }
        sourceCard.addView(sourceRow); gap(sourceCard, 14)
        sourceCard.addView(label("触发延迟（毫秒，0 表示立即）", 13, INK, true)); gap(sourceCard, 8)
        triggerDelayField = field("0", false, "触发延迟毫秒").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(triggerDelayMs.toString())
        }
        sourceCard.addView(triggerDelayField)

        matchCard = card(content)
        val matches = matchCard
        section(matches, "触发条件", "每条触发条件内部条件同时满足；多条触发条件之间任意一条满足即可。")
        matchHost = DragSortColumn(this@RuleEditActivity).apply {
            onMove = { from, to ->
                if (from in matchRules.indices && to in matchRules.indices) {
                    matchRules.add(to, matchRules.removeAt(from))
                    renderMatches()
                }
            }
        }
        matches.addView(matchHost)
        gap(matches, 12)
        matches.addView(button("+ 添加触发条件", SOFT, ACCENT) { showAddMatchSheet() }.apply {
            textSize = 14f; minHeight = dp(48); minimumHeight = dp(48)
        })

        val result = card(content)
        section(result, "命中后执行", "动作可以同时触发；转发目标在单独页面管理。")
        actionHost = column()
        result.addView(actionHost)
        gap(result, 12)
        result.addView(button("+ 添加结果动作", SOFT, ACCENT) { showAddActionSheet() }.apply {
            textSize = 14f; minHeight = dp(48); minimumHeight = dp(48)
        })

        setContentView(root)
    }

    // region match rules

    private fun renderMatches(): Unit = with(ui) {
        if (!::matchHost.isInitialized) return
        matchHost.removeAllViews()
        if (matchRules.isEmpty()) {
            matchHost.addView(label("还没有触发条件，至少添加一条才能保存。", 13, MUTED).apply {
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        matchRules.forEachIndexed { index, rule ->
            val row = column(14).apply {
                background = rounded(Color.WHITE, border = LINE)
                isFocusable = true
                contentDescription = "触发条件 ${index + 1}，${rule.summary()}"
                setOnClickListener { showMatchEditor(index) }
            }
            val head = row()
            val handle = label("☰", 17, MUTED, true).apply {
                setPadding(dp(8), dp(4), dp(8), dp(4))
                contentDescription = "长按拖动排序"
                isFocusable = true
                setOnLongClickListener {
                    matchHost.beginDrag(row, index)
                    true
                }
            }
            head.addView(handle)
            head.addView(label("触发条件 ${index + 1}", 14, INK, true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(label("删除", 12, RED, true).apply {
                setPadding(dp(10), dp(8), dp(6), dp(8))
                background = rounded(RED_SOFT, 10)
                isFocusable = true
                setOnClickListener {
                    if (index in matchRules.indices) {
                        matchRules.removeAt(index)
                        renderMatches()
                    }
                }
            })
            row.addView(head)
            gap(row, 8)
            rule.conditions().forEach { (field, condition) ->
                row.addView(label("${field.label} · ${condition.mode.label} · ${condition.value}", 12, SECONDARY))
                gap(row, 4)
            }
            matchHost.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun showAddMatchSheet() {
        BottomSheet.show(this, "添加触发条件", "选择一种匹配方式") { body, dialog ->
            body.addView(BottomSheet.option(ui, "手机号触发条件", "本机号码、对方号码、正文内容", Kind.MESSAGE) {
                matchRules += MatchRule()
                renderMatches()
                dialog.dismiss()
                showMatchEditor(matchRules.lastIndex)
            })
            body.addView(BottomSheet.option(ui, "HTTP(S) 触发条件", "按外部接口结果判断，暂未开放", Kind.TOOLS, enabled = false) {})
        }
    }

    private fun showMatchEditor(index: Int) {
        val current = matchRules.getOrNull(index) ?: return
        val readers = mutableMapOf<MatchField, () -> MatchCondition?>()
        BottomSheet.show(
            this, "触发条件 ${index + 1}", "留空表示不限制该条件",
            onConfirm = { dialog ->
                val updated = MatchRule(
                    id = current.id,
                    localNumber = readers[MatchField.LOCAL_NUMBER]?.invoke(),
                    senderNumber = readers[MatchField.SENDER_NUMBER]?.invoke(),
                    body = readers[MatchField.BODY]?.invoke(),
                )
                val error = updated.validationError()
                if (error != null) {
                    toast(error)
                } else {
                    matchRules[index] = updated
                    renderMatches()
                    dialog.dismiss()
                }
            },
        ) { body, dialog ->
            with(ui) {
                MatchField.entries.forEach { field ->
                    val existingCondition = when (field) {
                        MatchField.LOCAL_NUMBER -> current.localNumber
                        MatchField.SENDER_NUMBER -> current.senderNumber
                        MatchField.BODY -> current.body
                    }
                    body.addView(label(field.label, 14, INK, true)); gap(body, 5)
                    body.addView(label(field.hint, 11, MUTED)); gap(body, 8)
                    val modeRow = row().apply {
                        setPadding(dp(3), dp(3), dp(3), dp(3)); background = rounded(BACKGROUND, 10)
                    }
                    val modes = mutableListOf<Button>()
                    var selectedMode = existingCondition?.mode ?: MatchMode.REGEX
                    MatchMode.entries.forEach { mode ->
                        val chip = button(mode.label, BACKGROUND, SECONDARY) {
                            selectedMode = mode
                            modes.forEachIndexed { i, b -> styleChip(b, MatchMode.entries[i] == selectedMode) }
                        }.apply {
                            textSize = 12f; minHeight = dp(40); minimumHeight = dp(40)
                            setPadding(dp(4), dp(8), dp(4), dp(8))
                        }
                        modes += chip
                        modeRow.addView(chip, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                    modes.forEachIndexed { i, b -> styleChip(b, MatchMode.entries[i] == selectedMode) }
                    body.addView(modeRow); gap(body, 8)
                    val input = field("输入条件内容，留空表示不限制", field == MatchField.BODY, field.hint).apply {
                        setText(existingCondition?.value.orEmpty())
                    }
                    body.addView(input); gap(body, 18)
                    readers[field] = {
                        val value = input.text.toString().trim()
                        if (value.isEmpty()) null else MatchCondition(selectedMode, value)
                    }
                }
            }
        }
    }

    // endregion

    // region actions

    private fun renderActions(): Unit = with(ui) {
        if (!::actionHost.isInitialized) return
        actionHost.removeAllViews()
        if (actions.isEmpty()) {
            actionHost.addView(label("还没有结果动作，命中后不会有任何反应。", 13, MUTED).apply {
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        actions.forEachIndexed { index, action ->
            val row = row().apply {
                setPadding(dp(2), dp(12), dp(2), dp(12))
                minimumHeight = dp(58)
                isFocusable = true
                contentDescription = "${action.type.label}，${action.summary()}"
                setOnClickListener {
                    when {
                        action.type == ActionType.FORWARD -> openForwardPicker(index)
                        else -> openActionConfig(index)
                    }
                }
            }
            row.addView(icon(kindFor(action.type), ACCENT).apply {
                setPadding(dp(9), dp(9), dp(9), dp(9)); background = rounded(SOFT, 12)
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(13) })
            val text = column()
            text.addView(label(action.type.label, 14, INK, true)); gap(text, 4)
            val available = store.state().forwardsFor(action).size
            text.addView(label(action.summary(available), 12, SECONDARY))
            row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label("删除", 12, RED, true).apply {
                setPadding(dp(10), dp(8), dp(6), dp(8)); background = rounded(RED_SOFT, 10)
                isFocusable = true
                setOnClickListener {
                    if (index in actions.indices) {
                        actions.removeAt(index)
                        renderActions()
                    }
                }
            })
            actionHost.addView(row, LinearLayout.LayoutParams(-1, -2))
            if (index < actions.lastIndex) divider(actionHost)
        }
    }

    private fun kindFor(type: ActionType): Kind = when (type) {
        ActionType.FORWARD -> Kind.COPY
        ActionType.RING -> Kind.BELL
        ActionType.VIBRATE -> Kind.VIBRATE
        ActionType.FLASH -> Kind.INFO
    }

    private fun showAddActionSheet() {
        BottomSheet.show(this, "添加结果动作", "可以多次添加，命中时同时执行") { body, dialog ->
            body.addView(BottomSheet.option(ui, "转发", "推送到 HTTP(S) 服务或飞书群", Kind.COPY) {
                actions += RuleAction(ActionType.FORWARD)
                renderActions()
                dialog.dismiss()
                openForwardPicker(actions.lastIndex)
            })
            body.addView(BottomSheet.option(ui, "响铃", "循环响铃，可自定义音乐", Kind.BELL) {
                actions += RuleAction.local(ActionType.RING); renderActions(); dialog.dismiss(); openActionConfig(actions.lastIndex)
            })
            body.addView(BottomSheet.option(ui, "振动", "原生循环振动", Kind.VIBRATE) {
                actions += RuleAction.local(ActionType.VIBRATE); renderActions(); dialog.dismiss(); openActionConfig(actions.lastIndex)
            })
            body.addView(BottomSheet.option(ui, "闪光灯", "手电筒闪烁", Kind.INFO) {
                actions += RuleAction.local(ActionType.FLASH); renderActions(); dialog.dismiss(); openActionConfig(actions.lastIndex)
            })
        }
    }

    private fun openActionConfig(index: Int) {
        val action = actions.getOrNull(index) ?: return
        pendingActionIndex = index
        startActivityForResult(
            Intent(this, ActionConfigActivity::class.java)
                .putExtra(ActionConfigActivity.EXTRA_TYPE, action.type.name)
                .putExtra(ActionConfigActivity.EXTRA_ENABLED, action.enabled)
                .putExtra(ActionConfigActivity.EXTRA_DELAY, action.delayMs)
                .putExtra(ActionConfigActivity.EXTRA_DURATION, action.durationSeconds)
                .putExtra(ActionConfigActivity.EXTRA_REPEAT, action.repeatCount)
                .putExtra(ActionConfigActivity.EXTRA_SOUND, action.soundUri),
            REQUEST_ACTION,
        )
    }

    private fun openForwardPicker(index: Int) {
        val action = actions.getOrNull(index) ?: return
        pendingForwardIndex = index
        startActivityForResult(
            Intent(this, ForwardPickerActivity::class.java)
                .putStringArrayListExtra(ForwardPickerActivity.EXTRA_SELECTED, ArrayList(action.forwardIds)),
            REQUEST_FORWARD,
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ACTION && resultCode == RESULT_OK) {
            val index = pendingActionIndex
            if (index in actions.indices) {
                val current = actions[index]
                actions[index] = current.copy(
                    enabled = data?.getBooleanExtra(ActionConfigActivity.EXTRA_ENABLED, true) ?: true,
                    delayMs = data?.getLongExtra(ActionConfigActivity.EXTRA_DELAY, 0L) ?: 0L,
                    durationSeconds = data?.getIntExtra(ActionConfigActivity.EXTRA_DURATION, 0) ?: 0,
                    repeatCount = data?.getIntExtra(ActionConfigActivity.EXTRA_REPEAT, 0) ?: 0,
                    soundUri = data?.getStringExtra(ActionConfigActivity.EXTRA_SOUND).orEmpty(),
                )
                renderActions()
            }
            pendingActionIndex = -1
            return
        }
        if (requestCode == REQUEST_FORWARD && resultCode == RESULT_OK) {
            val ids = data?.getStringArrayListExtra(ForwardPickerActivity.EXTRA_SELECTED) ?: emptyList()
            val index = pendingForwardIndex
            if (index in actions.indices) {
                actions[index] = actions[index].copy(forwardIds = ids)
                renderActions()
            }
            pendingForwardIndex = -1
        }
    }

    // endregion

    private fun save() {
        val rule = AlarmRule(
            id = ruleId,
            name = nameField.text.toString().trim(),
            description = descriptionField.text.toString().trim(),
            enabled = existing?.enabled ?: true,
            trigger = trigger,
            triggerDelayMs = triggerDelayField.text.toString().trim().toLongOrNull() ?: 0L,
            matchRules = matchRules.toList(),
            actions = actions.toList(),
        )
        val error = rule.validationError()
        if (error != null) {
            AlertDialog.Builder(this)
                .setTitle("还差一点")
                .setMessage(error)
                .setPositiveButton("去修改", null)
                .show()
            return
        }
        if (!store.saveRule(rule)) {
            AlertDialog.Builder(this).setTitle("保存失败").setMessage("请稍后重试").setPositiveButton("知道了", null).show()
            return
        }
        store.log(if (existing == null) "新建规则：${rule.name}" else "修改规则：${rule.name}")
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ID, rule.id))
        finish()
    }

    private fun styleChip(button: Button, selected: Boolean) {
        button.isSelected = selected
        button.background = ui.ripple(if (selected) SOFT else BACKGROUND, 9)
        button.setTextColor(if (selected) ACCENT else SECONDARY)
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_ID = "rule_id"
        private const val KEY_ID = "draft_id"
        private const val KEY_MATCH_ROWS = "draft_match_rows"
        private const val KEY_ACTION_ROWS = "draft_action_rows"
        private const val KEY_FORWARD_INDEX = "draft_forward_index"
        private const val KEY_TRIGGER = "draft_trigger"
        private const val KEY_TRIGGER_DELAY = "draft_trigger_delay"
        private const val REQUEST_FORWARD = 2001
        private const val REQUEST_ACTION = 2002
        private const val SEPARATOR = "\u0001"
    }
}
