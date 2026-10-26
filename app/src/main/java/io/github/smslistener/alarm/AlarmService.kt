package io.github.smslistener.alarm

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import io.github.smslistener.action.AlarmFlash
import io.github.smslistener.core.ActionType
import io.github.smslistener.data.AppStore

/** One hardware owner, with independent logical contributions for every rule and channel. */
class AlarmService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val engine = AlarmSessionEngine(onStopApplied = ActionCancellationTracker::recordStop)
    private var wakeLock: PowerManager.WakeLock? = null
    private var sound: AlarmSound? = null
    private var soundUri: String? = null
    private var flash: AlarmFlash? = null
    private var vibrationOn = false
    private var flashOn = false
    private var destroyed = false
    private var lastStartId = 0
    private var generation = AlarmController.currentGeneration()
    private lateinit var store: AppStore
    private lateinit var notifications: AlarmNotifications
    private val tick = Runnable {
        if (!destroyed) runCatching { renderAndSchedule() }.onFailure(::runtimeFailure)
    }

    override fun onCreate() {
        super.onCreate()
        store = AppStore(this)
        notifications = AlarmNotifications(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        invalidateOldGeneration()
        val commandGeneration = intent?.getLongExtra(AlarmCommandTransport.GENERATION, AlarmController.currentGeneration())
        if (intent == null || commandGeneration != AlarmController.currentGeneration()) {
            if (!engine.hasWork()) stopSelf(startId)
            return START_NOT_STICKY
        }
        val commands = try { AlarmCommandTransport.read(intent) } catch (error: Exception) {
            log("本机动作参数无效，未执行该批动作", error)
            if (!engine.hasWork()) stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            val notification = notifications.active(intent.getStringExtra(REASON) ?: "规则提醒任务")
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(AlarmNotifications.ACTIVE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(AlarmNotifications.ACTIVE_ID, notification)
            invalidateOldGeneration()
            if (commandGeneration != AlarmController.currentGeneration()) {
                renderAndSchedule()
                return START_NOT_STICKY
            }
            // Due STOPs must publish their epochs before a late queued START is admitted.
            val receivedAt = SystemClock.uptimeMillis()
            engine.advance(receivedAt)
            val monitoring = store.state().enabled
            val allowed = commands.filter { (monitoring || !it.fromSms) && ActionCancellationTracker.accepts(it) }
            if (allowed.size != commands.size) log("已取消暂停监听或停止指令之前排队的本机动作")
            if (allowed.isNotEmpty()) {
                engine.submit(allowed, receivedAt)
                notifications.clearFailure()
                log("已接收 " + allowed.size + " 个本机动作，按规则和通道独立处理")
            }
            renderAndSchedule()
        } catch (error: Exception) { runtimeFailure(error) }
        // No replay of stale reminders or stop commands after process death.
        return START_NOT_STICKY
    }

    @SuppressLint("WakelockTimeout") // Explicit reminder tasks may run until stopped; every idle/destroy path releases it.
    private fun ensureAwake() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsListener:ActiveAlarm").apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    /** Apply a whole batch before touching hardware: START then STOP never emits a brief pulse. */
    private fun renderAndSchedule() {
        invalidateOldGeneration()
        handler.removeCallbacks(tick)
        engine.advance(SystemClock.uptimeMillis())
        if (engine.hasWork()) ensureAwake()
        var settled = false
        repeat(64) {
            if (settled) return@repeat
            val now = SystemClock.uptimeMillis()
            engine.advance(now)
            val outputs = engine.activeOutputs()
            var changed = false
            val vibrates = outputs.filter { it.type == ActionType.VIBRATE }
            if (vibrates.isEmpty() && vibrationOn) {
                runCatching { AlarmVibration.stop(this) }.onFailure { reportOutputFailure("振动停止请求失败，请手动停止；其他通道保持运行") }
                vibrationOn = false
                log("振动已停止；其他通道独立保留")
            } else if (vibrates.isNotEmpty() && !vibrationOn) {
                // The engine owns per-rule repeat limits; hardware loops while any contributor remains.
                vibrationOn = runCatching { AlarmVibration.start(this, 0) }.getOrDefault(false)
                if (vibrationOn) log("已提交闹钟用途的循环振动请求")
                else {
                    vibrates.forEach { engine.removeOutput(it.key) }
                    reportOutputFailure("振动未成功启动，请检查设备与后台设置")
                    changed = true
                }
            }
            val flashes = outputs.filter { it.type == ActionType.FLASH }
            if (flashes.isEmpty() && flashOn) {
                runCatching { flash?.stop() }.onFailure { reportOutputFailure("闪光灯停止请求失败，请手动停止") }
                flash = null; flashOn = false
                log("闪光灯已停止；其他通道独立保留")
            } else if (flashes.isNotEmpty() && !flashOn) {
                flash = AlarmFlash(this)
                flashOn = runCatching { flash!!.start() }.getOrDefault(false)
                if (flashOn) log("闪光灯闪烁已启动")
                else {
                    runCatching { flash?.stop() }; flash = null
                    flashes.forEach { engine.removeOutput(it.key) }
                    reportOutputFailure("闪光灯未成功启动，请检查相机权限")
                    changed = true
                }
            }
            val rings = outputs.filter { it.type == ActionType.RING }
            val wanted = rings.firstOrNull()
            if (wanted == null) {
                if (sound != null) { stopSound(); log("响铃已停止；其他通道独立保留") }
            } else {
                if (sound == null || soundUri != wanted.soundUri) {
                    stopSound()
                    lateinit var candidate: AlarmSound
                    candidate = AlarmSound(this, wanted.soundUri) {
                        handler.post {
                            if (!destroyed && sound === candidate) {
                                engine.activeOutputs().filter { it.type == ActionType.RING && it.soundUri == wanted.soundUri }
                                    .forEach { engine.removeOutput(it.key) }
                                stopSound()
                                reportOutputFailure("响铃发生错误，其他提醒继续运行")
                                runCatching { renderAndSchedule() }.onFailure(::runtimeFailure)
                            }
                        }
                    }
                    sound = candidate
                    soundUri = wanted.soundUri
                    if (!runCatching { candidate.start() }.getOrDefault(false)) {
                        rings.filter { it.soundUri == wanted.soundUri }.forEach { engine.removeOutput(it.key) }
                        stopSound()
                        reportOutputFailure("响铃未成功启动，其他提醒继续运行")
                        changed = true
                    }
                }
                sound?.let { player ->
                    val period = player.durationMillis
                    if (period > 0) {
                        val before = engine.activeOutputs().map { it.key }
                        engine.setSoundPeriod(wanted.soundUri, period, SystemClock.uptimeMillis())
                        if (before != engine.activeOutputs().map { it.key }) changed = true
                    } else {
                        val limited = engine.activeOutputs().filter {
                            it.type == ActionType.RING && it.soundUri == wanted.soundUri && it.repeatCount > 0
                        }
                        if (limited.isNotEmpty()) {
                            limited.forEach { engine.removeOutput(it.key) }
                            reportOutputFailure("音频时长未读取，限次响铃已取消，请重新选择音乐")
                            changed = true
                        }
                    }
                }
            }
            settled = !changed
        }
        val nextActive = engine.hasWork()
        val statusChanged = active != nextActive
        active = nextActive
        if (statusChanged) log(if (active) "本机提醒任务运行中" else "本机提醒任务已停止")
        if (!active) {
            stopOutputs()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(lastStartId)
        } else if (!settled) {
            handler.post(tick)
        } else {
            engine.nextWakeAt()?.let { deadline ->
                handler.postDelayed(tick, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0))
            }
        }
    }

    private fun invalidateOldGeneration() {
        val current = AlarmController.currentGeneration()
        if (generation == current) return
        handler.removeCallbacks(tick)
        engine.clear()
        stopOutputs()
        releaseWakeLock()
        val wasActive = active
        active = false
        generation = current
        if (wasActive) log("旧代际提醒任务已清除")
    }

    private fun stopSound() {
        runCatching { sound?.stop() }
        sound = null
        soundUri = null
    }

    private fun stopOutputs() {
        runCatching { AlarmVibration.stop(this) }
        vibrationOn = false
        stopSound()
        runCatching { flash?.stop() }
        flash = null
        flashOn = false
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
    }

    private fun log(message: String, error: Throwable? = null) {
        runCatching { store.log(message, error) }
    }

    private fun reportOutputFailure(message: String) {
        log(message)
        runCatching { notifications.failure(message) }
    }

    private fun runtimeFailure(error: Throwable) {
        log("报警服务执行失败", error)
        runCatching { notifications.failure("报警执行失败，请查看日志") }
        handler.removeCallbacks(tick)
        engine.clear()
        stopOutputs()
        releaseWakeLock()
        active = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        engine.clear()
        stopOutputs()
        releaseWakeLock()
        active = false
        log("报警任务已结束，已释放对应资源")
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
