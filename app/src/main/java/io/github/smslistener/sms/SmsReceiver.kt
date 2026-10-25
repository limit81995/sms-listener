package io.github.smslistener.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import io.github.smslistener.data.AppStore
import java.util.concurrent.Executors

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val pending = goAsync()
        executor.execute {
            val store = AppStore(context)
            try {
                SmsProcessor(context).process(intent)
            } catch (e: Exception) {
                store.log("处理短信失败", e)
            } finally { pending.finish() }
        }
    }

    private companion object { val executor = Executors.newSingleThreadExecutor() }
}
