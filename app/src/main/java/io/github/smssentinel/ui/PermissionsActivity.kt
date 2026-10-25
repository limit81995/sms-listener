package io.github.smssentinel.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.smssentinel.ui.SentinelIcon.Kind
import io.github.smssentinel.ui.UiKit.Companion.ACCENT
import io.github.smssentinel.ui.UiKit.Companion.AMBER
import io.github.smssentinel.ui.UiKit.Companion.BACKGROUND
import io.github.smssentinel.ui.UiKit.Companion.INK
import io.github.smssentinel.ui.UiKit.Companion.MUTED
import io.github.smssentinel.ui.UiKit.Companion.SECONDARY
import io.github.smssentinel.ui.UiKit.Companion.SOFT

/** Permission and background checks, opened from Settings. */
class PermissionsActivity : Activity() {
    private lateinit var ui: UiKit
    private lateinit var smsPermission: TextView
    private lateinit var readSmsPermission: TextView
    private lateinit var notificationPermission: TextView
    private lateinit var batteryPermission: TextView
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        buildScreen()
    }

    override fun onResume() { super.onResume(); refresh() }

    /**
     * The battery-optimisation prompt is a system dialog, not an Activity, so it
     * dismisses without any lifecycle callback. Window focus returning is the only
     * reliable moment to re-read the flag.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::summary.isInitialized) {
            window.decorView.postDelayed({ refresh() }, 350)
        }
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
        bar.addView(label("运行准备检查", 17, INK, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar)
        divider(root)

        val content = column().apply { setPadding(dp(18), dp(16), dp(18), dp(24)) }
        root.addView(ScrollView(this@PermissionsActivity).apply {
            isFillViewport = true; clipToPadding = false; addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        val card = card(content)
        section(card, "权限检查", "前四项为必需，最后一项可选。")
        summary = label("", 13, SECONDARY)
        card.addView(summary)
        gap(card, 14)
        divider(card)
        smsPermission = actionRow(card, "接收短信", "", Kind.MESSAGE) { requestSmsPermission() }
        divider(card)
        readSmsPermission = actionRow(card, "读取短信", "", Kind.RULES) { requestReadSmsPermission() }
        divider(card)
        batteryPermission = actionRow(card, "电池优化白名单", "", Kind.BATTERY) { openBatterySettings() }
        divider(card)
        actionRow(card, "自启动", "必需 · 在系统设置中开启，并把省电策略设为无限制", Kind.TOOLS) { openAppSettings() }
        divider(card)
        notificationPermission = actionRow(card, "显示通知", "", Kind.BELL) { requestNotificationPermission() }

        gap(content, 12)
        content.addView(label(
            "最容易漏的一项是省电策略：请在系统设置里把本应用的省电策略设为“无限制”，并在最近任务中锁定。" +
                "否则进程会被冻结，短信会延迟到打开应用后才处理。",
            11, MUTED,
        ))
        setContentView(root)
    }

    private fun refresh() {
        if (!::summary.isInitialized) return
        val sms = smsGranted()
        val readSms = readSmsGranted()
        val notifications = Diagnostics.notificationsAllowed(this)
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        // Notifications are optional: without them alarms still ring and vibrate,
        // the only loss is the stop button in the notification shade.
        // Autostart has no readable state, so it is listed as required but cannot be
        // counted; "已检测" makes that explicit.
        val ready = listOf(sms, readSms, battery).count { it }
        summary.text = when (ready) {
            3 -> "3 / 3 项已检测就绪 · 自启动请在系统中确认"
            else -> "$ready / 3 项已检测就绪 · 点击对应的项完成设置"
        }
        summary.setTextColor(if (ready == 3) ACCENT else AMBER)
        updatePermission(smsPermission, if (sms) "已允许" else "待授权 · 用于识别新短信", sms)
        updatePermission(readSmsPermission, if (readSms) "已允许" else "待授权 · 后台收短信的兜底通道", readSms)
        updatePermission(
            notificationPermission,
            if (notifications) "已允许 · 通知栏可停止报警"
            else "可选 · 不开启也能报警，只是没有通知栏的停止按钮",
            notifications,
        )
        updatePermission(
            batteryPermission,
            if (battery) "已加入 · 系统不会冻结后台"
            else "未加入 · 后台可能被冻结，短信会延迟到打开应用才处理",
            battery,
        )
    }

    private fun updatePermission(view: TextView, value: String, ready: Boolean) {
        view.text = value; view.setTextColor(if (ready) ACCENT else AMBER)
        val row = view.parent.parent as android.view.View
        val title = ((view.parent as LinearLayout).getChildAt(0) as TextView).text
        row.contentDescription = "$title，$value"
    }

    private fun smsGranted() = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    private fun readSmsGranted() = checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    private fun requestSmsPermission() {
        if (!smsGranted()) requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), 1) else openAppSettings()
    }
    private fun requestReadSmsPermission() {
        if (!readSmsGranted()) requestPermissions(arrayOf(Manifest.permission.READ_SMS), 3) else openAppSettings()
    }
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        } else {
            launchSetting(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }
    @SuppressLint("BatteryLife")
    private fun openBatterySettings() = launchSetting(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    private fun openAppSettings() = launchSetting(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    private fun launchSetting(intent: Intent) {
        try { startActivity(intent) } catch (_: RuntimeException) {
            android.widget.Toast.makeText(this, "无法打开，请在系统设置中查找短信哨兵", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            if (requestCode == 2 && !Diagnostics.notificationsAllowed(this)) {
                launchSetting(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
            return
        }
        if (requestCode == 2) {
            android.widget.Toast.makeText(this, "通知被拒绝：请在设置页手动开启", android.widget.Toast.LENGTH_LONG).show()
            launchSetting(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } else {
            android.widget.Toast.makeText(this, "尚未授权，可在应用系统设置中开启", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
