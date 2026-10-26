package io.github.smslistener.data

import io.github.smslistener.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuleCodecV5Test {
    private val sms = MatchRule(
        id = "sms", localNumber = MatchCondition(MatchMode.EQUALS, "13812345678"),
        senderNumber = MatchCondition(MatchMode.CONTAINS, "95588"),
        body = MatchCondition(MatchMode.REGEX, "余额|消费"),
    )
    private val action = RuleAction(
        ActionType.RING, enabled = false, delayMs = 412, durationSeconds = 18,
        repeatCount = 3, soundUri = "content://tone/one",
    )
    private fun legacy(source: String) = JSONObject().apply {
        put("schema", 4)
        put("enabled", false)
        put("forwards", JSONArray().put(JSONObject().put("id", "f").put("type", "HTTP")
            .put("url", "https://example.com")))
        put("rules", JSONArray().put(JSONObject().apply {
            put("id", "r")
            put("name", "旧规则")
            put("description", "说明")
            put("enabled", true)
            put("trigger", source)
            put("triggerDelayMs", 600_000)
            put("matchRules", JSONArray().put(RuleCodec.encodeMatchRule(sms).apply { remove("source"); remove("delayMs") }))
            put("actions", JSONArray().put(legacyAction(action)))
        }))
    }

    private fun legacyAction(action: RuleAction) = RuleCodec.encodeAction(action).apply {
        remove("operation")
        remove("stopScope")
        remove("targetRuleId")
    }

    @Test fun schemaFiveMigrationPreservesMixedGroupOrderAndNoGlobalTrigger() {
        val rule = AlarmRule(id = "r", name = "混合", matchRules = listOf(
            sms, MatchRule(id = "u", source = TriggerSource.UNLOCK, delayMs = 600_000),
            MatchRule(id = "l", source = TriggerSource.LOCK),
        ), actions = listOf(action))
        val state = SentinelState(rules = listOf(rule))
        val json = JSONObject(RuleCodec.encode(state)).put("schema", 5)
        json.getJSONArray("rules").getJSONObject(0).put("actions", JSONArray().put(legacyAction(action)))
        assertEquals(5, json.getInt("schema"))
        assertFalse(json.getJSONArray("rules").getJSONObject(0).has("trigger"))
        assertFalse(json.getJSONArray("rules").getJSONObject(0).has("triggerDelayMs"))
        val migrated = RuleCodec.decode(json.toString())
        assertEquals(state, migrated)
        assertEquals(ActionOperation.START, migrated.rules.single().actions.single().operation)
        assertFalse(migrated.rules.single().actions.single().enabled)
        val current = JSONObject(RuleCodec.encode(migrated))
        assertEquals(RuleCodec.SCHEMA, current.getInt("schema"))
        assertFalse(current.getJSONArray("rules").getJSONObject(0).has("trigger"))
        assertEquals(state, RuleCodec.decode(current.toString()))
    }

    @Test fun schemaFourScreensReplaceOldSmsGroupsAndPreserveActions() {
        for (source in listOf(TriggerSource.LOCK, TriggerSource.UNLOCK)) {
            val raw = legacy(source.name).toString()
            val state = RuleCodec.decode(raw)
            val rule = state.rules.single()
            val match = rule.matchRules.single()
            assertFalse(state.enabled)
            assertEquals("r", rule.id)
            assertEquals("说明", rule.description)
            assertTrue(rule.enabled)
            assertEquals(source, match.source)
            assertEquals(600_000L, match.delayMs)
            assertNull(match.body)
            assertNull(match.localNumber)
            assertNull(match.senderNumber)
            assertEquals(listOf(action), rule.actions)
            assertEquals("f", state.forwards.single().id)
            assertFalse(rule.matches(SmsSubject(body = "余额", sender = "95588", localNumber = "13812345678")))
            assertEquals(match, RuleCodec.decode(raw).rules.single().matchRules.single())
            assertEquals(state, RuleCodec.decode(RuleCodec.encode(state)))
        }
    }

    @Test fun schemaFourSmsPreservesConditionsParametersAndActions() {
        val rule = RuleCodec.decode(legacy("SMS").toString()).rules.single()
        assertEquals(listOf(sms), rule.matchRules)
        assertEquals(listOf(action), rule.actions)
        assertEquals(RuleMatch.MATCHED, RuleMatcher.evaluate(rule,
            SmsSubject(body = "余额", sender = "95588", localNumber = "13812345678")))
    }

    @Test fun missingFieldsDefaultToSmsZeroDelayAndEnabled() {
        val match = requireNotNull(RuleCodec.decodeMatchRule(JSONObject()))
        assertEquals(TriggerSource.SMS, match.source)
        assertEquals(0L, match.delayMs)
        assertTrue(match.id.isNotBlank())
        val rule = requireNotNull(RuleCodec.decodeRule(JSONObject()))
        assertTrue(rule.enabled)
        assertTrue(rule.matchRules.isEmpty())
        val action = requireNotNull(RuleCodec.decodeAction(JSONObject().put("type", "VIBRATE")))
        assertEquals(RuleAction(ActionType.VIBRATE), action)
        assertNull(RuleCodec.decodeMatchRule(null))
        assertNull(RuleCodec.decodeAction(null))
        assertNull(RuleCodec.decodeRule(null))
    }

    @Test fun unknownSourcesDoNotBecomeSmsAndUnknownActionsDoNotBecomeLocalActions() {
        for (type in listOf("FUTURE", "", "sms")) {
            assertNull(RuleCodec.decodeMatchRule(RuleCodec.encodeMatchRule(sms).put("source", type)))
            assertNull(RuleCodec.decodeRule(legacy(type).getJSONArray("rules").getJSONObject(0)))
            assertNull(RuleCodec.decodeAction(JSONObject().put("type", type)))
        }
        val json = JSONObject().put("schema", 5).put("rules", JSONArray().put(JSONObject()
            .put("name", "未知条件").put("matchRules", JSONArray().put(
                RuleCodec.encodeMatchRule(sms).put("source", "FUTURE")))))
        assertTrue(RuleCodec.decode(json.toString()).rules.single().matchRules.isEmpty())
    }

    @Test fun publicDraftCodecsRoundTripEachConditionActionAndRule() {
        for (source in TriggerSource.entries) {
            val match = sms.copy(source = source, delayMs = 450)
            assertEquals(match, RuleCodec.decodeMatchRule(RuleCodec.encodeMatchRule(match)))
        }
        for (type in ActionType.entries) {
            val draft = action.copy(type = type, forwardIds = listOf("f1", "f2"))
            assertEquals(draft, RuleCodec.decodeAction(RuleCodec.encodeAction(draft)))
        }
        val draft = AlarmRule(id = "draft", matchRules = listOf(sms), actions = listOf(action))
        assertEquals(draft, RuleCodec.decodeRule(RuleCodec.encodeRule(draft)))
    }

    @Test fun incompleteSmsDraftDoesNotLoseTheSelectedMode() {
        val draft = sms.copy(body = MatchCondition(MatchMode.REGEX, ""))
        assertEquals(draft, RuleCodec.decodeMatchRule(RuleCodec.encodeMatchRule(draft)))
    }

    @Test fun invalidScreenDelayIsNotSilentlyClampedIntoAValidRule() {
        for (delay in listOf(-1L, 600_001L)) {
            val decoded = requireNotNull(RuleCodec.decodeMatchRule(JSONObject()
                .put("source", "LOCK").put("delayMs", delay)))
            assertEquals(delay, decoded.delayMs)
            assertNotNull(decoded.validationError())
        }
    }

    @Test fun httpDraftSurvivesButValidationExplainsAvailability() {
        val match = requireNotNull(RuleCodec.decodeMatchRule(JSONObject().put("source", "HTTP")))
        assertEquals(TriggerSource.HTTP, match.source)
        assertTrue(requireNotNull(match.validationError()).contains("尚未开放"))
    }

    @Test fun schemaThreeSmsAndInlineLibraryStillMigrate() {
        val raw = """{"schema":3,"rules":[{"id":"r","name":"n","matchRules":[
            {"id":"m","body":{"mode":"CONTAINS","value":"告警"}}],"actions":[
            {"type":"FORWARD","forwards":[{"id":"f","type":"HTTP","url":"https://example.com"}]}]}]}"""
        val state = RuleCodec.decode(raw)
        assertEquals(TriggerSource.SMS, state.rules.single().matchRules.single().source)
        assertEquals(listOf("f"), state.rules.single().actions.single().forwardIds)
        assertEquals(RuleMatch.MATCHED, RuleMatcher.evaluate(state.rules.single(), SmsSubject(body = "告警")))
        assertEquals(state, RuleCodec.decode(RuleCodec.encode(state)))
    }

    @Test fun schemaOneAndTwoRemainSmsAndRoundTripThroughCurrentSchema() {
        val raws = listOf(
            """{"schema":1,"keywords":["告警"],"excludedKeywords":["演练"],"sound":true}""",
            """{"schema":2,"rules":[{"id":"r","name":"n","body":"告警","actions":[{"type":"RING"}]}]}""",
        )
        raws.forEach { raw ->
            val state = RuleCodec.decode(raw)
            assertEquals(TriggerSource.SMS, state.rules.single().matchRules.single().source)
            assertEquals(RuleMatch.MATCHED, RuleMatcher.evaluate(state.rules.single(), SmsSubject(body = "告警")))
            assertEquals(state, RuleCodec.decode(RuleCodec.encode(state)))
        }
    }
}
