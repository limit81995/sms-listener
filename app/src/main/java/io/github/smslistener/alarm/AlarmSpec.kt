package io.github.smslistener.alarm

/** Runtime parameters for one alarm run, assembled from the matched rules' local actions. */
data class AlarmSpec(
    val sound: Boolean,
    val vibration: Boolean,
    val flash: Boolean,
    /** 0 means keep alarming until the user stops it. */
    val durationSeconds: Int,
    /** Milliseconds before the alarm actually starts. */
    val delayMs: Long = 0,
    /** Repeat count for the pattern; 0 means unlimited. */
    val repeatCount: Int = 0,
    /** Custom audio URI for RING; blank uses the built-in tone. */
    val soundUri: String = "",
) {
    val isLocal: Boolean get() = sound || vibration || flash

    fun summary(): String = listOfNotNull(
        if (sound) "响铃" else null,
        if (vibration) "振动" else null,
        if (flash) "闪光灯" else null,
    ).joinToString(" + ") + " · " + if (durationSeconds == 0) "手动停止" else "${durationSeconds} 秒"
}
