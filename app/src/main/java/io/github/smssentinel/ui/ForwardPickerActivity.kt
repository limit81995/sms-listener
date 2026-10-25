package io.github.smssentinel.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.smssentinel.core.ForwardTarget
import io.github.smssentinel.core.ForwardType
import io.github.smssentinel.data.AppStore
import io.github.smssentinel.ui.SentinelIcon.Kind
import io.github.smssentinel.ui.UiKit.Companion.ACCENT
import io.github.smssentinel.ui.UiKit.Companion.BACKGROUND
import io.github.smssentinel.ui.UiKit.Companion.INK
import io.github.smssentinel.ui.UiKit.Companion.LINE
import io.github.smssentinel.ui.UiKit.Companion.MUTED
import io.github.smssentinel.ui.UiKit.Companion.SECONDARY
import io.github.smssentinel.ui.UiKit.Companion.SOFT

/**
 * Single-choice picker over the forward library. Tapping a row returns immediately,
 * so there is no confirm button and no selection counter.
 */
class ForwardPickerActivity : Activity() {
    private lateinit var ui: UiKit
    private lateinit var store: AppStore
    private lateinit var host: LinearLayout
    private lateinit var empty: TextView
    private var selectedId: String? = null
    private var targets: List<ForwardTarget> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        store = AppStore(this)
        selectedId = savedInstanceState?.getString(KEY_SELECTED)
            ?: intent.getStringArrayListExtra(EXTRA_SELECTED)?.firstOrNull()
        buildScreen()
        reload()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_SELECTED, selectedId)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::host.isInitialized) reload()
    }

    private fun reload() {
        targets = store.state().forwards
        if (selectedId != null && targets.none { it.id == selectedId }) selectedId = null
        render()
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
        bar.addView(label("选择转发目标", 17, INK, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(plainButton("添加") { addTarget() }.apply {
            textSize = 13f; minHeight = dp(40); minimumHeight = dp(40)
            setPadding(dp(18), dp(8), dp(18), dp(8)); layoutParams = LinearLayout.LayoutParams(-2, -2)
        })
        root.addView(bar)
        divider(root)

        val content = column().apply { setPadding(dp(18), dp(16), dp(18), dp(24)) }
        root.addView(ScrollView(this@ForwardPickerActivity).apply {
            isFillViewport = true; clipToPadding = false; addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        empty = label("暂无转发规则", 14, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(40), 0, dp(40))
        }
        content.addView(empty)

        host = column()
        content.addView(host, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun render(): Unit = with(ui) {
        if (!::host.isInitialized) return
        host.removeAllViews()
        empty.visibility = if (targets.isEmpty()) View.VISIBLE else View.GONE
        targets.forEach { target ->
            val picked = target.id == selectedId
            val card = column(16).apply {
                background = rounded(if (picked) SOFT else Color.WHITE, border = if (picked) ACCENT else LINE)
                isFocusable = true
                contentDescription = "${target.displayName}，" + if (picked) "已选择" else "点击选择"
                setOnClickListener {
                    selectedId = target.id
                    setResult(RESULT_OK, Intent()
                        .putStringArrayListExtra(EXTRA_SELECTED, arrayListOf(target.id)))
                    finish()
                }
            }
            val head = row()
            head.addView(icon(if (target.type == ForwardType.FEISHU) Kind.MESSAGE else Kind.COPY, ACCENT).apply {
                setPadding(dp(8), dp(8), dp(8), dp(8)); background = rounded(SOFT, 11)
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
            val text = column()
            text.addView(label(target.displayName, 15, INK, true)); gap(text, 5)
            text.addView(label(target.summary(), 12, SECONDARY))
            head.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(label(if (picked) "✓" else "○", 18, if (picked) ACCENT else MUTED, true).apply {
                setPadding(dp(8), 0, dp(4), 0)
            })
            card.addView(head)
            host.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun addTarget() {
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

    companion object {
        const val EXTRA_SELECTED = "selected_ids"
        private const val KEY_SELECTED = "draft_selected"
    }
}
