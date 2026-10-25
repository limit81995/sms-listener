package io.github.smslistener.data

import io.github.smslistener.core.ActionType
import io.github.smslistener.core.AlarmRule
import io.github.smslistener.core.ForwardTarget
import io.github.smslistener.core.ForwardType
import io.github.smslistener.core.MatchCondition
import io.github.smslistener.core.MatchMode
import io.github.smslistener.core.MatchRule
import io.github.smslistener.core.RuleAction
import io.github.smslistener.core.RuleMatch
import io.github.smslistener.core.RuleMatcher
import io.github.smslistener.core.SentinelState
import io.github.smslistener.core.SmsSubject
import org.junit.Assert.*
import org.junit.Test

class RuleCodecTest {
    private val http = ForwardTarget(
        id = "f1", name = "运维群", type = ForwardType.HTTP,
        url = "https://example.com/hook", method = "POST",
    )
    private val feishu = ForwardTarget(
        id = "f2", name = "告警群", type = ForwardType.FEISHU,
        url = "https://open.feishu.cn/x", secret = "s3cret",
    )

    private val rule = AlarmRule(
        id = "rule-1",
        name = "服务器告警",
        description = "生产环境",
        enabled = true,
        matchRules = listOf(
            MatchRule(
                id = "m1",
                senderNumber = MatchCondition(MatchMode.CONTAINS, "106*"),
                body = MatchCondition(MatchMode.REGEX, "告警|DOWN"),
            ),
            MatchRule(id = "m2", localNumber = MatchCondition(MatchMode.EQUALS, "13812345678")),
        ),
        actions = listOf(
            RuleAction(ActionType.RING, durationSeconds = 30),
            RuleAction(ActionType.VIBRATE),
            RuleAction(ActionType.FORWARD, forwardIds = listOf("f1", "f2")),
        ),
    )

    private val state = SentinelState(enabled = true, rules = listOf(rule), forwards = listOf(http, feishu))

    @Test fun roundTripPreservesRulesAndLibrary() {
        assertEquals(state, RuleCodec.decode(RuleCodec.encode(state)))
    }

    @Test fun matchRuleOrderIsPreserved() {
        assertEquals(listOf("m1", "m2"), RuleCodec.decode(RuleCodec.encode(state)).rules.first().matchRules.map { it.id })
    }

    @Test fun forwardIdsSurviveRoundTrip() {
        val decoded = RuleCodec.decode(RuleCodec.encode(state))
        val forward = decoded.rules.first().actions.first { it.type == ActionType.FORWARD }
        assertEquals(listOf("f1", "f2"), forward.forwardIds)
        assertEquals(2, decoded.forwardsFor(forward).size)
    }

    @Test fun schema3InlineForwardsAreLiftedIntoTheLibrary() {
        val legacy = """{"schema":3,"enabled":true,"rules":[{"id":"r","name":"旧规则","matchRules":[],
            "actions":[{"type":"FORWARD","duration":0,"forwards":[
              {"id":"a","type":"HTTP","url":"http://10.0.0.1:9000","method":"GET"},
              {"id":"b","type":"FEISHU","url":"https://open.feishu.cn/x","secret":"k"}]}]}]}"""
        val decoded = RuleCodec.decode(legacy)
        assertEquals(2, decoded.forwards.size)
        assertEquals(setOf("a", "b"), decoded.forwards.map { it.id }.toSet())
        val forward = decoded.rules.first().actions.first { it.type == ActionType.FORWARD }
        assertEquals(listOf("a", "b"), forward.forwardIds)
    }

    @Test fun schema2ActionsBecomeALibrary() {
        val legacy = """{"schema":2,"enabled":true,"rules":[{"id":"r","name":"旧规则","sender":"95588",
            "body":"余额","actions":[
              {"type":"VIBRATE","duration":10},
              {"type":"HTTP","url":"http://10.0.0.1:9000","method":"GET"},
              {"type":"FEISHU","url":"https://open.feishu.cn/x","secret":"k"}]}]}"""
        val decoded = RuleCodec.decode(legacy)
        val migrated = decoded.rules.first()
        val match = migrated.matchRules.first()
        assertEquals(MatchMode.CONTAINS, match.senderNumber?.mode)
        assertEquals(listOf(ActionType.VIBRATE, ActionType.FORWARD), migrated.actions.map { it.type })
        assertEquals(2, decoded.forwards.size)
        assertEquals(setOf(ForwardType.HTTP, ForwardType.FEISHU), decoded.forwards.map { it.type }.toSet())
    }

    @Test fun schema2RuleStillMatchesAfterMigration() {
        val legacy = """{"schema":2,"enabled":true,"rules":[{"id":"r","name":"n","sender":"95588",
            "body":"余额","actions":[{"type":"VIBRATE","duration":0}]}]}"""
        val migrated = RuleCodec.decode(legacy).rules.first()
        assertEquals(RuleMatch.MATCHED, RuleMatcher.evaluate(migrated, SmsSubject(body = "余额变动", sender = "95588")))
        assertEquals(RuleMatch.CONDITION_MISSED, RuleMatcher.evaluate(migrated, SmsSubject(body = "余额变动", sender = "10086")))
    }

    @Test fun schema1KeywordsBecomeOneRegex() {
        val legacy = """{"schema":1,"enabled":false,"keywords":["告警","故障"],"matchAll":false,
            "excludedKeywords":[],"sound":true,"vibration":false,"durationSeconds":5}"""
        val migrated = RuleCodec.decode(legacy).rules.first()
        assertEquals(RuleMatch.MATCHED, RuleMatcher.evaluate(migrated, SmsSubject(body = "主机故障告警")))
        assertEquals(RuleMatch.CONDITION_MISSED, RuleMatcher.evaluate(migrated, SmsSubject(body = "订单已送达")))
        assertEquals(listOf(ActionType.RING), migrated.actions.map { it.type })
    }

    @Test(expected = IllegalArgumentException::class) fun newerSchemaIsNotSilentlyMisread() {
        RuleCodec.decode("""{"schema":9}""")
    }

    @Test fun emptyStateRoundTrips() {
        val empty = SentinelState()
        assertEquals(empty, RuleCodec.decode(RuleCodec.encode(empty)))
    }
}
