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
import io.github.smslistener.core.StopScope
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
    private var pendingRequest = 0
    private var pendingIndex = -1
    private var pendingMatchId: String? = null
    private var pendingActionType: ActionType? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        store = AppStore(this)
        val draft = savedInstanceState?.getString(KEY_DRAFT)?.let {
            runCatching { RuleCodec.decodeRule(org.json.JSONObject(it)) }.getOrNull()
        }
        ruleId = draft?.id ?: intent.getStringExtra(EXTRA_ID) ?: UUID.randomUUID().toString()
        existing = store.rule(ruleId)
        val initial = draft ?: existing ?: AlarmRule(id = ruleId)
        matchRules.addAll(initial.matchRules)
        actions.addAll(initial.actions)
        pendingRequest = savedInstanceState?.getInt(KEY_REQUEST) ?: 0
        pendingIndex = savedInstanceState?.getInt(KEY_INDEX, -1) ?: -1
        pendingMatchId = savedInstanceState?.getString(KEY_MATCH_ID)
        pendingActionType = savedInstanceState?.getString(KEY_ACTION_TYPE)?.let { value ->
            ActionType.entries.firstOrNull { it.name == value }
        }
        buildScreen()
        nameField.setText(initial.name)
        descriptionField.setText(initial.description)
        renderMatches()
        renderActions()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_DRAFT, RuleCodec.encodeRule(draft()).toString())
        outState.putInt(KEY_REQUEST, pendingRequest)
        outState.putInt(KEY_INDEX, pendingIndex)
        outState.putString(KEY_MATCH_ID, pendingMatchId)
        outState.putString(KEY_ACTION_TYPE, pendingActionType?.name)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::actionHost.isInitialized) renderActions()
    }

    private fun draft() = AlarmRule(
        id = ruleId,
        name = nameField.text.toString(),
        description = descriptionField.text.toString(),
        enabled = store.rule(ruleId)?.enabled ?: existing?.enabled ?: true,
        matchRules = matchRules.toList(),
        actions = actions.toList(),
    )

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

        val matches = card(content)
        section(matches, "触发条件", "按列表顺序判断，满足任意一条即执行一次。长按左侧手柄调整顺序。")
        matchHost = DragSortColumn(this@RuleEditActivity).apply {
            tag = "trigger_rows"
            onMove = { from, to ->
                if (from in matchRules.indices && to in matchRules.indices) {
                    matchRules.add(to, matchRules.removeAt(from))
                    renderMatches()
                }
            }
        }
        matches.addView(matchHost)
        gap(matches, 12)
        matches.addView(button("+ 添加触发条件", SOFT, ACCENT) { openMatchEditor(null) }.apply {
            textSize = 14f; minHeight = dp(48); minimumHeight = dp(48)
        })

        val result = card(content)
        section(result, "命中后执行", "动作可以同时触发；转发目标在单独页面管理。")
        actionHost = column().apply { tag = "action_rows" }
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
                tag = "trigger_row"
                background = rounded(Color.WHITE, border = LINE)
                isFocusable = true
                contentDescription = "触发条件 ${index + 1}，${rule.summary()}"
                setOnClickListener { openMatchEditor(index) }
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
            head.addView(label("${index + 1}. ${rule.source.label}", 14, INK, true), LinearLayout.LayoutParams(0, -2, 1f))
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
            row.addView(label(rule.summary(), 12, SECONDARY))
            matchHost.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun openMatchEditor(index: Int?) {
        val intent = if (index == null) Intent(this, TriggerTypeActivity::class.java) else {
            val current = matchRules.getOrNull(index) ?: return
            Intent(this, MatchRuleActivity::class.java)
                .putExtra(MatchRuleActivity.EXTRA_MATCH, RuleCodec.encodeMatchRule(current).toString())
        }
        launchEditor(intent, REQUEST_MATCH, index)
    }

    private fun launchEditor(intent: Intent, request: Int, index: Int?, type: ActionType? = null) {
        if (pendingRequest != 0) return
        pendingRequest = request
        pendingIndex = index ?: -1
        pendingActionType = type
        pendingMatchId = if (request == REQUEST_MATCH) index?.let { matchRules.getOrNull(it)?.id } else null
        try { startActivityForResult(intent, request) }
        catch (_: RuntimeException) { clearPending(); showError("页面打开失败，请重试") }
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
            val available = store.state().forwardsFor(action).size
            val targetName = if (action.operation == ActionOperation.STOP && action.stopScope == StopScope.SPECIFIC_RULE) {
                when {
                    action.targetRuleId.isBlank() -> "目标未选择"
                    action.targetRuleId == ruleId -> nameField.text.toString().ifBlank { "本规则（正在编辑）" }
                    else -> store.rule(action.targetRuleId)?.name ?: "目标已删除（" + action.targetRuleId + "）"
                }
            } else null
            val summary = action.summary(available, targetName)
            val row = row().apply {
                tag = "action_row"
                setPadding(dp(2), dp(12), dp(2), dp(12))
                minimumHeight = dp(58)
                isFocusable = true
                contentDescription = summary
                setOnClickListener {
                    when {
                        action.type == ActionType.FORWARD -> openForwardPicker(index)
                        else -> openActionConfig(index, action.type)
                    }
                }
            }
            row.addView(icon(kindFor(action.type), ACCENT).apply {
                setPadding(dp(9), dp(9), dp(9), dp(9)); background = rounded(SOFT, 12)
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(13) })
            val text = column()
            text.addView(label(action.type.label + " · " + action.operation.label, 14, INK, true)); gap(text, 4)
            text.addView(label(summary, 12, SECONDARY))
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
                dialog.dismiss()
                openForwardPicker(null)
            })
            body.addView(BottomSheet.option(ui, "响铃", "循环响铃，可自定义音乐", Kind.BELL) {
                dialog.dismiss(); openActionConfig(null, ActionType.RING)
            })
            body.addView(BottomSheet.option(ui, "振动", "原生循环振动", Kind.VIBRATE) {
                dialog.dismiss(); openActionConfig(null, ActionType.VIBRATE)
            })
            body.addView(BottomSheet.option(ui, "闪光灯", "手电筒闪烁", Kind.INFO) {
                dialog.dismiss(); openActionConfig(null, ActionType.FLASH)
            })
        }
    }

    private fun openActionConfig(index: Int?, type: ActionType) {
        val action = if (index == null) RuleAction(type) else actions.getOrNull(index) ?: return
        launchEditor(Intent(this, ActionConfigActivity::class.java)
            .putExtra(ActionConfigActivity.EXTRA_TYPE, action.type.name)
            .putExtra(ActionConfigActivity.EXTRA_ENABLED, action.enabled)
            .putExtra(ActionConfigActivity.EXTRA_DELAY, action.delayMs)
            .putExtra(ActionConfigActivity.EXTRA_DURATION, action.durationSeconds)
            .putExtra(ActionConfigActivity.EXTRA_REPEAT, action.repeatCount)
            .putExtra(ActionConfigActivity.EXTRA_SOUND, action.soundUri)
            .putExtra(ActionConfigActivity.EXTRA_OPERATION, action.operation.name)
            .putExtra(ActionConfigActivity.EXTRA_STOP_SCOPE, action.stopScope.name)
            .putExtra(ActionConfigActivity.EXTRA_TARGET_RULE_ID, action.targetRuleId)
            .putExtra(ActionConfigActivity.EXTRA_OWNER_RULE_ID, ruleId), REQUEST_ACTION, index, action.type)
    }

    private fun openForwardPicker(index: Int?) {
        val action = if (index == null) RuleAction(ActionType.FORWARD) else actions.getOrNull(index) ?: return
        launchEditor(Intent(this, ForwardPickerActivity::class.java)
            .putStringArrayListExtra(ForwardPickerActivity.EXTRA_SELECTED, ArrayList(action.forwardIds)),
            REQUEST_FORWARD, index, ActionType.FORWARD)
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pendingRequest || pendingRequest == 0) return
        val index = pendingIndex
        val matchId = pendingMatchId
        val expectedType = pendingActionType
        clearPending()
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQUEST_MATCH -> {
                val updated = data.getStringExtra(MatchRuleActivity.EXTRA_MATCH)?.let {
                    runCatching { RuleCodec.decodeMatchRule(org.json.JSONObject(it)) }.getOrNull()
                } ?: return
                updated.validationError()?.let { showError(it); return }
                if (index == -1) {
                    if (matchRules.size >= 20) { showError("触发条件最多 20 条"); return }
                    matchRules += updated
                } else {
                    val position = matchRules.indexOfFirst { it.id == matchId }
                    if (position < 0) return
                    matchRules[position] = updated.copy(id = matchRules[position].id)
                }
                renderMatches()
            }
            REQUEST_ACTION -> {
                val type = data.getStringExtra(ActionConfigActivity.EXTRA_TYPE)?.let { name ->
                    ActionType.entries.firstOrNull { it.name == name }
                } ?: return
                if (type != expectedType || !type.isLocal) return
                if (data.getStringExtra(ActionConfigActivity.EXTRA_OWNER_RULE_ID) != ruleId) return
                val operation = ActionOperation.entries.firstOrNull {
                    it.name == data.getStringExtra(ActionConfigActivity.EXTRA_OPERATION)
                } ?: return
                val scope = StopScope.entries.firstOrNull {
                    it.name == data.getStringExtra(ActionConfigActivity.EXTRA_STOP_SCOPE)
                } ?: return
                val target = data.getStringExtra(ActionConfigActivity.EXTRA_TARGET_RULE_ID) ?: return
                val stop = operation == ActionOperation.STOP
                val updated = RuleAction(type,
                    enabled = true,
                    delayMs = data.getLongExtra(ActionConfigActivity.EXTRA_DELAY, 0L),
                    durationSeconds = if (stop) 0 else data.getIntExtra(ActionConfigActivity.EXTRA_DURATION, 0),
                    repeatCount = if (stop) 0 else data.getIntExtra(ActionConfigActivity.EXTRA_REPEAT, 0),
                    soundUri = if (stop) "" else data.getStringExtra(ActionConfigActivity.EXTRA_SOUND).orEmpty(),
                    operation = operation,
                    stopScope = if (stop) scope else StopScope.THIS_RULE,
                    targetRuleId = if (stop && scope == StopScope.SPECIFIC_RULE)
                        target else "")
                commitAction(index, updated)
            }
            REQUEST_FORWARD -> {
                val ids = data.getStringArrayListExtra(ForwardPickerActivity.EXTRA_SELECTED)?.distinct() ?: return
                if (ids.size != 1 || store.forward(ids.single()) == null) return
                val updated = (actions.getOrNull(index)?.takeIf { it.type == ActionType.FORWARD }
                    ?: RuleAction(ActionType.FORWARD)).copy(forwardIds = ids, enabled = true,
                        operation = ActionOperation.START, stopScope = StopScope.THIS_RULE, targetRuleId = "")
                commitAction(index, updated)
            }
        }
    }

    private fun commitAction(index: Int, updated: RuleAction) {
        updated.validationError()?.let { showError(it); return }
        if (missingStopTarget(updated)) { showError("停止目标规则已删除，请重新选择"); return }
        if (index == -1) {
            if (actions.size >= 20) { showError("结果动作最多 20 个"); return }
            actions += updated
        } else if (index in actions.indices) actions[index] = updated else return
        renderActions()
    }

    private fun missingStopTarget(action: RuleAction): Boolean =
        action.operation == ActionOperation.STOP && action.stopScope == StopScope.SPECIFIC_RULE &&
            action.targetRuleId != ruleId && store.rule(action.targetRuleId) == null

    private fun clearPending() {
        pendingRequest = 0; pendingIndex = -1; pendingMatchId = null; pendingActionType = null
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this).setTitle("请检查配置").setMessage(message)
            .setPositiveButton("知道了", null).show()
    }

    // endregion

    private fun save() {
        val rule = draft().let { it.copy(name = it.name.trim(), description = it.description.trim()) }
        val error = rule.validationError()
        if (error != null) {
            AlertDialog.Builder(this)
                .setTitle("还差一点")
                .setMessage(error)
                .setPositiveButton("去修改", null)
                .show()
            return
        }
        if (rule.actions.any(::missingStopTarget)) { showError("停止目标规则已删除，请重新选择"); return }
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
        private const val REQUEST_FORWARD = 2001
        private const val REQUEST_ACTION = 2002
        private const val REQUEST_MATCH = 2003
        private const val KEY_DRAFT = "editor_draft_json"
        private const val KEY_REQUEST = "pending_request"
        private const val KEY_INDEX = "pending_index"
        private const val KEY_MATCH_ID = "pending_match_id"
        private const val KEY_ACTION_TYPE = "pending_action_type"
    }
}
