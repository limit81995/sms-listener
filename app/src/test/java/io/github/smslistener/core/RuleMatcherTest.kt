package io.github.smslistener.core

import org.junit.Assert.*
import org.junit.Test

class RuleMatcherTest {
    private fun condition(mode: MatchMode, value: String) = MatchCondition(mode, value)

    private fun rule(
        body: MatchCondition? = null,
        sender: MatchCondition? = null,
        local: MatchCondition? = null,
        enabled: Boolean = true,
        matchRules: List<MatchRule>? = null,
        actions: List<RuleAction> = listOf(RuleAction(ActionType.VIBRATE)),
    ) = AlarmRule(
        name = "测试规则",
        enabled = enabled,
        matchRules = matchRules ?: listOf(MatchRule(body = body, senderNumber = sender, localNumber = local)),
        actions = actions,
    )

    private fun subject(body: String = "", sender: String = "", local: String = "") =
        SmsSubject(body = body, sender = sender, localNumber = local)

    @Test fun regexMatchesChineseAndIgnoresCase() {
        val rule = rule(body = condition(MatchMode.REGEX, "告警|DOWN"))
        assertEquals(RuleMatch.MATCHED, RuleMatcher.evaluate(rule, subject(body = "【运维】主机故障告警")))
        assertEquals(RuleMatch.MATCHED, RuleMatcher.evaluate(rule, subject(body = "server down")))
        assertEquals(RuleMatch.CONDITION_MISSED, RuleMatcher.evaluate(rule, subject(body = "您的订单已送达")))
    }

    @Test fun containsIsLiteralAndCaseInsensitive() {
        val rule = rule(body = condition(MatchMode.CONTAINS, "告警"))
        assertEquals(RuleMatch.MATCHED, RuleMatcher.evaluate(rule, subject(body = "系统告警：磁盘")))
        assertEquals(RuleMatch.MATCHED, RuleMatcher.evaluate(
            rule(body = condition(MatchMode.CONTAINS, "ALERT")), subject(body = "alert level 3"),
        ))
    }

    @Test fun equalsRequiresTheWholeText() {
        val rule = rule(body = condition(MatchMode.EQUALS, "ON"))
        assertEquals(RuleMatch.MATCHED, RuleMatcher.evaluate(rule, subject(body = "on")))
        assertEquals(RuleMatch.CONDITION_MISSED, RuleMatcher.evaluate(rule, subject(body = "ON 是开启")))
    }

    @Test fun senderContainsNormalizesCountryCodeAndSpaces() {
        val rule = rule(sender = condition(MatchMode.CONTAINS, "95588"))
        assertEquals(RuleMatch.MATCHED, RuleMatcher.evaluate(rule, subject(sender = "+86 955-88")))
        assertEquals(RuleMatch.CONDITION_MISSED, RuleMatcher.evaluate(rule, subject(sender = "10086")))
    }

    @Test fun senderEqualsMatchesNormalizedNumbers() {
        val rule = rule(sender = condition(MatchMode.EQUALS, "+8613812345678"))
        assertEquals(RuleMatch.MATCHED, RuleMatcher.evaluate(rule, subject(sender = "13812345678")))
    }

    @Test fun allConditionsInsideOneMatchRuleMustHold() {
        val rule = rule(
            sender = condition(MatchMode.CONTAINS, "95588"),
            body = condition(MatchMode.REGEX, "余额|消费"),
        )
        assertEquals(RuleMatch.MATCHED, RuleMatcher.evaluate(rule, subject(body = "余额变动 100 元", sender = "95588")))
        assertEquals(RuleMatch.CONDITION_MISSED, RuleMatcher.evaluate(rule, subject(body = "余额变动", sender = "10086")))
        assertEquals(RuleMatch.CONDITION_MISSED, RuleMatcher.evaluate(rule, subject(body = "促销", sender = "95588")))
    }

