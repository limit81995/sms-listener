package io.github.smslistener.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.smslistener.core.TriggerSource

/**
 * Dynamically registered by MonitorService. The no-arg manifest instance is inert:
 * SCREEN_OFF is a registered-receiver broadcast, and USER_PRESENT must not be
 * delivered twice via both static and dynamic receivers. No goAsync or timers here.
 */
class WakeReceiver(private val onEvent: ((String) -> Unit)? = null) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_SCREEN_ON || sourceFor(action) != null) onEvent?.invoke(action)
    }

    internal companion object {
        fun sourceFor(action: String): TriggerSource? = when (action) {
            Intent.ACTION_USER_PRESENT -> TriggerSource.UNLOCK
            Intent.ACTION_SCREEN_OFF -> TriggerSource.LOCK
            else -> null
        }

        fun shouldCheckSms(action: String): Boolean =
            action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_USER_PRESENT
    }
}
