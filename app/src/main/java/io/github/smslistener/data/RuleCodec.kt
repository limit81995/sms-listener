package io.github.smslistener.data

import io.github.smslistener.core.ActionOperation
import io.github.smslistener.core.StopScope
import io.github.smslistener.core.ActionType
import io.github.smslistener.core.AlarmRule
import io.github.smslistener.core.ForwardTarget
import io.github.smslistener.core.ForwardType
import io.github.smslistener.core.MatchCondition
import io.github.smslistener.core.MatchMode
import io.github.smslistener.core.MatchRule
import io.github.smslistener.core.RuleAction
import io.github.smslistener.core.SentinelState
import io.github.smslistener.core.TriggerSource
import org.json.JSONArray
import org.json.JSONObject

/** JSON persistence, including migration from earlier schemas. */
object RuleCodec {
    const val SCHEMA = 6

    fun encode(state: SentinelState): String = JSONObject().apply {
        put("schema", SCHEMA)
        put("enabled", state.enabled)
        put("forwards", JSONArray().apply { state.forwards.forEach { put(encodeForward(it)) } })
        put("rules", JSONArray().apply { state.rules.forEach { put(encodeRule(it)) } })
    }.toString()

    fun encodeRule(rule: AlarmRule): JSONObject = JSONObject().apply {
        put("id", rule.id)
        put("name", rule.name)
        put("description", rule.description)
        put("enabled", rule.enabled)
        put("matchRules", JSONArray().apply { rule.matchRules.forEach { put(encodeMatchRule(it)) } })
        put("actions", JSONArray().apply { rule.actions.forEach { put(encodeAction(it)) } })
    }

    fun encodeMatchRule(matchRule: MatchRule): JSONObject = JSONObject().apply {
        put("id", matchRule.id)
        put("source", matchRule.source.name)
        put("delayMs", matchRule.delayMs)
        matchRule.localNumber?.let { put("localNumber", encodeCondition(it)) }
        matchRule.senderNumber?.let { put("senderNumber", encodeCondition(it)) }
        matchRule.body?.let { put("body", encodeCondition(it)) }
    }

    private fun encodeCondition(condition: MatchCondition): JSONObject = JSONObject().apply {
        put("mode", condition.mode.name)
        put("value", condition.value)
    }

    fun encodeAction(action: RuleAction): JSONObject = JSONObject().apply {
        put("type", action.type.name)
        put("enabled", action.enabled)
        put("delayMs", action.delayMs)
        put("duration", action.durationSeconds)
        put("repeatCount", action.repeatCount)
        put("soundUri", action.soundUri)
        put("forwardIds", JSONArray(action.forwardIds))
        put("operation", action.operation.name)
        put("stopScope", action.stopScope.name)
        put("targetRuleId", action.targetRuleId)
    }

    internal fun encodeForward(target: ForwardTarget): JSONObject = JSONObject().apply {
        put("id", target.id)
        put("name", target.name)
        put("type", target.type.name)
        put("url", target.url)
        put("method", target.method)
        put("headers", target.headers)
        put("body", target.body)
        put("secret", target.secret)
    }

    internal fun decodeForward(json: JSONObject?): ForwardTarget? {
        json ?: return null
        val type = ForwardType.entries.firstOrNull { it.name == json.optString("type") } ?: return null
        return ForwardTarget(
            id = json.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
            name = json.optString("name"),
            type = type,
            url = json.optString("url"),
            method = json.optString("method").ifBlank { "POST" },
            headers = json.optString("headers"),
            body = json.optString("body"),
            secret = json.optString("secret"),
        )
    }

