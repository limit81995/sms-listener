package io.github.smslistener.data

import io.github.smslistener.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Schema 6 distinguishes inactivity in an old document from an explicit stop command. */
class RuleCodecV6Test {
    private fun rule(id: String, actions: List<RuleAction>) = AlarmRule(
        id = id, name = "规则-$id", description = "迁移保留", enabled = false,
        matchRules = listOf(
            MatchRule(id = "$id-sms", body = MatchCondition(MatchMode.REGEX, "告警|恢复")),
            MatchRule(id = "$id-lock", source = TriggerSource.LOCK, delayMs = 123),
        ),
        actions = actions,
    )

    private fun schemaFive(state: SentinelState) = JSONObject(RuleCodec.encode(state)).apply {
        put("schema", 5)
        val rules = getJSONArray("rules")
        for (i in 0 until rules.length()) {
            val actions = rules.getJSONObject(i).getJSONArray("actions")
            for (j in 0 until actions.length()) {
                actions.getJSONObject(j).apply {
                    remove("operation")
                    remove("stopScope")
                    remove("targetRuleId")
                }
            }
        }
    }

    @Test fun newActionsAndAbsentLegacyFieldsDefaultToStartNotStop() {
        for (type in ActionType.entries) {
            val model = RuleAction(type)
            assertEquals(ActionOperation.START, model.operation)
            assertEquals(StopScope.THIS_RULE, model.stopScope)
            assertEquals("", model.targetRuleId)
            for (enabled in listOf(false, true)) {
                val json = JSONObject().put("type", type.name).put("enabled", enabled)
                val decoded = requireNotNull(RuleCodec.decodeAction(json))
                assertEquals(model.copy(enabled = enabled), decoded)
                assertEquals(ActionOperation.START, decoded.operation)
            }
        }
    }

    @Test fun schemaFiveMigrationPreservesDisabledFlagsParametersAndMixedConditionsWithoutAnyStop() {
        val actions = ActionType.entries.filter { it.isLocal }.flatMap { type ->
            listOf(false, true).map { enabled -> RuleAction(type, enabled = enabled,
                delayMs = 345, durationSeconds = 12, repeatCount = 7, soundUri = "content://fixture/tone") }
        }
        val expected = SentinelState(enabled = false, rules = listOf(rule("old", actions)))
        val raw = schemaFive(expected)
        assertFalse(raw.getJSONArray("rules").getJSONObject(0).getJSONArray("actions")
            .getJSONObject(0).has("operation"))
        val migrated = RuleCodec.decode(raw.toString())
        assertEquals(expected, migrated)
        assertTrue(migrated.rules.single().actions.all { it.operation == ActionOperation.START })
        assertEquals(3, migrated.rules.single().actions.count { !it.enabled })
        val exported = JSONObject(RuleCodec.encode(migrated))
        assertEquals(6, exported.getInt("schema"))
        assertEquals(expected, RuleCodec.decode(exported.toString()))
    }

    @Test fun schemaSixRoundTripPreservesEveryLocalOperationScopeTargetAndOrder() {
        val target = rule("target", listOf(RuleAction(ActionType.FLASH)))
        val actions = ActionType.entries.filter { it.isLocal }.flatMap { type ->
            listOf(RuleAction(type, delayMs = 123, durationSeconds = 17, repeatCount = 3,
                soundUri = if (type == ActionType.RING) "content://fixture/tone" else "")) +
                StopScope.entries.map { scope -> RuleAction(type, operation = ActionOperation.STOP,
                    stopScope = scope, targetRuleId = if (scope == StopScope.SPECIFIC_RULE) target.id else "",
                    delayMs = 321, enabled = type != ActionType.VIBRATE) }
        }
        val expected = SentinelState(rules = listOf(rule("owner", actions), target))
        val json = JSONObject(RuleCodec.encode(expected))
        assertEquals(6, RuleCodec.SCHEMA)
        assertEquals(6, json.getInt("schema"))
        assertEquals(expected, RuleCodec.decode(json.toString()))
        actions.forEach { action ->
            val encoded = RuleCodec.encodeAction(action)
            assertEquals(action.operation.name, encoded.getString("operation"))
            assertEquals(action.stopScope.name, encoded.getString("stopScope"))
            assertEquals(action.targetRuleId, encoded.getString("targetRuleId"))
            assertEquals(action, RuleCodec.decodeAction(encoded))
        }
    }

