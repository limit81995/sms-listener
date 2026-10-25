package io.github.smssentinel.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.smssentinel.core.ActionType
import io.github.smssentinel.core.ForwardTarget
import io.github.smssentinel.core.ForwardType
import io.github.smssentinel.data.AppStore
import io.github.smssentinel.ui.SentinelIcon.Kind
import io.github.smssentinel.ui.UiKit.Companion.ACCENT
import io.github.smssentinel.ui.UiKit.Companion.BACKGROUND
import io.github.smssentinel.ui.UiKit.Companion.INK
import io.github.smssentinel.ui.UiKit.Companion.LINE
import io.github.smssentinel.ui.UiKit.Companion.MUTED
import io.github.smssentinel.ui.UiKit.Companion.RED
import io.github.smssentinel.ui.UiKit.Companion.SECONDARY
import io.github.smssentinel.ui.UiKit.Companion.SOFT

/** Owns the forward library: create, edit, delete. */
class ForwardLibraryActivity : Activity() {
    private lateinit var ui: UiKit
    private lateinit var store: AppStore
    private lateinit var host: LinearLayout
    private lateinit var countLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        store = AppStore(this)
        buildScreen()
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::host.isInitialized) render()
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
        bar.addView(label("转发规则", 17, INK, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(plainButton("添加") { showAddSheet() }.apply {
            textSize = 13f; minHeight = dp(40); minimumHeight = dp(40)
            setPadding(dp(18), dp(8), dp(18), dp(8)); layoutParams = LinearLayout.LayoutParams(-2, -2)
        })
        root.addView(bar)
        divider(root)

        val content = column().apply { setPadding(dp(18), dp(16), dp(18), dp(24)) }
        root.addView(ScrollView(this@ForwardLibraryActivity).apply {
            isFillViewport = true; clipToPadding = false; addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        val intro = card(content)
        val line = row()
        line.addView(label("转发目标", 16, INK, true), LinearLayout.LayoutParams(0, -2, 1f))
        countLabel = label("", 12, SECONDARY)
        line.addView(countLabel)
        intro.addView(line); gap(intro, 8)
        intro.addView(label("一处维护，规则里直接选择。点击编辑，左滑删除。", 12, SECONDARY))

        host = column()
        content.addView(host, LinearLayout.LayoutParams(-1, -2))
        gap(content, 10)
        content.addView(label("模板变量：{text} 正文 · {sender} 对方号码 · {local} 本机号码 · {rule} 规则名 · {time} 时间", 11, MUTED))
        setContentView(root)
    }

    private fun render(): Unit = with(ui) {
        if (!::host.isInitialized) return
        val targets = store.state().forwards
        host.removeAllViews()
        countLabel.text = "${targets.size} 个"
        if (targets.isEmpty()) {
            val empty = card(host, 22)
            empty.addView(icon(Kind.COPY, MUTED, 26))
            gap(empty, 10)
            empty.addView(label("暂无转发规则", 15, bold = true))
            gap(empty, 6)
            empty.addView(label("点右上角“添加”，支持 HTTP(S) 与飞书。", 12, SECONDARY))
            return
        }
        targets.forEach { target ->
            val swipe = SwipeRevealRow(this@ForwardLibraryActivity, "删除") { confirmDelete(target) }
            swipe.setRow(cardFor(target))
            host.addView(swipe, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun cardFor(target: ForwardTarget): View = with(ui) {
        val card = column(16).apply {
            background = rounded(Color.WHITE, border = LINE)
            isFocusable = true
            contentDescription = "${target.displayName}，${target.summary()}"
            setOnClickListener { edit(target.id) }
            setOnLongClickListener { confirmDelete(target); true }
        }
        val head = row()
        head.addView(icon(if (target.type == ForwardType.FEISHU) Kind.MESSAGE else Kind.COPY, ACCENT).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8)); background = rounded(SOFT, 11)
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
        val text = column()
        text.addView(label(target.displayName, 15, INK, true)); gap(text, 5)
        text.addView(label(target.summary(), 12, SECONDARY))
        head.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(label(target.type.label, 10, ACCENT, true).apply {
            setPadding(dp(8), dp(5), dp(8), dp(5)); background = rounded(SOFT, 8)
        })
        card.addView(head)
        val users = store.state().rules.count { rule ->
            rule.actions.any { it.type == ActionType.FORWARD && target.id in it.forwardIds }
        }
        gap(card, 10)
        card.addView(label(if (users == 0) "尚未被任何规则使用" else "被 $users 条规则使用", 11, MUTED))
        return card
    }

    private fun confirmDelete(target: ForwardTarget) {
        val used = store.state().rules.count { rule ->
            rule.actions.any { it.type == ActionType.FORWARD && target.id in it.forwardIds }
        }
        AlertDialog.Builder(this).setTitle("删除转发目标？")
            .setMessage(
                "「${target.displayName}」删除后，" +
                    if (used > 0) "会从 $used 条规则里一起移除。" else "不会影响其他配置。",
            )
            .setPositiveButton("删除") { _, _ ->
                store.deleteForward(target.id)
                store.log("删除转发目标：${target.displayName}")
                render()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun edit(id: String) {
        startActivity(Intent(this, ForwardConfigActivity::class.java)
            .putExtra(ForwardConfigActivity.EXTRA_ID, id))
    }

    private fun showAddSheet() {
        BottomSheet.show(this, "添加转发目标", "选择类型后进入配置页") { body, dialog ->
            body.addView(BottomSheet.option(ui, "HTTP(S) 转发", "自定义方法、请求头与正文", Kind.COPY) {
                dialog.dismiss()
                startActivity(Intent(this, ForwardConfigActivity::class.java)
                    .putExtra(ForwardConfigActivity.EXTRA_TYPE, ForwardType.HTTP.name))
            })
            body.addView(BottomSheet.option(ui, "飞书转发", "推送到群机器人，支持签名", Kind.MESSAGE) {
                dialog.dismiss()
                startActivity(Intent(this, ForwardConfigActivity::class.java)
                    .putExtra(ForwardConfigActivity.EXTRA_TYPE, ForwardType.FEISHU.name))
            })
        }
    }
}