    fun decode(raw: String): SentinelState {
        val json = JSONObject(raw)
        val schema = json.optInt("schema", 1)
        // Unlike tolerant draft decoding, a whole configuration must not silently discard an
        // invalid operation and leave the remaining START actions executable.
        json.optJSONArray("rules")?.let { rules ->
            for (index in 0 until rules.length()) {
                rules.optJSONObject(index)?.optJSONArray("actions")?.let { actions ->
                    for (actionIndex in 0 until actions.length()) {
                        val action = actions.optJSONObject(actionIndex) ?: continue
                        require(validActionMetadata(action, requireFields = schema == SCHEMA)) { "动作操作或停止范围无效" }
                    }
                }
            }
        }
        return when (schema) {
            4, 5, SCHEMA -> SentinelState(
                enabled = json.optBoolean("enabled", true),
                rules = decodeRules(json.optJSONArray("rules")),
                forwards = json.optJSONArray("forwards")?.let { array ->
                    (0 until array.length()).mapNotNull { decodeForward(array.optJSONObject(it)) }
                } ?: emptyList(),
            )
            3 -> migrateV3(json)
            2 -> migrateV2(json)
            1 -> migrateV1(json)
            else -> throw IllegalArgumentException("Unsupported configuration version: $schema")
        }
    }

    private fun decodeRules(array: JSONArray?): List<AlarmRule> =
        array?.let { rows -> (0 until rows.length()).mapNotNull { decodeRule(rows.optJSONObject(it)) } } ?: emptyList()

