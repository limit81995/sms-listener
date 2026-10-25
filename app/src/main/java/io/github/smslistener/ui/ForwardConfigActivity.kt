package io.github.smslistener.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.smslistener.core.ForwardTarget
import io.github.smslistener.core.ForwardType
import io.github.smslistener.data.AppStore
import io.github.smslistener.ui.UiKit.Companion.ACCENT
import io.github.smslistener.ui.UiKit.Companion.BACKGROUND
import io.github.smslistener.ui.UiKit.Companion.INK
import io.github.smslistener.ui.UiKit.Companion.MUTED
import io.github.smslistener.ui.UiKit.Companion.SECONDARY
import io.github.smslistener.ui.UiKit.Companion.SOFT

/** Configures exactly one forward target. The form differs per [ForwardType]. */
class ForwardConfigActivity : Activity() {
    private lateinit var ui: UiKit
    private lateinit var store: AppStore
    private lateinit var nameField: EditText
    private lateinit var urlField: EditText
    private lateinit var bodyField: EditText
    private lateinit var titleLabel: TextView
    // Optional per type; null when the field is not part of this form.
    private var methodField: EditText? = null
    private var headersField: EditText? = null
    private var secretField: EditText? = null
    private var target: ForwardTarget? = null
    private var type: ForwardType = ForwardType.HTTP

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        store = AppStore(this)
        val id = savedInstanceState?.getString(KEY_ID) ?: intent.getStringExtra(EXTRA_ID)
        target = id?.let { store.forward(it) }
        type = target?.type ?: ForwardType.entries.firstOrNull {
            it.name == (savedInstanceState?.getString(KEY_TYPE) ?: intent.getStringExtra(EXTRA_TYPE))
        } ?: ForwardType.HTTP
        buildScreen()
        target?.let { existing ->
            nameField.setText(existing.name)
            urlField.setText(existing.url)
            methodField?.setText(existing.method)
            headersField?.setText(existing.headers)
            bodyField.setText(existing.body)
            secretField?.setText(existing.secret)
        }
        titleLabel.text = if (target == null) "新建" + type.label + "转发" else "编辑" + type.label + "转发"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_ID, target?.id)
        outState.putString(KEY_TYPE, type.name)
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
        bar.addView(plainButton("返回", INK) { finish() }.apply {
            textSize = 13f; minHeight = dp(40); minimumHeight = dp(40)
            setPadding(dp(16), dp(8), dp(16), dp(8)); layoutParams = LinearLayout.LayoutParams(-2, -2)
        })
        titleLabel = label("", 17, INK, true).apply { gravity = Gravity.CENTER }
        bar.addView(titleLabel, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(plainButton("保存") { save() }.apply {
            textSize = 13f; minHeight = dp(40); minimumHeight = dp(40)
            setPadding(dp(18), dp(8), dp(18), dp(8)); layoutParams = LinearLayout.LayoutParams(-2, -2)
        })
        root.addView(bar)
        divider(root)

        val content = column().apply { setPadding(dp(18), dp(16), dp(18), dp(28)) }
        root.addView(ScrollView(this@ForwardConfigActivity).apply {
            isFillViewport = true; clipToPadding = false; addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        val card = card(content)
        section(card, "基本信息", if (type == ForwardType.FEISHU) "推送到飞书群机器人" else "以 HTTP(S) 请求发送短信内容")
        card.addView(label("名称  ·  可选", 13, INK, true)); gap(card, 8)
        nameField = field("例如：运维群告警", false, "转发名称")
        card.addView(nameField); gap(card, 16)

        card.addView(label(if (type == ForwardType.FEISHU) "机器人 Webhook 地址" else "请求地址", 13, INK, true))
        gap(card, 8)
        card.addView(label("必须以 http:// 或 https:// 开头", 12, SECONDARY)); gap(card, 10)
        urlField = field("https://…", false, "请求地址")
        card.addView(urlField); gap(card, 16)

        if (type == ForwardType.HTTP) {
            card.addView(label("请求方法", 13, INK, true)); gap(card, 8)
            methodField = field("GET / POST / PUT / PATCH / DELETE", false, "请求方法").also {
                card.addView(it); gap(card, 16)
            }
            card.addView(label("请求头  ·  可选", 13, INK, true)); gap(card, 8)
            card.addView(label("每行一个 Name: Value", 12, SECONDARY)); gap(card, 10)
            headersField = field("Content-Type: application/json", true, "请求头").also {
                card.addView(it); gap(card, 16)
            }
        }

        card.addView(label(if (type == ForwardType.FEISHU) "消息模板  ·  可选" else "请求正文  ·  可选", 13, INK, true))
        gap(card, 8)
        card.addView(label("支持 {text} {sender} {local} {rule} {time}；留空则发送默认 JSON", 12, SECONDARY))
        gap(card, 10)
        bodyField = field("留空使用默认格式", true, "请求正文模板")
        card.addView(bodyField)

        if (type == ForwardType.FEISHU) {
            val secretCard = card(content)
            section(secretCard, "签名校验", "机器人开启签名校验时填写，否则留空")
            secretField = field("签名密钥", false, "签名密钥").also { secretCard.addView(it) }
        }

        gap(content, 4)
        content.addView(label("保存后可在规则里选择这个转发目标。", 11, MUTED))
        setContentView(root)
    }

    private fun save() {
        val draft = (target ?: ForwardTarget(type = type)).copy(
            name = nameField.text.toString().trim(),
            url = urlField.text.toString().trim(),
            method = methodField?.text?.toString()?.trim()?.ifBlank { "POST" } ?: "POST",
            headers = headersField?.text?.toString().orEmpty(),
            body = bodyField.text.toString(),
            secret = secretField?.text?.toString()?.trim().orEmpty(),
        ).sanitized()

        val error = draft.validationError()
        if (error != null) {
            AlertDialog.Builder(this).setTitle("还差一点").setMessage(error)
                .setPositiveButton("去修改", null).show()
            return
        }
        if (!store.saveForward(draft)) {
            AlertDialog.Builder(this).setTitle("保存失败").setMessage("请稍后重试")
                .setPositiveButton("知道了", null).show()
            return
        }
        store.log((if (target == null) "新建转发目标：" else "修改转发目标：") + draft.displayName)
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ID, draft.id))
        finish()
    }

    companion object {
        const val EXTRA_ID = "forward_id"
        const val EXTRA_TYPE = "forward_type"
        private const val KEY_ID = "draft_id"
        private const val KEY_TYPE = "draft_type"
    }
}