    @Test fun unknownNullAndWronglyTypedOperationsOrScopesDoNotBecomeStart() {
        val stop = RuleAction(ActionType.RING, operation = ActionOperation.STOP,
            stopScope = StopScope.SPECIFIC_RULE, targetRuleId = "other")
        val malformed = listOf("FUTURE", "", "start", "stop", 0, true,
            JSONObject.NULL, JSONArray(), JSONObject())
        for (field in listOf("operation", "stopScope")) {
            for (value in malformed) {
                val encoded = RuleCodec.encodeAction(stop).put(field, value)
                assertNull("Invalid $field=$value must not create an executable action", RuleCodec.decodeAction(encoded))
            }
        }
    }

    @Test fun schemaSixRequiresOperationFieldsEvenThoughIndependentOldDraftsHaveDefaults() {
        val state = SentinelState(rules = listOf(rule("owner", listOf(RuleAction(ActionType.RING)))))
        for (field in listOf("operation", "stopScope", "targetRuleId")) {
            val config = JSONObject(RuleCodec.encode(state))
            config.getJSONArray("rules").getJSONObject(0).getJSONArray("actions").getJSONObject(0).remove(field)
            assertThrows(IllegalArgumentException::class.java) { RuleCodec.decode(config.toString()) }
        }
        for (wrong in listOf(0, true, JSONObject.NULL, JSONArray(), JSONObject())) {
            val action = RuleCodec.encodeAction(RuleAction(ActionType.RING)).put("targetRuleId", wrong)
            assertNull(RuleCodec.decodeAction(action))
        }
    }

    @Test fun badStopEnumsInsideRuleNeverProduceAnAccidentalStart() {
        val good = RuleAction(ActionType.FLASH, delayMs = 12)
        val stop = RuleAction(ActionType.RING, operation = ActionOperation.STOP)
        for (field in listOf("operation", "stopScope")) {
            val json = RuleCodec.encodeRule(rule("owner", listOf(stop, good)))
            json.getJSONArray("actions").getJSONObject(0).put(field, "UNKNOWN")
            assertNull("A malformed stop must invalidate the draft rather than drop an action", RuleCodec.decodeRule(json))
            val config = JSONObject(RuleCodec.encode(SentinelState())).put("rules", JSONArray().put(json))
            assertThrows(IllegalArgumentException::class.java) { RuleCodec.decode(config.toString()) }
        }
    }

    @Test fun legacyFormatOneSchemaFiveBackupRestoresAndNextExportUsesSix() {
        val oldActions = listOf(RuleAction(ActionType.RING, enabled = false, delayMs = 234,
            durationSeconds = 9, repeatCount = 4, soundUri = "content://fixture/old"))
        val expected = SentinelState(enabled = false, rules = listOf(rule("old", oldActions)))
        val oldBackup = JSONObject().put("format", "smslistener-config").put("version", 1)
            .put("config", schemaFive(expected))
        val restored = ConfigBackupCodec.decode(oldBackup.toString())
        assertEquals(expected, restored)
        assertEquals(ActionOperation.START, restored.rules.single().actions.single().operation)
        assertFalse(restored.rules.single().actions.single().enabled)
        val newBackup = JSONObject(ConfigBackupCodec.encode(restored))
        assertEquals(1, newBackup.getInt("version"))
        assertEquals(6, newBackup.getJSONObject("config").getInt("schema"))
        assertEquals(restored, ConfigBackupCodec.decode(newBackup.toString()))
    }

    @Test fun danglingStopTargetIsKeptForRepairAndNeverBroadenedByTheDraftCodec() {
        for (enabled in listOf(true, false)) {
            val stop = RuleAction(ActionType.VIBRATE, enabled = enabled,
                operation = ActionOperation.STOP, stopScope = StopScope.SPECIFIC_RULE, targetRuleId = "deleted")
            val expected = SentinelState(rules = listOf(rule("owner", listOf(stop))))
            val decoded = RuleCodec.decode(RuleCodec.encode(expected))
            assertEquals(expected, decoded)
            assertEquals(StopScope.SPECIFIC_RULE, decoded.rules.single().actions.single().stopScope)
            assertEquals("deleted", decoded.rules.single().actions.single().targetRuleId)
            assertThrows(IllegalArgumentException::class.java) { ConfigBackupCodec.encode(decoded) }
            assertThrows(IllegalArgumentException::class.java) { ConfigBackupCodec.decode(RuleCodec.encode(decoded)) }
        }
    }

    @Test fun missingSpecificTargetIsInvalidAndForwardCannotBecomeAStop() {
        val stop = RuleAction(ActionType.FLASH, operation = ActionOperation.STOP,
            stopScope = StopScope.SPECIFIC_RULE)
        assertNotNull(stop.validationError())
        assertNotNull(stop.copy(targetRuleId = "  ").validationError())
        assertNull(stop.copy(targetRuleId = "other").validationError())
        assertNotNull(RuleAction(ActionType.FORWARD, forwardIds = listOf("f"),
            operation = ActionOperation.STOP).validationError())
    }
}
