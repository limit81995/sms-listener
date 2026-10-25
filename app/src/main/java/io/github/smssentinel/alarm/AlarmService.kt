package io.github.smssentinel.alarm

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import io.github.smssentinel.action.AlarmFlash
import io.github.smssentinel.data.AppStore

class AlarmService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var sound: AlarmSound? = null
    private var flash: AlarmFlash? = null
    private lateinit var store: AppStore
    private lateinit var notifications: AlarmNotifications
    private var started = false

    override fun onCreate() {
        super.onCreate()
        store = AppStore(this)
        notifications = AlarmNotifications(this)
    }

    @SuppressLint("WakelockTimeout") // User explicitly requests an alarm lasting until acknowledged.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = notifications.active(intent?.getStringExtra(REASON) ?: "短信规则报警")
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(AlarmNotifications.ACTIVE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(AlarmNotifications.ACTIVE_ID, notification)
            if (intent?.getBooleanExtra(FROM_SMS, false) == true && !store.state().enabled) {
                store.log("监听已暂停，取消排队中的短信报警")
                if (!started) stopSelf()
                return START_NOT_STICKY
            }
            if (started) {
                store.log("报警进行中：合并新触发，保持原停止时间")
                return START_NOT_STICKY
            }
            val spec = AlarmSpec(
                sound = intent?.getBooleanExtra(SOUND, false) ?: false,
                vibration = intent?.getBooleanExtra(VIBRATION, false) ?: false,
                flash = intent?.getBooleanExtra(FLASH, false) ?: false,
                durationSeconds = (intent?.getIntExtra(DURATION, 0) ?: 0).coerceIn(0, 3600),
                delayMs = (intent?.getLongExtra(DELAY, 0L) ?: 0L).coerceIn(0, 600_000),
                repeatCount = (intent?.getIntExtra(REPEAT, 0) ?: 0).coerceIn(0, 999),
                soundUri = intent?.getStringExtra(SOUND_URI).orEmpty(),
            )
            require(spec.isLocal)
            notifications.clearFailure()
            started = true
            active = true
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsSentinel:ActiveAlarm").apply {
                    setReferenceCounted(false)
                    // Explicitly chosen indefinite alarm. Always released in onDestroy.
                    acquire()
                }
            if (spec.delayMs > 0) {
                store.log("报警将在 ${spec.delayMs} 毫秒后开始")
                handler.postDelayed({ beginActions(spec) }, spec.delayMs)
            } else {
                beginActions(spec)
            }
        } catch (e: Exception) {
            store.log("报警服务执行失败", e)
            notifications.failure("报警执行失败，请查看日志")
            stopSelf()
        }
        // Do not restart an unacknowledged or obsolete alarm after the process was killed.
        return START_NOT_STICKY
    }

    /** Starts the actual alarm outputs; split out so a configured delay can wrap it. */
    private fun beginActions(spec: AlarmSpec) {
        if (!active) return
        var vibrationSubmitted = false
        if (spec.vibration) {
            try {
                vibrationSubmitted = AlarmVibration.start(this, spec.repeatCount)
                store.log(if (vibrationSubmitted) "已提交闹钟用途的循环振动请求" else "设备报告没有振动器")
            } catch (e: RuntimeException) {
                store.log("循环振动请求失败", e)
            }
        }
        var soundStarted = false
        if (spec.sound) {
            sound = AlarmSound(this, spec.soundUri) {
                notifications.failure("铃声发生错误，请查看日志")
            }
            soundStarted = sound!!.start()
        }
        var flashStarted = false
        if (spec.flash) {
            flash = AlarmFlash(this)
            flashStarted = flash!!.start()
            store.log(if (flashStarted) "闪光灯闪烁已启动" else "没有可用闪光灯或缺少相机权限")
        }
        if ((spec.vibration && !vibrationSubmitted) || (spec.sound && !soundStarted)) {
            notifications.failure("部分报警动作未能启动，请查看日志")
        }
        if (!vibrationSubmitted && !soundStarted && !flashStarted) {
            store.log("没有任何本机动作成功启动")
            stopSelf()
            return
        }
        store.log(
            if (spec.durationSeconds == 0) "报警运行中，等待手动停止"
            else "报警将在 ${spec.durationSeconds} 秒后停止",
        )
        if (spec.durationSeconds > 0) handler.postDelayed({
            store.log("已达到设置的报警时长")
            stopSelf()
        }, spec.durationSeconds * 1000L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { AlarmVibration.stop(this) }
        runCatching { sound?.stop() }
        runCatching { flash?.stop() }
        sound = null
        flash = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        active = false
        if (started) store.log("报警已停止，已释放铃声、振动与闪光灯资源")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val SOUND = "alarm_sound"
        const val VIBRATION = "alarm_vibration"
        const val FLASH = "alarm_flash"
        const val DURATION = "alarm_duration"
        const val DELAY = "alarm_delay"
        const val REPEAT = "alarm_repeat"
        const val SOUND_URI = "alarm_sound_uri"
        const val REASON = "alarm_reason"
        const val FROM_SMS = "from_sms"
        @Volatile var active = false
            private set
    }
}