    /** Also accepts pre-schema-5 standalone rule drafts / intent extras. */
    fun decodeRule(json: JSONObject?): AlarmRule? {
        json ?: return null
        json.optJSONArray("actions")?.let { actions ->
            for (index in 0 until actions.length()) {
                val action = actions.optJSONObject(index) ?: continue
                if (!validActionMetadata(action)) return null
            }
        }
        val id = json.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }
        // Only an absent source defaults to SMS. Unknown future types fail closed.
        val legacySource = if (json.isNull("trigger")) TriggerSource.SMS else
            TriggerSource.entries.firstOrNull { it.name == json.optString("trigger") } ?: return null
        val groups = when (legacySource) {
            TriggerSource.LOCK, TriggerSource.UNLOCK, TriggerSource.HTTP -> listOf(
                MatchRule(
                    // Stable on repeated reads, even before the migrated state is saved.
                    id = "$id:screen",
                    source = legacySource,
                    delayMs = json.optLong("triggerDelayMs", 0L),
                ),
            )
            TriggerSource.SMS -> json.optJSONArray("matchRules")?.let { array ->
                (0 until array.length()).mapNotNull { decodeMatchRule(array.optJSONObject(it)) }
            } ?: emptyList()
        }
        return AlarmRule(
            id = id,
            name = json.optString("name"),
            description = json.optString("description"),
            enabled = json.optBoolean("enabled", true),
            matchRules = groups,
            actions = json.optJSONArray("actions")?.let { array ->
                (0 until array.length()).mapNotNull { decodeAction(array.optJSONObject(it)) }
            } ?: emptyList(),
        )
    }

    fun decodeMatchRule(json: JSONObject?): MatchRule? {
        json ?: return null
        val source = if (json.isNull("source")) TriggerSource.SMS else
            TriggerSource.entries.firstOrNull { it.name == json.optString("source") } ?: return null
        return MatchRule(
            id = json.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
            localNumber = decodeCondition(json.optJSONObject("localNumber")),
            senderNumber = decodeCondition(json.optJSONObject("senderNumber")),
            body = decodeCondition(json.optJSONObject("body")),
            source = source,
            delayMs = json.optLong("delayMs", 0L),
        )
    }

    private fun decodeCondition(json: JSONObject?): MatchCondition? {
        json ?: return null
        val mode = MatchMode.entries.firstOrNull { it.name == json.optString("mode") } ?: return null
        // Preserve incomplete draft content; validation, not decoding, reports blanks.
        return MatchCondition(mode, json.optString("value"))
    }

    private fun validActionMetadata(json: JSONObject, requireFields: Boolean = false): Boolean {
        if (requireFields && listOf("operation", "stopScope", "targetRuleId").any { !json.has(it) }) return false
        val operation = if (!json.has("operation")) ActionOperation.START else
            ActionOperation.entries.firstOrNull { it.name == json.opt("operation") } ?: return false
        if (json.has("stopScope") && StopScope.entries.none { it.name == json.opt("stopScope") }) return false
        if (json.has("targetRuleId") && json.opt("targetRuleId") !is String) return false
        return json.opt("type") != ActionType.FORWARD.name || operation == ActionOperation.START
    }

    fun decodeAction(json: JSONObject?): RuleAction? {
        json ?: return null
        if (!validActionMetadata(json)) return null
        val type = ActionType.entries.firstOrNull { it.name == json.optString("type") } ?: return null
        val ids = json.optJSONArray("forwardIds")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it)?.takeIf(String::isNotBlank) }
        } ?: emptyList()
        return RuleAction(
            type = type,
            enabled = json.optBoolean("enabled", true),
            delayMs = json.optLong("delayMs", 0L).coerceIn(0, 600_000),
            durationSeconds = json.optInt("duration", 0).coerceIn(0, 3600),
            repeatCount = json.optInt("repeatCount", 0).coerceIn(0, 999),
            soundUri = json.optString("soundUri"),
            forwardIds = ids,
            operation = if (json.has("operation")) ActionOperation.valueOf(json.getString("operation")) else ActionOperation.START,
            stopScope = if (json.has("stopScope")) StopScope.valueOf(json.getString("stopScope")) else StopScope.THIS_RULE,
            targetRuleId = json.optString("targetRuleId"),
        )
    }

    /** Schema 3 kept forward targets inside each action; lift them into the library. */
    private fun migrateV3(json: JSONObject): SentinelState {
        val library = LinkedHashMap<String, ForwardTarget>()
        val rules = json.optJSONArray("rules")?.let { array ->
            (0 until array.length()).mapNotNull { element ->
                val rule = array.optJSONObject(element) ?: return@mapNotNull null
                val actions = rule.optJSONArray("actions")?.let { rows ->
                    (0 until rows.length()).mapNotNull { index ->
                        val action = rows.optJSONObject(index) ?: return@mapNotNull null
                        val type = ActionType.entries.firstOrNull { it.name == action.optString("type") }
                            ?: return@mapNotNull null
                        val ids = mutableListOf<String>()
                        val inline = action.optJSONArray("forwards")
                        if (inline != null) {
                            for (i in 0 until inline.length()) {
                                val target = decodeForward(inline.optJSONObject(i)) ?: continue
                                library[target.id] = target
                                ids += target.id
                            }
                        }
                        RuleAction(type = type, enabled = action.optBoolean("enabled", true),
                            durationSeconds = action.optInt("duration", 0).coerceIn(0, 3600), forwardIds = ids)
                    }
                } ?: emptyList()
                AlarmRule(
                    id = rule.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                    name = rule.optString("name"),
                    description = rule.optString("description"),
                    enabled = rule.optBoolean("enabled", true),
                    matchRules = rule.optJSONArray("matchRules")?.let { rows ->
                        (0 until rows.length()).mapNotNull { decodeMatchRule(rows.optJSONObject(it)) }
                    } ?: emptyList(),
                    actions = actions,
                )
            } ?: emptyList()
        } ?: emptyList()
        return SentinelState(
            enabled = json.optBoolean("enabled", true),
            rules = rules,
            forwards = library.values.toList(),
        )
    }

    /** Schema 2: flat sender/body patterns with inline actions. */
    private fun migrateV2(json: JSONObject): SentinelState {
        val library = LinkedHashMap<String, ForwardTarget>()
        val rules = json.optJSONArray("rules")?.let { array ->
            (0 until array.length()).mapNotNull { element ->
                val rule = array.optJSONObject(element) ?: return@mapNotNull null
                val sender = rule.optString("sender").trim().takeIf { it.isNotEmpty() && it != "*" }
                val body = rule.optString("body").trim().takeIf { it.isNotEmpty() }
                val match = MatchRule(
                    senderNumber = sender?.let { pattern ->
                        if (pattern.all { ch -> ch.isDigit() || ch == '+' || ch == '*' || ch == '#' }) {
                            MatchCondition(MatchMode.CONTAINS, pattern)
                        } else MatchCondition(MatchMode.REGEX, pattern)
                    },
                    body = body?.let { MatchCondition(MatchMode.REGEX, it) },
                )
                val actions = mutableListOf<RuleAction>()
                val ids = mutableListOf<String>()
                val disabledIds = mutableListOf<String>()
                rule.optJSONArray("actions")?.let { rows ->
                    for (index in 0 until rows.length()) {
                        val action = rows.optJSONObject(index) ?: continue
                        when (action.optString("type")) {
                            "RING" -> actions += RuleAction(type = ActionType.RING, enabled = action.optBoolean("enabled", true), durationSeconds = action.optInt("duration", 0).coerceIn(0, 3600))
                            "VIBRATE" -> actions += RuleAction(type = ActionType.VIBRATE, enabled = action.optBoolean("enabled", true), durationSeconds = action.optInt("duration", 0).coerceIn(0, 3600))
                            "FLASH" -> actions += RuleAction(type = ActionType.FLASH, enabled = action.optBoolean("enabled", true), durationSeconds = action.optInt("duration", 0).coerceIn(0, 3600))
                            "FEISHU" -> {
                                val target = ForwardTarget(
                                    type = ForwardType.FEISHU,
                                    url = action.optString("url"),
                                    body = action.optString("body"),
                                    secret = action.optString("secret"),
                                )
                                library[target.id] = target
                                if (action.optBoolean("enabled", true)) ids += target.id else disabledIds += target.id
                            }
                            "HTTP", "WEBHOOK" -> {
                                val target = ForwardTarget(
                                    type = ForwardType.HTTP,
                                    url = action.optString("url"),
                                    method = action.optString("method").ifBlank { "POST" },
                                    headers = action.optString("headers"),
                                    body = action.optString("body"),
                                )
                                library[target.id] = target
                                if (action.optBoolean("enabled", true)) ids += target.id else disabledIds += target.id
                            }
                        }
                    }
                }
                if (ids.isNotEmpty()) actions += RuleAction(ActionType.FORWARD, forwardIds = ids)
                if (disabledIds.isNotEmpty()) actions += RuleAction(ActionType.FORWARD, enabled = false, forwardIds = disabledIds)
                AlarmRule(
                    id = rule.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                    name = rule.optString("name"),
                    description = rule.optString("description"),
                    enabled = rule.optBoolean("enabled", true),
                    matchRules = listOf(match),
                    actions = actions,
                )
            } ?: emptyList()
        } ?: emptyList()
        return SentinelState(
            enabled = json.optBoolean("enabled", true),
            rules = rules,
            forwards = library.values.toList(),
        )
    }

    /** Schema 1: literal keyword list with optional exclusions. */
    private fun migrateV1(json: JSONObject): SentinelState {
        fun words(key: String): List<String> =
            json.optJSONArray(key)?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it)?.trim() }
                    .filter { it.isNotEmpty() }
            } ?: emptyList()

        val keywords = words("keywords").map { Regex.escape(it) }
        if (keywords.isEmpty()) return SentinelState()
        val excluded = words("excludedKeywords")
        val matchAll = json.optBoolean("matchAll", false)
        val exclusion = if (excluded.isEmpty()) "" else
            "(?!.*(?:" + excluded.joinToString("|") { Regex.escape(it) } + "))"
        val core = if (matchAll) keywords.joinToString("") { "(?=.*" + it + ")" } + ".*"
        else ".*(?:" + keywords.joinToString("|") + ").*"
        val duration = json.optInt("durationSeconds", 0).coerceIn(0, 3600)
        val actions = buildList {
            if (json.optBoolean("sound", true)) add(RuleAction(type = ActionType.RING, durationSeconds = duration))
            if (json.optBoolean("vibration", true)) add(RuleAction(type = ActionType.VIBRATE, durationSeconds = duration))
        }
        val rule = AlarmRule(
            name = "默认规则",
            description = "从旧版关键词配置迁移",
            matchRules = listOf(MatchRule(body = MatchCondition(MatchMode.REGEX, "^$exclusion$core\$"))),
            actions = actions.ifEmpty { listOf(RuleAction(type = ActionType.VIBRATE, durationSeconds = duration)) },
        )
        return SentinelState(rules = listOf(rule))
    }
}
