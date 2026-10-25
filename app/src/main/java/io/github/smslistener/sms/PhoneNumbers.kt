package io.github.smslistener.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/** Best-effort read of the SIM number that received a message. Empty when unavailable. */
object PhoneNumbers {
    fun forSubscription(context: Context, subscriptionId: Int): String {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_PHONE_NUMBERS
        else Manifest.permission.READ_PHONE_STATE
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return ""
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                val manager = context.getSystemService(SubscriptionManager::class.java)
                val id = if (subscriptionId >= 0) subscriptionId
                else SubscriptionManager.getDefaultSubscriptionId()
                manager?.getPhoneNumber(id).orEmpty()
            } else {
                @Suppress("DEPRECATION")
                val manager = context.getSystemService(TelephonyManager::class.java)
                @Suppress("DEPRECATION")
                if (subscriptionId >= 0) manager.createForSubscriptionId(subscriptionId).line1Number.orEmpty()
                else manager.line1Number.orEmpty()
            }
        } catch (_: RuntimeException) {
            ""
        }
    }
}
