package io.github.smssentinel.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.smssentinel.R
import io.github.smssentinel.data.AppStore
import io.github.smssentinel.ui.MainActivity

class AlarmNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel(ACTIVE, "正在报警", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "报警期间的状态和停止按钮；响铃、循环振动由报警服务控制"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        manager.createNotificationChannel(NotificationChannel(ERROR, "报警异常", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "后台报警启动失败时的提醒"
        })
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun active(reason: String): Notification {
        val stop = PendingIntent.getBroadcast(context, 1, Intent(context, StopAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context, ACTIVE)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(reason)
            .setContentText("点击“停止报警”结束响铃和振动")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, "停止报警", stop).build())
            .build()
    }

    fun failure(message: String) {
        try {
            manager.notify(FAILURE_ID, Notification.Builder(context, ERROR)
                .setSmallIcon(R.drawable.ic_alarm).setContentTitle("短信哨兵：报警未能完整执行")
                .setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(openApp()).setAutoCancel(true).build())
        } catch (e: RuntimeException) { AppStore(context).log("异常提醒通知无法显示", e) }
    }

    fun clearFailure() { manager.cancel(FAILURE_ID) }
    fun clear() { manager.cancel(ACTIVE_ID); clearFailure() }

    companion object {
        const val ACTIVE_ID = 10
        const val FAILURE_ID = 11
        const val ACTIVE = "active_alarm_v1"
        private const val ERROR = "alarm_errors_v1"
    }
}
