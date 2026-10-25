package io.github.smssentinel.action

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper

/** Blinks the rear torch. Fails quietly when the device has no flash or permission is missing. */
class AlarmFlash(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(CameraManager::class.java)
    private var cameraId: String? = null
    private var lit = false

    private val blink = object : Runnable {
        override fun run() {
            val id = cameraId ?: return
            lit = !lit
            runCatching { manager.setTorchMode(id, lit) }
            handler.postDelayed(this, if (lit) 700 else 300)
        }
    }

    fun start(): Boolean {
        if (cameraId != null) return true
        val id = runCatching {
            manager.cameraIdList.firstOrNull { cameraIdCandidate ->
                manager.getCameraCharacteristics(cameraIdCandidate)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: return false
        cameraId = id
        lit = false
        handler.post(blink)
        return true
    }

    fun stop() {
        handler.removeCallbacks(blink)
        val id = cameraId ?: return
        runCatching { manager.setTorchMode(id, false) }
        cameraId = null
        lit = false
    }
}
