package io.github.smssentinel.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.smssentinel.action.ActionExecutor
import io.github.smssentinel.alarm.AlarmService
import io.github.smssentinel.alarm.AlarmSpec
import io.github.smssentinel.core.RuleMatch
import io.github.smssentinel.core.RuleMatcher
import io.github.smssentinel.core.SmsSubject
import io.github.smssentinel.data.AppStore
import io.github.smssentinel.ui.SentinelIcon.Kind
import io.github.smssentinel.ui.UiKit.Companion.ACCENT
import io.github.smssentinel.ui.UiKit.Companion.BACKGROUND
import io.github.smssentinel.ui.UiKit.Companion.INK
import io.github.smssentinel.ui.UiKit.Companion.MUTED
import io.github.smssentinel.ui.UiKit.Companion.SECONDARY
import io.github.smssentinel.ui.UiKit.Companion.SOFT

/** Alarm tests and the SMS match checker, opened from Settings. */
class TestsActivity : Activity() {
    private lateinit var ui: UiKit
    private lateinit var store: AppStore
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var starting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        store = AppStore(this)
        buildScreen()
    }

    override fun onResume() { super.onResume(); updateStatus() }

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
        bar.addView(label("测试功能", 17, INK, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar)
        divider(root)

        val content = column().apply { setPadding(dp(18), dp(16), dp(18), dp(24)) }
        root.addView(ScrollView(this@TestsActivity).apply {
            isFillViewport = true; clipToPadding = false; addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        status = label("", 13, SECONDARY).apply {
            setPadding(dp(4), dp(10), dp(4), dp(4))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status)

        val card = card(content)
        section(card, "报警测试", "确认设备能正常响铃、振动和闪光。")
        actionRow(card, "振动测试", "振动 5 秒，可随时停止", Kind.VIBRATE) { test(vibrate = true) }
        divider(card)
        actionRow(card, "响铃测试", "响铃 5 秒，请先确认闹钟音量", Kind.BELL) { test(vibrate = false) }
        divider(card)
        actionRow(card, "闪光灯测试", "手电筒闪烁 5 秒，需要相机权限", Kind.INFO) {
            start(AlarmSpec(sound = false, vibration = false, flash = true, durationSeconds = 5), "闪光灯测试")
        }
        divider(card)
        actionRow(card, "完整报警测试", "响铃、振动、闪光灯同时触发 5 秒", Kind.PLAY) {
            start(AlarmSpec(sound = true, vibration = true, flash = true, durationSeconds = 5), "完整报警测试")
        }

        val match = card(content)
        section(match, "短信匹配检查", "输入号码与正文，检查会触发哪些规则。")
        actionRow(match, "开始检查", "不会产生报警或发送网络请求", Kind.RULES) { simulateSms() }

        gap(content, 10)
        content.addView(label("测试会中断当前的报警；报警进行中时需先停止。", 11, MUTED))
        setContentView(root)
    }

    private fun test(vibrate: Boolean) = start(
        AlarmSpec(sound = !vibrate, vibration = vibrate, flash = false, durationSeconds = 5),
        if (vibrate) "振动测试" else "响铃测试",
    )

    private fun start(spec: AlarmSpec, reason: String) {
        if (AlarmService.active || starting) {
            status.text = "当前正在报警，请先点击顶部的停止按钮"
            return
        }
        starting = true
        status.text = if (ActionExecutor.runLocal(this, spec, reason)) "测试已启动：$reason" else "启动失败，请查看运行日志"
        handler.postDelayed({ starting = false }, 1000)
    }

    private fun updateStatus() {
        if (!::status.isInitialized) return
        status.text = if (AlarmService.active) "报警进行中，可从通知或首页停止" else ""
    }

    private fun simulateSms() = with(ui) {
        val body = column(22)
        body.addView(label("输入号码与短信正文，检查会触发哪些已启用规则。", 14, SECONDARY))
        gap(body, 16)
        val sender = field("发信号码，例如 95588；留空表示未知号码", false, "发信号码")
        body.addView(sender); gap(body, 12)
        val input = field("在这里输入一条短信…", true, "待检查的短信正文")
        body.addView(input); gap(body, 16)
        val result = label("", 14, ACCENT).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        body.addView(result)
        val dialog = AlertDialog.Builder(this@TestsActivity).setTitle("短信匹配检查").setView(body)
            .setNegativeButton("关闭", null).setPositiveButton("检查匹配", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val state = store.state()
                val subject = SmsSubject(body = input.text.toString(), sender = sender.text.toString().trim())
                val verdicts = RuleMatcher.diagnose(state.rules.filter { it.enabled }, subject)
                val hits = verdicts.filter { it.match == RuleMatch.MATCHED }
                result.text = when {
                    state.rules.none { it.enabled } -> "没有启用的规则"
                    hits.isNotEmpty() -> "触发 ${hits.size} 条规则：\n" + hits.joinToString("\n") { "· ${it.rule.name}" } +
                        "\n\n动作：" + hits.flatMap { it.rule.actions }.joinToString(" · ") { it.type.label }
                    else -> "不会触发\n" + verdicts.joinToString("\n") { "· ${it.rule.name}：${it.match.description}" }
                }
                result.setTextColor(if (hits.isNotEmpty()) ACCENT else SECONDARY)
            }
        }
        dialog.show()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
