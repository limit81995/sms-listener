package io.github.smssentinel.core

import org.junit.Assert.*
import org.junit.Test

class MessageFingerprintTest {
    @Test fun sameBroadcastGetsSameFingerprint() {
        assertEquals(MessageFingerprint.of("123", "告警", 100, 1), MessageFingerprint.of("123", "告警", 100, 1))
    }
    @Test fun repeatedContentAtAnotherTimeIsANewSms() {
        assertNotEquals(MessageFingerprint.of("123", "告警", 100, 1), MessageFingerprint.of("123", "告警", 101, 1))
    }
    @Test fun differentSimOrSenderDoesNotDeduplicate() {
        val original = MessageFingerprint.of("123", "告警", 100, 1)
        assertNotEquals(original, MessageFingerprint.of("123", "告警", 100, 2))
        assertNotEquals(original, MessageFingerprint.of("124", "告警", 100, 1))
    }
    @Test fun fieldBoundariesCannotCollide() {
        assertNotEquals(MessageFingerprint.of("12", "3", 100, 1), MessageFingerprint.of("1", "23", 100, 1))
    }
}
