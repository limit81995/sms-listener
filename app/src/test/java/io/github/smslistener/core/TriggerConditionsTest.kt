package io.github.smslistener.core

import org.junit.Assert.*
import org.junit.Test

class TriggerConditionsTest {
    private val sms = MatchRule(id = "sms", body = MatchCondition(MatchMode.CONTAINS, "告警"))
    private val unlock = MatchRule(id = "unlock", source = TriggerSource.UNLOCK, delayMs = 600_000)
    private val lock = MatchRule(id = "lock", source = TriggerSource.LOCK, delayMs = 120)
    private fun rule(vararg groups: MatchRule) = AlarmRule(
        id = "r", name = "混合规则", matchRules = groups.toList(),
        actions = listOf(RuleAction(ActionType.VIBRATE)),
    )

    @Test fun sourcesKeepTheUiContract() {
        assertEquals(listOf("短信", "解锁屏幕", "锁定屏幕", "HTTP(S)"), TriggerSource.entries.map { it.label })
        assertEquals(TriggerSource.SMS, MatchRule().source)
        assertEquals(0L, MatchRule().delayMs)
    }

    @Test fun mixedRuleMatchesEachSourceIndependently() {
        val rule = rule(unlock, sms, lock)
        assertNull(rule.validationError())
        assertEquals(sms, RuleMatcher.firstMatch(rule, TriggerSource.SMS, SmsSubject(body = "告警")))
        assertEquals(unlock, RuleMatcher.firstMatch(rule, TriggerSource.UNLOCK))
        assertEquals(lock, RuleMatcher.firstMatch(rule, TriggerSource.LOCK))
        assertNull(RuleMatcher.firstMatch(rule, TriggerSource.SMS, SmsSubject(body = "普通短信")))
        assertNull(RuleMatcher.firstMatch(rule, TriggerSource.HTTP))
    }

    @Test fun firstSameSourceGroupWinsEvenWhenLaterDelayIsShorter() {
        val later = unlock.copy(id = "second", delayMs = 0)
        assertEquals(unlock, RuleMatcher.firstMatch(rule(sms, unlock, later), TriggerSource.UNLOCK))
    }

    @Test fun firstMatchingSmsGroupWinsWithoutDuplicatingTheRule() {
        val missed = sms.copy(id = "miss", body = MatchCondition(MatchMode.EQUALS, "other"))
        val secondHit = sms.copy(id = "second")
        val rule = rule(lock, missed, sms, secondHit)
        val subject = SmsSubject(body = "告警")
        assertEquals(sms, RuleMatcher.firstMatch(rule, TriggerSource.SMS, subject))
        assertEquals(listOf(rule), RuleMatcher.matched(listOf(rule), subject))
    }

    @Test fun screenGroupNeverMatchesSmsEvenWithRetainedSmsFields() {
        val screen = sms.copy(source = TriggerSource.LOCK)
        assertFalse(screen.matches(SmsSubject(body = "告警")))
        assertFalse(rule(screen).matches(SmsSubject(body = "告警")))
        assertEquals(RuleMatch.CONDITION_MISSED, RuleMatcher.evaluate(rule(screen), SmsSubject(body = "告警")))
        assertEquals(screen, RuleMatcher.firstMatch(rule(screen), TriggerSource.LOCK))
    }

    @Test fun screenDelayAcceptsBothBoundsAndRejectsOutsideValues() {
        for (source in listOf(TriggerSource.UNLOCK, TriggerSource.LOCK)) {
            for (delay in listOf(0L, 1L, 600_000L)) {
                assertNull(MatchRule(source = source, delayMs = delay).validationError())
            }
            for (delay in listOf(-1L, 600_001L, Long.MAX_VALUE)) {
                assertNotNull(MatchRule(source = source, delayMs = delay).validationError())
            }
        }
    }

    @Test fun smsIgnoresScreenDelayAndScreenIgnoresSmsFields() {
        val smsWithDelay = sms.copy(delayMs = -1)
        assertNull(smsWithDelay.validationError())
        assertTrue(smsWithDelay.matches(SmsSubject(body = "告警")))
        val screen = unlock.copy(body = MatchCondition(MatchMode.REGEX, "["))
        assertNull(screen.validationError())
        assertFalse(screen.isEmpty)
        assertTrue(MatchRule().isEmpty)
    }

    @Test fun httpIsRetainedButNotAvailable() {
        val http = MatchRule(source = TriggerSource.HTTP)
        assertTrue(requireNotNull(http.validationError()).contains("尚未开放"))
        assertFalse(http.matches(SmsSubject()))
        assertEquals(RuleMatch.INVALID, RuleMatcher.evaluate(rule(http), TriggerSource.HTTP))
    }

    @Test fun everyGroupIsValidatedEvenWhenAnEarlierGroupWouldMatch() {
        val invalid = sms.copy(id = "invalid", body = MatchCondition(MatchMode.REGEX, "["))
        val invalidRule = rule(unlock, invalid)
        assertTrue(requireNotNull(invalidRule.validationError()).startsWith("触发条件 2"))
        assertNull(RuleMatcher.firstMatch(invalidRule, TriggerSource.UNLOCK))
        assertNotNull(rule(*Array(21) { unlock.copy(id = it.toString()) }).validationError())
    }

    @Test fun disabledRuleNeverMatchesScreenEvents() {
        val rule = rule(unlock).copy(enabled = false)
        assertEquals(RuleMatch.DISABLED, RuleMatcher.evaluate(rule, TriggerSource.UNLOCK))
        assertNull(RuleMatcher.firstMatch(rule, TriggerSource.UNLOCK))
    }
}
