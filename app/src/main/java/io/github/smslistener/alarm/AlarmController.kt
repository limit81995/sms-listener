package io.github.smslistener.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.smslistener.data.AppStore

object AlarmController {
    private val generation = java.util.concurrent.atomic.AtomicLong(0)
    internal fun currentGeneration(): Long = generation.get()

    fun execute(context: Context, commands: List<AlarmCommand>, expectedGeneration: Long = currentGeneration()): Boolean {
        if (commands.isEmpty() || expectedGeneration != currentGeneration()) return false
        return try {
            val intent = AlarmCommandTransport.put(Intent(context, AlarmService::class.java), commands)
                .putExtra(AlarmCommandTransport.GENERATION, expectedGeneration)
                .putExtra(AlarmService.FROM_SMS, commands.all { it.fromSms })
                .putExtra(AlarmService.REASON, commands.first().reason.ifBlank { "规则提醒任务" })
            context.startForegroundService(intent)
            true
        } catch (e: RuntimeException) {
            runCatching { AppStore(context).log("规则动作提交失败，请检查后台运行设置", e) }
            AlarmNotifications(context).failure("规则动作提交失败，请打开应用查看日志")
            false
        }
    }
    fun start(context: Context, spec: AlarmSpec, reason: String, fromSms: Boolean = false): Boolean {
        if (!spec.isLocal) return false
        return try {
            context.startForegroundService(Intent(context, AlarmService::class.java).apply {
                putExtra(AlarmCommandTransport.GENERATION, currentGeneration())
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
        // Also invalidate already-issued START intents that have not reached the service yet.
        generation.incrementAndGet()
        if (!context.stopService(Intent(context, AlarmService::class.java))) {
            AlarmNotifications(context).clear()
        }
    }
}

class StopAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { AlarmController.stop(context) }
}
