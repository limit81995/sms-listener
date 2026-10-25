package io.github.smslistener.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.smslistener.data.AppStore
import io.github.smslistener.ui.UiKit.Companion.ACCENT
import io.github.smslistener.ui.UiKit.Companion.BACKGROUND
import io.github.smslistener.ui.UiKit.Companion.INK
import io.github.smslistener.ui.UiKit.Companion.MUTED
import io.github.smslistener.ui.UiKit.Companion.RED
import io.github.smslistener.ui.UiKit.Companion.SECONDARY
import io.github.smslistener.ui.UiKit.Companion.SOFT

/** Full log view with copy and a confirmed clear. */
class LogsActivity : Activity() {
    private lateinit var ui: UiKit
    private lateinit var store: AppStore
    private lateinit var body: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        store = AppStore(this)
        buildScreen()
        refresh()
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
        bar.addView(label("最新动态", 17, INK, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(plainButton("清空", RED) { confirmClear() }.apply {
            textSize = 13f; minHeight = dp(40); minimumHeight = dp(40)
            setPadding(dp(16), dp(8), dp(16), dp(8)); layoutParams = LinearLayout.LayoutParams(-2, -2)
        })
        root.addView(bar)
        divider(root)

        val content = column().apply { setPadding(dp(18), dp(14), dp(18), dp(24)) }
        root.addView(ScrollView(this@LogsActivity).apply {
            isFillViewport = true; clipToPadding = false; addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        val actions = row()
        actions.addView(button("复制诊断", SOFT, ACCENT) { copyReport() }.apply {
            textSize = 13f; minHeight = dp(44); minimumHeight = dp(44)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        content.addView(actions)
        gap(content, 14)

        body = label("", 13).apply {
            setTextIsSelectable(true)
            setLineSpacing(dp(6).toFloat(), 1f)
        }
        content.addView(body)
        gap(content, 12)
        content.addView(label("只保留最近 200 条；日志不记录短信正文和号码。", 11, MUTED))
        setContentView(root)
    }

    private fun refresh() {
        val report = Diagnostics.text(this) + "\n\n最近运行记录\n\n" + store.logs()
        body.text = report
    }

    private fun copyReport() {
        val report = Diagnostics.text(this) + "\n\n最近运行记录\n\n" + store.logs()
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("短信哨兵诊断", report))
        android.widget.Toast.makeText(this, "诊断与日志已复制", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清空运行记录？")
            .setMessage("触发规则和转发配置会保留，只清除日志。")
            .setPositiveButton("清空") { _, _ ->
                store.clearLogs()
                refresh()
                android.widget.Toast.makeText(this, "已清空", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
