package io.github.smslistener.alarm

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import io.github.smslistener.R
import io.github.smslistener.data.AppStore

class AlarmSound(private val context: Context, private val soundUri: String = "", private val onFailure: () -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val store = AppStore(context)
    private var player: MediaPlayer? = null
    private var pausedForFocus = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AlarmVibration.audioAttributes())
        .setOnAudioFocusChangeListener { change ->
            try {
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        player?.takeIf { it.isPlaying }?.pause()
                        pausedForFocus = true
                        store.log("系统收回音频焦点，响铃已暂停")
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> if (pausedForFocus) {
                        player?.start()
                        pausedForFocus = false
                        store.log("音频焦点恢复，继续响铃")
                    }
                }
            } catch (e: RuntimeException) { store.log("恢复响铃失败", e); onFailure() }
        }.build()

    fun start(): Boolean {
        try {
            if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                store.log("无法获取闹钟音频焦点，未播放铃声")
                return false
            }
            player = MediaPlayer().also { media ->
                media.setAudioAttributes(AlarmVibration.audioAttributes())
                if (soundUri.isNotBlank()) {
                    // User-picked file; fall back to the built-in tone if it cannot be opened.
                    runCatching { media.setDataSource(context, android.net.Uri.parse(soundUri)) }
                        .onFailure {
                            context.resources.openRawResourceFd(R.raw.alarm_tone).use { fd ->
                                media.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                            }
                        }
                } else {
                    context.resources.openRawResourceFd(R.raw.alarm_tone).use { fd ->
                        media.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    }
                }
                media.isLooping = true
                media.setOnErrorListener { _, what, extra ->
                    store.log("铃声播放错误（$what / $extra）")
                    onFailure()
                    true
                }
                media.prepare() // A small bundled local WAV; no network or external URI.
                media.start()
            }
            store.log(if (soundUri.isNotBlank()) "已开始播放自定义铃声" else "已开始循环铃声；使用系统闹钟音量")
            return true
        } catch (e: Exception) {
            store.log("无法开始响铃", e)
            stop()
            return false
        }
    }

    fun stop() {
        player?.release()
        player = null
        audio.abandonAudioFocusRequest(focus)
        pausedForFocus = false
    }
}
