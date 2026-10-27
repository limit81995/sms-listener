package io.github.smslistener.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.smslistener.R
import io.github.smslistener.action.ActionExecutor
import io.github.smslistener.alarm.AlarmController
import io.github.smslistener.alarm.ActionCancellationSnapshot
import io.github.smslistener.alarm.ActionCancellationTracker
import io.github.smslistener.core.AlarmRule
import io.github.smslistener.core.MatchRule
import io.github.smslistener.core.RuleMatcher
import io.github.smslistener.core.SentinelState
import io.github.smslistener.core.TriggerSource
import io.github.smslistener.data.AppStore
import io.github.smslistener.data.ConfigFileManager
import io.github.smslistener.sms.SmsDatabaseWatcher
import io.github.smslistener.ui.MainActivity
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Foreground, event-driven monitor. All provider reads and dispatch run off the main thread. */
class MonitorService : Service() {
    private val worker = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "SmsListener-Watch").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private lateinit var store: AppStore
    private lateinit var triggers: ScreenTriggerDispatcher
    private var observer: ContentObserver? = null
    private var wakeReceiver: WakeReceiver? = null
    @Volatile private var destroyed = false
    private val stateListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // Ignore logs/cursors: those writes must never create a polling feedback loop.
        if (key == "state_v2" || key == null) submit("更新触发任务失败") { triggers.cancelInvalid() }
    }

    override fun onCreate() {
        super.onCreate()
        store = AppStore(this)
        ConfigFileManager.opened(this)
        triggers = ScreenTriggerDispatcher(
            readState = { store.state() },
            schedule = { delay, task ->
                val future = worker.schedule({
                    if (!destroyed) guarded("屏幕触发执行失败", task)
                }, delay, TimeUnit.MILLISECONDS)
                val cancel: () -> Unit = { future.cancel(false); Unit }
                cancel
            },
            dispatch = { rule -> ActionExecutor.dispatchTrigger(this, rule) },
            onError = { error -> logFailure("屏幕触发执行失败", error) },
            capture = { ruleId -> ActionCancellationTracker.capture(ruleId, AlarmController.currentGeneration()) },
            dispatchPending = { rule, snapshot -> ActionExecutor.dispatchTrigger(this, rule, snapshot) },
        )
        guarded("配置观察器注册失败") { store.observe(stateListener) }
        guarded("短信观察器注册失败") {
            val candidate = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    submit("短信补查失败") { SmsDatabaseWatcher.poll(this@MonitorService) }
                }
            }
            contentResolver.registerContentObserver(Uri.parse("content://sms"), true, candidate)
            observer = candidate
        }
        guarded("屏幕事件注册失败") {
            val candidate = WakeReceiver { action ->
                submit("屏幕事件处理失败") {
                    guarded("屏幕触发调度失败") {
                        WakeReceiver.sourceFor(action)?.let { triggers.onEvent(it) }
                    }
                    // Exactly one catch-up query per screen-on/unlock event; never a timer loop.
                    if (WakeReceiver.shouldCheckSms(action)) {
                        guarded("短信补查失败") { SmsDatabaseWatcher.poll(this@MonitorService) }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(candidate, filter, Context.RECEIVER_EXPORTED)
            else registerReceiver(candidate, filter)
            wakeReceiver = candidate
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

    private fun submit(message: String, task: () -> Unit) {
        if (destroyed) return
        guarded(message) {
            worker.execute { if (!destroyed) guarded(message, task) }
        }
    }

    private fun guarded(message: String, task: () -> Unit) {
        try { task() } catch (error: Exception) { logFailure(message, error) }
    }

    private fun logFailure(message: String, error: Throwable) {
        // Logging/storage failures must not take down the service either.
        runCatching { store.log(message, error) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ID, notification)
            }
        } catch (error: RuntimeException) {
            logFailure("后台守护服务启动失败", error)
            triggers.close()
            stopSelf()
            return START_NOT_STICKY
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
            .setContentText("监听中，重要事件到达时会按规则提醒")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(Notification.PRIORITY_MIN)
            .build()
    }

    override fun onDestroy() {
        destroyed = true
        triggers.close()
        guarded("配置观察器注销失败") { store.removeObserver(stateListener) }
        observer?.let { registered ->
            guarded("短信观察器注销失败") { contentResolver.unregisterContentObserver(registered) }
        }
        observer = null
        wakeReceiver?.let { registered ->
            guarded("屏幕事件注销失败") { unregisterReceiver(registered) }
        }
        wakeReceiver = null
        worker.shutdownNow()
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
                runCatching { AppStore(context).log("后台守护服务启动失败，系统可能限制了后台启动", e) }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MonitorService::class.java)) }
        }
    }
}

/**
 * Service-owned, cancellable one-shot tasks. Injectable clock/dispatch keeps lifecycle
 * tests deterministic. No state is persisted and a recreated service never replays an event.
 */
internal class ScreenTriggerDispatcher(
    private val readState: () -> SentinelState,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val dispatch: (AlarmRule) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val capture: (String) -> ActionCancellationSnapshot = { ActionCancellationSnapshot(it, 0L, emptyMap()) },
    private val dispatchPending: ((AlarmRule, ActionCancellationSnapshot) -> Unit)? = null,
) {
    private class Pending(val ruleId: String, val conditions: List<MatchRule>, val matched: MatchRule, val cancellation: ActionCancellationSnapshot) {
        var cancel: (() -> Unit)? = null
    }
    private val pending = mutableSetOf<Pending>()
    private var closed = false

    @Synchronized fun onEvent(source: TriggerSource) {
        if (closed || (source != TriggerSource.LOCK && source != TriggerSource.UNLOCK)) return
        val state = readState()
        if (!state.enabled) { cancelAll(); return }
        state.rules.forEach { rule ->
            val match = RuleMatcher.firstMatch(rule, source) ?: return@forEach
            val task = Pending(rule.id, rule.matchRules.toList(), match, capture(rule.id))
            pending += task
            try {
                task.cancel = schedule(match.delayMs) { fire(task) }
            } catch (error: Exception) {
                pending.remove(task)
                report(error)
            }
        }
    }

    private fun currentRule(task: Pending, state: SentinelState): AlarmRule? {
        if (!state.enabled) return null
        val rule = state.rules.firstOrNull { it.id == task.ruleId } ?: return null
        if (rule.matchRules != task.conditions) return null
        return rule.takeIf { RuleMatcher.firstMatch(it, task.matched.source) == task.matched }
    }

    @Synchronized private fun fire(task: Pending) {
        if (!pending.remove(task) || closed) return
        try {
            // Reload immediately before dispatch, including enabled/removed/edited conditions.
            currentRule(task, readState())?.let { rule ->
                if (dispatchPending != null) dispatchPending.invoke(rule, task.cancellation) else dispatch(rule)
            }
        } catch (error: Exception) {
            report(error)
        }
    }

    @Synchronized fun cancelInvalid() {
        if (closed || pending.isEmpty()) return
        val state = readState()
        val invalid = pending.filter { currentRule(it, state) == null }
        invalid.forEach { task -> pending.remove(task); cancel(task) }
    }

    @Synchronized fun close() {
        closed = true
        cancelAll()
    }

    private fun cancelAll() {
        val tasks = pending.toList()
        pending.clear()
        tasks.forEach(::cancel)
    }

    private fun cancel(task: Pending) {
        try { task.cancel?.invoke() } catch (error: Exception) { report(error) }
    }

    private fun report(error: Throwable) { runCatching { onError(error) } }
}
