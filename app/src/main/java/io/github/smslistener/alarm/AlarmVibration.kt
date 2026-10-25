package io.github.smslistener.alarm

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object AlarmVibration {
    @Suppress("DEPRECATION")
    fun vibrator(context: Context): Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else context.getSystemService(Vibrator::class.java)

    /**
     * [repeatCount] 0 keeps the alarm going until cancelled; a positive value plays
     * the on/off pattern exactly that many times.
     */
    fun start(context: Context, repeatCount: Int = 0): Boolean {
        val vibrator = vibrator(context)
        if (!vibrator.hasVibrator()) return false
        // Native waveform: 800 ms on, 400 ms off. System default amplitude works with
        // motors that cannot control amplitude as well as those that can.
        val times = if (repeatCount in 1..999) repeatCount else 0
        val effect = if (times == 0) {
            VibrationEffect.createWaveform(
                longArrayOf(0, 800, 400), intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0), 0,
            )
        } else {
            val timings = LongArray(times * 2 + 1).also { arr ->
                arr[0] = 0
                for (i in 0 until times) {
                    arr[i * 2 + 1] = 800
                    arr[i * 2 + 2] = 400
                }
            }
            val amplitudes = IntArray(timings.size).also { arr ->
                for (i in arr.indices) arr[i] = if (i % 2 == 1) VibrationEffect.DEFAULT_AMPLITUDE else 0
            }
            VibrationEffect.createWaveform(timings, amplitudes, -1)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build())
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, audioAttributes())
        }
        return true // Submitted to Android; this does not prove physical motor movement.
    }

    fun stop(context: Context) { vibrator(context).cancel() }

    fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
}