    @Test fun separateMatchRulesAreAlternatives() {
        val rule = rule(matchRules = listOf(
            MatchRule(senderNumber = condition(MatchMode.CONTAINS, "95588")),
            MatchRule(body = condition(MatchMode.REGEX, "验证码")),
        ))
        assertEquals(RuleMatch.MATCHED, RuleMatcher.evaluate(rule, subject(body = "任意", sender = "95588")))
        assertEquals(RuleMatch.MATCHED, RuleMatcher.evaluate(rule, subject(body = "验证码 1234", sender = "10086")))
        assertEquals(RuleMatch.CONDITION_MISSED, RuleMatcher.evaluate(rule, subject(body = "任意", sender = "10086")))
    }

    @Test fun localNumberConditionUsesTheSimNumber() {
        val rule = rule(local = condition(MatchMode.REGEX, "138.*"))
        assertEquals(RuleMatch.MATCHED, RuleMatcher.evaluate(rule, subject(local = "13812345678", sender = "10086")))
        assertEquals(RuleMatch.CONDITION_MISSED, RuleMatcher.evaluate(rule, subject(local = "", sender = "10086")))
    }

    @Test fun disabledRuleNeverMatches() {
        assertEquals(RuleMatch.DISABLED, RuleMatcher.evaluate(
            rule(body = condition(MatchMode.CONTAINS, "告警"), enabled = false), subject(body = "告警"),
        ))
    }

    @Test fun invalidRegexIsReportedRatherThanCrashing() {
        val broken = rule(body = condition(MatchMode.REGEX, "([unclosed"))
        assertNotNull(broken.validationError())
        assertEquals(RuleMatch.INVALID, RuleMatcher.evaluate(broken, subject(body = "任意")))
    }

    @Test fun everyMatchingRuleIsReturned() {
        val rules = listOf(
            rule(body = condition(MatchMode.CONTAINS, "告警")),
            rule(body = condition(MatchMode.CONTAINS, "故障")),
            rule(body = condition(MatchMode.CONTAINS, "促销")),
        )
        val hits = RuleMatcher.matched(rules, subject(body = "告警：磁盘故障"))
        assertEquals(2, hits.size)
    }

    @Test fun ruleWithoutAnyConditionIsUnrestrictedButRuleWithoutGroupsIsRejected() {
        val unrestricted = rule(matchRules = listOf(MatchRule()))
        assertNull(unrestricted.validationError())
        assertEquals(RuleMatch.MATCHED, RuleMatcher.evaluate(unrestricted, subject(body = "任意短信")))
        assertNotNull(rule(matchRules = emptyList()).validationError())
        assertNull(rule(body = condition(MatchMode.CONTAINS, "告警")).validationError())
    }

    @Test fun ruleWithoutActionsIsRejected() {
        assertNotNull(rule(body = condition(MatchMode.CONTAINS, "x"), actions = emptyList()).validationError())
    }

    @Test fun forwardActionRequiresAtLeastOneTarget() {
        assertNotNull(RuleAction(ActionType.FORWARD).validationError())
        assertNull(RuleAction(ActionType.FORWARD, forwardIds = listOf("f1")).validationError())
    }

    @Test fun forwardTargetValidatesAddressAndMethod() {
        assertNotNull(ForwardTarget(type = ForwardType.HTTP, url = "ftp://x").validationError())
        assertNotNull(ForwardTarget(type = ForwardType.FEISHU, url = "").validationError())
        assertNull(ForwardTarget(type = ForwardType.HTTP, url = "http://10.0.0.1:8080", method = "POST").validationError())
    }

    @Test fun localActionDurationValidation() {
        assertNull(RuleAction(ActionType.RING, durationSeconds = 0).validationError())
        assertNotNull(RuleAction(ActionType.RING, durationSeconds = 3601).validationError())
    }

    @Test fun longBodyIsNotTruncated() {
        val rule = rule(body = condition(MatchMode.CONTAINS, "告警"))
        assertEquals(RuleMatch.MATCHED, RuleMatcher.evaluate(rule, subject(body = "正常".repeat(2000) + "告警")))
    }

    @Test fun numberNormalizationDropsFormatting() {
        assertEquals("13812345678", RuleMatcher.normalizeNumber("+86 138-1234-5678"))
        assertEquals("95588", RuleMatcher.normalizeNumber("95588"))
    }
}
