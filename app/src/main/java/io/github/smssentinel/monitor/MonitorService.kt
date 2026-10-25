package io.github.smssentinel.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.smssentinel.R
import io.github.smssentinel.data.AppStore
import io.github.smssentinel.sms.SmsDatabaseWatcher
import io.github.smssentinel.ui.MainActivity
import java.util.concurrent.Executors

/**
 * Low-priority foreground service that keeps the process alive while monitoring is
 * on. Without it, vendor ROMs freeze the app and SMS_RECEIVED never reaches it.
 * It does no polling and no work of its own.
 */
class MonitorService : Service() {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SmsSentinel-Watch").apply { isDaemon = true }
    }
    private var observer: ContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        // Vendor ROMs may drop SMS_RECEIVED for background apps; the provider is reliable
        // as long as this service keeps the process alive.
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                worker.execute { SmsDatabaseWatcher.poll(this@MonitorService) }
            }
        }.also { contentResolver.registerContentObserver(Uri.parse("content://sms"), true, it) }
        // Screen-on fires before unlock, so picking up the phone is enough.
        runCatching {
            registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    worker.execute { SmsDatabaseWatcher.poll(this@MonitorService) }
                }
            }, android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_ON)
                addAction(android.content.Intent.ACTION_USER_PRESENT)
            })
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "监听状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "监听开启期间显示，用于保持后台接收短信"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notification)
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("短信哨兵正在守护")
            .setContentText("监听中，重要短信到达时会按规则提醒")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(Notification.PRIORITY_MIN)
            .build()
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "monitor_v1"
        private const val ID = 21

        /** Safe to call from a foreground Activity; never throws. */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, MonitorService::class.java))
            } catch (e: RuntimeException) {
                AppStore(context).log("后台守护服务启动失败，系统可能限制了后台启动", e)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MonitorService::class.java)) }
        }
    }
}
