package io.github.smslistener.core

import java.nio.ByteBuffer
import java.security.MessageDigest

object MessageFingerprint {
    /** Timestamp prevents a new SMS with the same text from being mistaken for a duplicate. */
    fun of(sender: String, body: String, timestamp: Long, subscription: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in listOf(sender, body, timestamp.toString(), subscription.toString())) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
