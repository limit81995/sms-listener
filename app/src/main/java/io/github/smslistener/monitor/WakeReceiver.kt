package io.github.smslistener.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.github.smslistener.action.ActionExecutor
import io.github.smslistener.core.TriggerSource
import io.github.smslistener.data.AppStore
import io.github.smslistener.sms.SmsDatabaseWatcher
import java.util.concurrent.Executors

/**
 * Single entry point for screen state:
 *  - SCREEN_ON / USER_PRESENT also poll the SMS provider (catch-up),
 *  - USER_PRESENT fires UNLOCK rules, SCREEN_OFF fires LOCK rules.
 */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_SCREEN_ON &&
            action != Intent.ACTION_USER_PRESENT &&
            action != Intent.ACTION_SCREEN_OFF
        ) return

        val app = context.applicationContext
        val pending = goAsync()
        executor.execute {
            try {
                if (action != Intent.ACTION_SCREEN_OFF) {
                    runCatching { SmsDatabaseWatcher.poll(app) }
                }
                val source = when (action) {
                    Intent.ACTION_USER_PRESENT -> TriggerSource.UNLOCK
                    Intent.ACTION_SCREEN_OFF -> TriggerSource.LOCK
                    else -> null
                }
                if (source != null) fireRules(app, source)
            } catch (e: Exception) {
                AppStore(app).log("屏幕事件处理失败", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun fireRules(context: Context, source: TriggerSource) {
        val store = AppStore(context)
        val state = store.state()
        if (!state.enabled) return
        val rules = state.rules.filter { it.enabled && it.trigger == source }
        if (rules.isEmpty()) return
        val main = Handler(Looper.getMainLooper())
        rules.forEach { rule ->
            val fire = Runnable {
                runCatching { ActionExecutor.dispatchTrigger(context, rule) }
                    .onFailure { store.log("屏幕触发执行失败：${rule.name}", it) }
            }
            if (rule.triggerDelayMs <= 0) executor.execute(fire)
            else main.postDelayed(fire, rule.triggerDelayMs)
        }
    }

    private companion object {
        val executor = Executors.newSingleThreadExecutor()
    }
}
