package io.github.smssentinel.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.smssentinel.data.AppStore

/** Restores the resident monitor after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            -> if (AppStore(context).state().enabled) MonitorService.start(context)
        }
    }
}
