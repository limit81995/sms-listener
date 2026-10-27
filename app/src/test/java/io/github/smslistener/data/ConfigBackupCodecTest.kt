package io.github.smslistener.data

import io.github.smslistener.core.ActionOperation
import io.github.smslistener.core.ActionType
import io.github.smslistener.core.AlarmRule
import io.github.smslistener.core.ForwardTarget
import io.github.smslistener.core.ForwardType
import io.github.smslistener.core.MatchCondition
import io.github.smslistener.core.MatchMode
import io.github.smslistener.core.MatchRule
import io.github.smslistener.core.RuleAction
import io.github.smslistener.core.SentinelState
import io.github.smslistener.core.StopScope
import io.github.smslistener.core.TriggerSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConfigBackupCodecTest {
    private val schema = RuleCodec.SCHEMA
    // Synthetic fixtures only; whitespace and escapes in credentials must survive unchanged.
    private val http = ForwardTarget(
        id = "http", name = "  HTTP目标  ", type = ForwardType.HTTP,
        url = "https://example.invalid/hook?token=fixture-token", method = "PATCH",
        headers = "{\"Authorization\":\"Bearer fixture-token\",\"X-Test\":\"中文\"}",
        body = "{\"message\":\"{{body}}\",\"sender\":\"{{sender}}\"}",
        secret = "  fixture\\token/\"值\"\n\t\u000C\b\r😃  ",
    )
    private val feishu = ForwardTarget(
        id = "feishu", name = "群机器人", type = ForwardType.FEISHU,
        url = "https://example.invalid/feishu", method = "POST",
        headers = "X-Fixture: yes", body = "自定义模板 {{body}}", secret = "  fixture-signing-secret  ",
    )
    private val rule = AlarmRule(
        id = "rule-one", name = "混合来源", description = "保留所有参数与顺序", enabled = true,
        matchRules = listOf(
            MatchRule(
                id = "sms-group", source = TriggerSource.SMS,
                localNumber = MatchCondition(MatchMode.EQUALS, "SIM-ONE"),
                senderNumber = MatchCondition(MatchMode.CONTAINS, "SENDER"),
                body = MatchCondition(MatchMode.REGEX, "告警|DOWN"),
            ),
            MatchRule(id = "unlock-group", source = TriggerSource.UNLOCK, delayMs = 123),
            MatchRule(id = "lock-group", source = TriggerSource.LOCK, delayMs = 600_000),
        ),
        actions = listOf(
            RuleAction(ActionType.RING, enabled = false, delayMs = 1234, durationSeconds = 23,
                repeatCount = 4, soundUri = "content://com.android.providers.media.documents/document/audio%3A123"),
            RuleAction(ActionType.VIBRATE, durationSeconds = 3600, repeatCount = 999),
            RuleAction(ActionType.FLASH, delayMs = 600_000),
            RuleAction(ActionType.FORWARD, delayMs = 17, durationSeconds = 19, repeatCount = 2,
                soundUri = "content://fixture/retained", forwardIds = listOf("feishu", "http")),
        ),
    )
    private val second = AlarmRule(
        id = "rule-two", name = "已禁用", enabled = false,
        matchRules = listOf(MatchRule(id = "second-group", body = MatchCondition(MatchMode.CONTAINS, "fixture"))),
        actions = listOf(RuleAction(ActionType.FLASH, enabled = false)),
    )
    private val state = SentinelState(enabled = false, rules = listOf(rule, second), forwards = listOf(http, feishu))

    private fun config(value: SentinelState = state) = JSONObject(RuleCodec.encode(value))

    /** A real v5 document has no operation fields; changing only the schema is not migration coverage. */
    private fun schemaFive(value: SentinelState = state) = config(value).apply {
        put("schema", 5)
        val rules = getJSONArray("rules")
        for (i in 0 until rules.length()) {
            val actions = rules.getJSONObject(i).getJSONArray("actions")
            for (j in 0 until actions.length()) {
                val action = actions.getJSONObject(j)
                action.remove("operation")
                action.remove("stopScope")
                action.remove("targetRuleId")
            }
        }
    }

    private fun envelope(value: JSONObject = config()) = JSONObject()
        .put("format", "smslistener-config").put("version", 1).put("config", value)
    private fun JSONObject.firstRule(): JSONObject = getJSONArray("rules").getJSONObject(0)
    private fun JSONObject.firstGroup(): JSONObject = firstRule().getJSONArray("matchRules").getJSONObject(0)
    private fun JSONObject.firstAction(): JSONObject = firstRule().getJSONArray("actions").getJSONObject(0)
    private fun JSONObject.firstTarget(): JSONObject = getJSONArray("forwards").getJSONObject(0)

    private fun rejected(block: () -> Unit): IllegalArgumentException {
        val error = assertThrows(IllegalArgumentException::class.java) { block() }
        val message = error.message.orEmpty()
        assertTrue("Expected a short Chinese error", message.length in 1..40 && message.any { it in '\u4e00'..'\u9fff' })
        assertNull(error.cause)
        return error
    }

    private fun rejectConfig(value: JSONObject) {
        rejected { ConfigBackupCodec.decode(value.toString()) }
        rejected { ConfigBackupCodec.decode(envelope(value).toString()) }
    }

    private fun rejectState(value: SentinelState) {
        rejected { ConfigBackupCodec.encode(value) }
        rejectConfig(config(value))
    }

    @Test fun roundTripPreservesMixedSourcesActionsTokensAndCustomMusic() {
        val encoded = ConfigBackupCodec.encode(state)
        val root = JSONObject(encoded)
        assertEquals("smslistener-config", root.getString("format"))
        assertEquals(1, root.getInt("version"))
        assertEquals(6, root.getJSONObject("config").getInt("schema"))
        val decoded = ConfigBackupCodec.decode(encoded)
        assertEquals(state, decoded)
        assertEquals(listOf(TriggerSource.SMS, TriggerSource.UNLOCK, TriggerSource.LOCK),
            decoded.rules.first().matchRules.map { it.source })
        assertEquals(listOf("feishu", "http"), decoded.rules.first().actions.last().forwardIds)
        assertEquals(http.secret, decoded.forwards.first().secret)
        assertEquals(feishu.secret, decoded.forwards.last().secret)
        assertEquals(rule.actions.first().soundUri, decoded.rules.first().actions.first().soundUri)
    }

    @Test fun emptyConfigurationAndEmptyDisabledConfigurationAreValid() {
        for (enabled in listOf(true, false)) {
            val empty = SentinelState(enabled = enabled)
            assertEquals(empty, ConfigBackupCodec.decode(ConfigBackupCodec.encode(empty)))
        }
    }

    @Test fun unrestrictedSmsConditionRoundTrips() {
        val unrestricted = state.copy(rules = listOf(rule.copy(matchRules = listOf(MatchRule(id = "all")))))
        assertEquals(unrestricted, ConfigBackupCodec.decode(ConfigBackupCodec.encode(unrestricted)))
    }

    @Test fun allDisabledRulesAndActionsArePreserved() {
        val disabled = state.copy(rules = state.rules.map { item ->
            item.copy(enabled = false, actions = item.actions.map { it.copy(enabled = false) })
        })
        assertEquals(disabled, ConfigBackupCodec.decode(ConfigBackupCodec.encode(disabled)))
    }

    @Test fun rawCurrentSchemaIsAcceptedWithoutMigration() {
        assertEquals(state, ConfigBackupCodec.decode(RuleCodec.encode(state)))
        assertEquals(SentinelState(), ConfigBackupCodec.decode(RuleCodec.encode(SentinelState())))
    }

    @Test fun explicitSchemaFiveRawAndFormatOneBackupsMigrateWithoutTurningDisabledIntoStop() {
        val old = schemaFive()
        assertEquals(5, old.getInt("schema"))
        assertFalse(old.firstAction().has("operation"))
        assertFalse(old.firstAction().has("stopScope"))
        assertFalse(old.firstAction().has("targetRuleId"))
        for (raw in listOf(old.toString(), envelope(old).toString())) {
            val restored = ConfigBackupCodec.decode(raw)
            assertEquals(state, restored)
            assertFalse(restored.rules.first().actions.first().enabled)
            assertFalse(restored.rules.last().actions.single().enabled)
            assertTrue(restored.rules.flatMap { it.actions }.all {
                it.operation == ActionOperation.START && it.stopScope == StopScope.THIS_RULE && it.targetRuleId.isEmpty()
            })
            val exported = ConfigBackupCodec.encode(restored)
            assertEquals(6, JSONObject(exported).getJSONObject("config").getInt("schema"))
            assertEquals(restored, ConfigBackupCodec.decode(exported))
        }
    }

    @Test fun schemaFiveMigrationRemainsStrictAboutMissingUnknownAndMalformedOldFields() {
        rejectConfig(schemaFive().apply { firstAction().remove("enabled") })
        rejectConfig(schemaFive().apply { firstAction().put("enabled", "false") })
        rejectConfig(schemaFive().apply { firstAction().put("delayMs", -1) })
        rejectConfig(schemaFive().apply { firstGroup().remove("source") })
        rejectConfig(schemaFive().put("extra", true))
        for ((field, value) in listOf("operation" to "STOP", "stopScope" to "ALL_RULES", "targetRuleId" to "rule-two")) {
            rejectConfig(schemaFive().apply { firstAction().put(field, value) })
        }
    }

    @Test fun schemaSixOperationFieldsAreRequiredAndNeverCoercedToStart() {
        for (field in listOf("operation", "stopScope", "targetRuleId")) {
            rejectConfig(config().apply { firstAction().remove(field) })
            for (bad in listOf(1, true, JSONObject.NULL, JSONObject(), JSONArray())) {
                rejectConfig(config().apply { firstAction().put(field, bad) })
            }
        }
        for (bad in listOf("FUTURE", "", "start", "stop")) {
            rejectConfig(config().apply { firstAction().put("operation", bad) })
            rejectConfig(config().apply { firstAction().put("stopScope", bad) })
        }
    }

    @Test fun schemaSixBackupPreservesAllStopScopesChannelsAndDisabledFlags() {
        val actions = ActionType.entries.filter { it.isLocal }.flatMap { type ->
            StopScope.entries.map { scope -> RuleAction(type, operation = ActionOperation.STOP,
                stopScope = scope, targetRuleId = if (scope == StopScope.SPECIFIC_RULE) second.id else "",
                delayMs = 234, enabled = type != ActionType.FLASH) }
        }
        val stopped = state.copy(rules = listOf(rule.copy(actions = actions), second))
        assertEquals(stopped, ConfigBackupCodec.decode(ConfigBackupCodec.encode(stopped)))
        assertEquals(stopped, ConfigBackupCodec.decode(RuleCodec.encode(stopped)))
    }

    @Test fun danglingStopReferencesAreRejectedEvenWhenRuleOrActionIsDisabled() {
        for (enabled in listOf(false, true)) {
            val stop = RuleAction(ActionType.RING, enabled = enabled, operation = ActionOperation.STOP,
                stopScope = StopScope.SPECIFIC_RULE, targetRuleId = "deleted-rule")
            rejectState(state.copy(rules = listOf(rule.copy(enabled = enabled, actions = listOf(stop)))))
            rejectState(state.copy(rules = listOf(rule.copy(actions = listOf(stop.copy(targetRuleId = ""))))))
        }
    }

    @Test fun outputContainsOnlyConfigurationNotLogsCursorsOrPermissions() {
        val root = JSONObject(ConfigBackupCodec.encode(state))
        assertEquals(setOf("format", "version", "config"), root.keys().asSequence().toSet())
        assertEquals(setOf("schema", "enabled", "rules", "forwards"),
            root.getJSONObject("config").keys().asSequence().toSet())
        for (field in listOf("logs", "sms", "smsCursor", "lastSmsId", "permissions", "uriPermissions")) {
            assertFalse(root.has(field))
            assertFalse(root.getJSONObject("config").has(field))
            rejectConfig(config().put(field, JSONArray()))
        }
    }

    @Test fun objectKeyOrderWhitespaceAndEquivalentNumbersAreAccepted() {
        val original = config()
        val reordered = JSONObject().apply {
            put("rules", original.getJSONArray("rules"))
            put("forwards", original.getJSONArray("forwards"))
            put("enabled", original.getBoolean("enabled"))
            put("schema", original.getInt("schema"))
        }
        val raw = reordered.toString().replace("\"schema\":$schema", "\"schema\":$schema.000")
            .replace("\"delayMs\":1234", "\"delayMs\":1.234e3")
        assertEquals(state, ConfigBackupCodec.decode(" \n\t" + raw + "\r "))
        val backup = envelope().toString().replace("\"version\":1", "\"version\":1.0")
        assertEquals(state, ConfigBackupCodec.decode(backup))
    }

    @Test fun unicodeEscapedKeysAndStringsAreAccepted() {
        val raw = RuleCodec.encode(state).replace("\"schema\"", "\"sche\\u006da\"")
            .replace("混合来源", "\\u6df7\\u5408\\u6765\\u6e90")
        assertEquals(state, ConfigBackupCodec.decode(raw))
    }

    @Test fun nonJsonNonObjectsAndPermissiveJsonExtensionsAreRejected() {
        val invalid = listOf(
            "", " ", "not json", "[]", "null", "true", "5", "\"text\"",
            "{", "{}trailing", "{}{}", "{}\u0000", "{schema:5}", "{'schema':5}",
            "{\"schema\"=5}", "{\"schema\":5;\"rules\":[]}", "{/*comment*/\"schema\":5}",
            "{\"schema\":NaN}", "{\"schema\":Infinity}", "{\"schema\":+5}",
            "{\"schema\":05}", "{\"schema\":5.}", "{\"schema\":.5}", "{\"schema\":5e}",
            "{\"schema\":5,}", "{\"rules\":[,]}", "{\"rules\":[true,]}",
            "{\"value\":\"bad\\x20\"}", "{\"value\":\"bad\\u00G0\"}",
            "{\"value\":\"bad\nline\"}", "{\"value\":\"bad\\u12\"}",
        )
        invalid.forEach { raw -> rejected { ConfigBackupCodec.decode(raw) } }
    }

    @Test fun strictGrammarIsEnforcedOnOtherwiseValidConfiguration() {
        val raw = RuleCodec.encode(SentinelState())
        val invalid = listOf(
            raw.replace("\"schema\"", "schema"),
            raw.replace("\"schema\"", "'schema'"),
            raw.replace("\"schema\":$schema", "\"schema\":0$schema"),
            raw.replace("\"schema\":$schema", "\"schema\":0x$schema"),
            raw.replace("\"schema\":$schema", "\"schema\":$schema."),
            raw.replace("\"schema\":$schema", "\"schema\"=>$schema"),
            raw.replace(",", ";"),
            raw.dropLast(1) + ",}",
            "{/* comment */" + raw.drop(1),
        )
        invalid.forEach { rejected { ConfigBackupCodec.decode(it) } }
    }

    @Test fun trailingContentIsRejectedEvenAfterValidConfiguration() {
        for (suffix in listOf("false", "{}", "// comment", "\u0000")) {
            rejected { ConfigBackupCodec.decode(RuleCodec.encode(state) + suffix) }
        }
    }

    @Test fun duplicateJsonKeysIncludingEscapedAliasesAreRejected() {
        val raw = RuleCodec.encode(state)
        for (key in listOf("schema", "sche\\u006da")) {
            rejected { ConfigBackupCodec.decode(raw.replace("\"schema\":$schema", "\"schema\":$schema,\"" + key + "\":$schema")) }
        }
        rejected { ConfigBackupCodec.decode(raw.replace("\"source\":\"SMS\"", "\"source\":\"SMS\",\"source\":\"LOCK\"")) }
    }

    @Test fun excessiveNestingFailsWithArgumentErrorRatherThanStackOverflow() {
        rejected { ConfigBackupCodec.decode("{\"extra\":" + "[".repeat(1000) + "0" + "]".repeat(1000) + "}") }
    }

    @Test fun unknownSourcesModesActionsAndForwardTypesAreRejectedWithoutDroppingItems() {
        for (unknown in listOf("FUTURE", "", "sms")) {
            rejectConfig(config().apply { firstGroup().put("source", unknown) })
            rejectConfig(config().apply { firstGroup().getJSONObject("body").put("mode", unknown) })
            rejectConfig(config().apply { firstAction().put("type", unknown) })
            rejectConfig(config().apply { firstTarget().put("type", unknown) })
        }
        rejectConfig(config().apply { firstGroup().put("source", "HTTP") })
    }

    @Test fun incorrectPrimitiveTypesAreRejectedRatherThanCoerced() {
        for (wrong in listOf("false", 0, JSONObject.NULL, JSONArray(), JSONObject())) {
            rejectConfig(config().put("enabled", wrong))
            rejectConfig(config().apply { firstRule().put("enabled", wrong) })
            rejectConfig(config().apply { firstAction().put("enabled", wrong) })
        }
        for (wrong in listOf(123, true, JSONObject.NULL, JSONArray(), JSONObject())) {
            rejectConfig(config().apply { firstRule().put("name", wrong) })
            rejectConfig(config().apply { firstRule().put("description", wrong) })
            rejectConfig(config().apply { firstAction().put("soundUri", wrong) })
            rejectConfig(config().apply { firstTarget().put("secret", wrong) })
            rejectConfig(config().apply { firstGroup().getJSONObject("body").put("value", wrong) })
        }
        for (wrong in listOf("1234", true, JSONObject.NULL)) {
            rejectConfig(config().apply { firstAction().put("delayMs", wrong) })
            rejectConfig(config().apply { firstGroup().put("delayMs", wrong) })
        }
    }

    @Test fun incorrectContainersAndMalformedArrayMembersAreRejected() {
        for (wrong in listOf("[]", JSONObject(), JSONObject.NULL)) {
            rejectConfig(config().put("rules", wrong))
            rejectConfig(config().put("forwards", wrong))
            rejectConfig(config().apply { firstRule().put("matchRules", wrong) })
            rejectConfig(config().apply { firstRule().put("actions", wrong) })
            rejectConfig(config().apply { firstAction().put("forwardIds", wrong) })
        }
        for (wrong in listOf("condition", JSONArray(), JSONObject.NULL, true)) {
            rejectConfig(config().apply { firstGroup().put("body", wrong) })
            rejectConfig(config().apply { getJSONArray("rules").put(wrong) })
            rejectConfig(config().apply { getJSONArray("forwards").put(wrong) })
            rejectConfig(config().apply { firstRule().getJSONArray("matchRules").put(wrong) })
            rejectConfig(config().apply { firstRule().getJSONArray("actions").put(wrong) })
        }
        for (wrong in listOf(123, true, JSONObject.NULL, JSONObject(), "")) {
            rejectConfig(config().apply { firstAction().put("forwardIds", JSONArray().put(wrong)) })
        }
    }

    @Test fun missingAndUnknownFieldsAreRejectedAtEveryConfigLevel() {
        rejectConfig(config().apply { remove("enabled") })
        rejectConfig(config().apply { firstRule().remove("description") })
        rejectConfig(config().apply { firstGroup().remove("source") })
        rejectConfig(config().apply { firstAction().remove("repeatCount") })
        rejectConfig(config().apply { firstTarget().remove("secret") })
        rejectConfig(config().apply { firstGroup().getJSONObject("body").remove("value") })
        rejectConfig(config().put("unknown", true))
        rejectConfig(config().apply { firstRule().put("unknown", true) })
        rejectConfig(config().apply { firstGroup().put("unknown", true) })
        rejectConfig(config().apply { firstAction().put("unknown", true) })
        rejectConfig(config().apply { firstTarget().put("unknown", true) })
        rejectConfig(config().apply { firstGroup().getJSONObject("body").put("unknown", true) })
    }

    @Test fun actionRangesFractionsAndIntegerOverflowAreRejectedRatherThanClamped() {
        val bad = mapOf(
            "delayMs" to listOf<Number>(-1, 600_001, 0.5, Long.MAX_VALUE),
            "duration" to listOf<Number>(-1, 3601, 0.5, 4_294_967_296L),
            "repeatCount" to listOf<Number>(-1, 1000, 0.5, 4_294_967_296L),
        )
        for (type in ActionType.entries) {
            for ((field, values) in bad) {
                for (number in values) {
                    rejectConfig(config().apply {
                        firstAction().put("type", type.name).put("forwardIds", JSONArray().put("http")).put(field, number)
                    })
                }
            }
        }
    }

    @Test fun conditionGroupDelaysAreValidatedForSmsAndScreenSources() {
        for (source in listOf(TriggerSource.SMS, TriggerSource.UNLOCK, TriggerSource.LOCK)) {
            for (number in listOf<Number>(-1, 600_001, 0.5, Long.MAX_VALUE)) {
                rejectConfig(config().apply { firstGroup().put("source", source.name).put("delayMs", number) })
            }
        }
    }

    @Test fun highPrecisionNumbersCannotRoundToAValidVersionOrParameter() {
        val raw = RuleCodec.encode(state)
        rejected { ConfigBackupCodec.decode(raw.replace("\"schema\":$schema", "\"schema\":$schema.00000000000000000001")) }
        for (number in listOf("1234.00000000000000000001", "1e-10000", "9223372036854775808", "1e10000")) {
            rejected { ConfigBackupCodec.decode(raw.replace("\"delayMs\":1234", "\"delayMs\":" + number)) }
        }
    }

    @Test fun invalidRuleAndConditionContentIsRejectedByModelValidation() {
        val invalidRules = listOf(
            rule.copy(name = " "), rule.copy(name = "x".repeat(61)), rule.copy(description = "x".repeat(201)),
            rule.copy(matchRules = emptyList()), rule.copy(actions = emptyList()),
            rule.copy(matchRules = List(21) { rule.matchRules.first().copy(id = "g-" + it) }),
            rule.copy(actions = List(21) { RuleAction(ActionType.FLASH) }),
            rule.copy(matchRules = listOf(MatchRule(id = "blank", body = MatchCondition(MatchMode.CONTAINS, " ")))),
            rule.copy(matchRules = listOf(MatchRule(id = "long", body = MatchCondition(MatchMode.CONTAINS, "x".repeat(501))))),
            rule.copy(matchRules = listOf(MatchRule(id = "regex", body = MatchCondition(MatchMode.REGEX, "fixture-private[")))),
            rule.copy(matchRules = listOf(MatchRule(id = "http-group", source = TriggerSource.HTTP))),
        )
        invalidRules.forEach { rejectState(state.copy(rules = listOf(it))) }
    }

    @Test fun unusedScreenConditionsAreAlsoValidatedWithoutLeakingRegexText() {
        val group = MatchRule(id = "screen", source = TriggerSource.LOCK,
            body = MatchCondition(MatchMode.REGEX, "fixture-private["))
        rejectState(state.copy(rules = listOf(rule.copy(matchRules = listOf(group)))))
    }

    @Test fun invalidTargetSettingsAreRejectedIncludingUnusedTargets() {
        val invalidTargets = listOf(
            http.copy(url = ""), http.copy(url = "ftp://example.invalid"),
            http.copy(url = "https://example.invalid/" + "x".repeat(2048)),
            http.copy(method = "TRACE"), http.copy(headers = "x".repeat(8193)), http.copy(body = "x".repeat(32769)),
        )
        invalidTargets.forEach { rejectState(SentinelState(forwards = listOf(it))) }
    }

    @Test fun duplicateAndBlankRuleTargetAndGroupIdsAreRejected() {
        rejectState(state.copy(rules = listOf(rule, rule)))
        rejectState(state.copy(forwards = listOf(http, http, feishu)))
        rejectState(state.copy(rules = listOf(rule.copy(matchRules = listOf(rule.matchRules.first(), rule.matchRules.first())))))
        rejectState(state.copy(rules = listOf(rule, second.copy(matchRules = listOf(rule.matchRules.first())))))
        for (id in listOf("", " \t")) {
            rejectState(state.copy(rules = listOf(rule.copy(id = id))))
            rejectState(SentinelState(forwards = listOf(http.copy(id = id))))
            rejectState(state.copy(rules = listOf(rule.copy(matchRules = listOf(rule.matchRules.first().copy(id = id))))))
        }
    }

    @Test fun danglingForwardReferencesAreRejectedEvenForDisabledActionsAndRules() {
        for (enabled in listOf(true, false)) {
            val action = RuleAction(ActionType.FORWARD, enabled = enabled, forwardIds = listOf("http", "deleted"))
            rejectState(state.copy(rules = listOf(rule.copy(enabled = enabled, actions = listOf(action)))))
        }
        rejectState(state.copy(rules = listOf(rule.copy(actions = listOf(RuleAction(ActionType.RING, forwardIds = listOf("deleted")))))))
    }

    @Test fun legacyForwardActionWithAllTargetsDeletedIsStrictlyRejected() {
        for (enabled in listOf(true, false)) {
            val action = RuleAction(ActionType.FORWARD, enabled = enabled, forwardIds = emptyList())
            rejectState(state.copy(rules = listOf(rule.copy(enabled = enabled, actions = listOf(action)))))
        }
    }

    @Test fun tooManyForwardReferencesAreRejected() {
        val action = RuleAction(ActionType.FORWARD, forwardIds = List(21) { "http" })
        rejectState(state.copy(rules = listOf(rule.copy(actions = listOf(action)))))
    }

    @Test fun encodeRejectsValuesThatRuleCodecWouldClampEvenOnForwardActions() {
        val action = rule.actions.last().copy(delayMs = -1)
        rejectState(state.copy(rules = listOf(rule.copy(actions = listOf(action)))))
    }

    @Test fun oldFutureMissingOrWronglyTypedSchemasAreRejected() {
        for (badSchema in listOf(0, 1, 2, 3, 4, schema + 1, 99, schema + 0.5, schema.toString(), true, JSONObject.NULL)) {
            rejectConfig(config().put("schema", badSchema))
        }
        rejectConfig(config().apply { remove("schema") })
    }

    @Test fun unsupportedBackupVersionOrFormatAndMalformedEnvelopeAreRejected() {
        for (version in listOf(0, -1, 2, 99, 1.5, "1", true, JSONObject.NULL)) {
            rejected { ConfigBackupCodec.decode(envelope().put("version", version).toString()) }
        }
        for (format in listOf("other", "SMSLISTENER-CONFIG", 1, JSONObject.NULL)) {
            rejected { ConfigBackupCodec.decode(envelope().put("format", format).toString()) }
        }
        for (value in listOf("{}", JSONArray(), JSONObject.NULL, true)) {
            rejected { ConfigBackupCodec.decode(envelope().put("config", value).toString()) }
        }
        for (key in listOf("version", "format", "config")) {
            rejected { ConfigBackupCodec.decode(envelope().apply { remove(key) }.toString()) }
        }
    }

    @Test fun utf8ByteLimitRejectsOversizeInputAndOutputRatherThanCountingCharacters() {
        assertEquals(2 * 1024 * 1024, ConfigBackupCodec.MAX_BYTES)
        val raw = "中".repeat(ConfigBackupCodec.MAX_BYTES / 3 + 1)
        assertTrue(raw.length < ConfigBackupCodec.MAX_BYTES)
        assertEquals("配置超过2MB", rejected { ConfigBackupCodec.decode(raw) }.message)
        val oversized = SentinelState(forwards = listOf(http.copy(secret = raw)))
        assertEquals("配置超过2MB", rejected { ConfigBackupCodec.encode(oversized) }.message)
    }

    @Test fun exactByteLimitIsAcceptedAndOneExtraByteIsRejected() {
        val raw = RuleCodec.encode(SentinelState())
        val padded = raw + " ".repeat(ConfigBackupCodec.MAX_BYTES - raw.toByteArray(Charsets.UTF_8).size)
        assertEquals(ConfigBackupCodec.MAX_BYTES, padded.toByteArray(Charsets.UTF_8).size)
        assertEquals(SentinelState(), ConfigBackupCodec.decode(padded))
        assertEquals("配置超过2MB", rejected { ConfigBackupCodec.decode(padded + " ") }.message)
    }

    @Test fun exportLimitIncludesTheEnvelopeAndAcceptsExactlyMaxBytes() {
        val emptySecret = SentinelState(forwards = listOf(http.copy(secret = "")))
        val overhead = ConfigBackupCodec.encode(emptySecret).toByteArray(Charsets.UTF_8).size
        val exact = emptySecret.copy(forwards = listOf(http.copy(secret = "a".repeat(ConfigBackupCodec.MAX_BYTES - overhead))))
        val encoded = ConfigBackupCodec.encode(exact)
        assertEquals(ConfigBackupCodec.MAX_BYTES, encoded.toByteArray(Charsets.UTF_8).size)
        assertEquals(exact, ConfigBackupCodec.decode(encoded))
        val oversized = exact.copy(forwards = listOf(exact.forwards.single().copy(secret = exact.forwards.single().secret + "x")))
        assertEquals("配置超过2MB", rejected { ConfigBackupCodec.encode(oversized) }.message)
    }

    @Test fun errorMessagesContainNoRawPayloadOrCredentials() {
        val marker = "DO_NOT_INCLUDE_THIS_FIXTURE_TOKEN"
        val malformed = "{\"token\":\"" + marker + "\",\"schema\":}"
        val parseError = rejected { ConfigBackupCodec.decode(malformed) }
        assertFalse(parseError.message.orEmpty().contains(marker))
        val broken = state.copy(rules = listOf(rule.copy(matchRules = listOf(
            MatchRule(id = "sensitive", body = MatchCondition(MatchMode.REGEX, marker + "[")),
        ))))
        val modelError = rejected { ConfigBackupCodec.encode(broken) }
        assertFalse(modelError.message.orEmpty().contains(marker))
    }
}
