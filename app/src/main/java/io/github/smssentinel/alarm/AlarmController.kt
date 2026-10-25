package io.github.smssentinel.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.smssentinel.data.AppStore

object AlarmController {
    fun start(context: Context, spec: AlarmSpec, reason: String, fromSms: Boolean = false): Boolean {
        if (!spec.isLocal) return false
        return try {
            context.startForegroundService(Intent(context, AlarmService::class.java).apply {
                putExtra(AlarmService.SOUND, spec.sound)
                putExtra(AlarmService.VIBRATION, spec.vibration)
                putExtra(AlarmService.FLASH, spec.flash)
                putExtra(AlarmService.DURATION, spec.durationSeconds)
                putExtra(AlarmService.DELAY, spec.delayMs)
                putExtra(AlarmService.REPEAT, spec.repeatCount)
                putExtra(AlarmService.SOUND_URI, spec.soundUri)
                putExtra(AlarmService.REASON, reason)
                putExtra(AlarmService.FROM_SMS, fromSms)
            })
            true
        } catch (e: RuntimeException) {
            AppStore(context).log("报警服务启动被阻止，请检查后台运行和电池设置", e)
            AlarmNotifications(context).failure("系统阻止了后台报警，请打开应用查看日志和后台设置")
            false
        }
    }

    fun stop(context: Context) {
        if (!context.stopService(Intent(context, AlarmService::class.java))) {
            AlarmNotifications(context).clear()
        }
    }
}

class StopAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { AlarmController.stop(context) }
}
