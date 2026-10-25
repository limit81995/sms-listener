package io.github.smslistener.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import io.github.smslistener.alarm.AlarmNotifications
import io.github.smslistener.BuildConfig
import io.github.smslistener.alarm.AlarmService
import io.github.smslistener.alarm.AlarmVibration
import io.github.smslistener.data.AppStore

object Diagnostics {
    fun notificationsAllowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            (manager.getNotificationChannel(AlarmNotifications.ACTIVE)?.importance ?: NotificationManager.IMPORTANCE_HIGH) > 0
    }

    fun text(context: Context): String {
        val audio = context.getSystemService(AudioManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val notification = context.getSystemService(NotificationManager::class.java)
        val vibrator = AlarmVibration.vibrator(context)
        fun yn(value: Boolean) = if (value) "是" else "否"
        return """
            短信哨兵 ${BuildConfig.VERSION_NAME}
            设备：${Build.MANUFACTURER} ${Build.MODEL}
            Android：${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}
            系统版本：${Build.DISPLAY}
            短信接收权限：${yn(context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED)}
            报警通知可显示：${yn(notificationsAllowed(context))}
            已开启监听：${yn(AppStore(context).state().enabled)}
            报警服务运行中：${yn(AlarmService.active)}
            振动器存在：${yn(vibrator.hasVibrator())}
            支持振幅控制：${yn(vibrator.hasAmplitudeControl())}
            系统闹钟音量：${audio.getStreamVolume(AudioManager.STREAM_ALARM)} / ${audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)}
            铃声模式：${audio.ringerMode}（0 静音 / 1 振动 / 2 正常）
            勿扰过滤：${notification.currentInterruptionFilter}（1 全部 / 2 优先 / 3 全禁 / 4 闹钟）
            已取消电池优化：${yn(power.isIgnoringBatteryOptimizations(context.packageName))}
            省电模式：${yn(power.isPowerSaveMode)}

            振动请求使用闹钟用途；系统没有提供判断马达是否实际振动的通用回调。
        """.trimIndent()
    }
}
