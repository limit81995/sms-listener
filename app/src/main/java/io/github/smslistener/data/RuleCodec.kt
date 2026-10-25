package io.github.smslistener.data

import io.github.smslistener.core.ActionType
import io.github.smslistener.core.AlarmRule
import io.github.smslistener.core.ForwardTarget
import io.github.smslistener.core.ForwardType
import io.github.smslistener.core.MatchCondition
import io.github.smslistener.core.MatchMode
import io.github.smslistener.core.MatchRule
import io.github.smslistener.core.RuleAction
import io.github.smslistener.core.SentinelState
import org.json.JSONArray
import org.json.JSONObject

/** JSON persistence, including migration from earlier schemas. */
object RuleCodec {
    const val SCHEMA = 4

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
        put("trigger", rule.trigger.name)
        put("triggerDelayMs", rule.triggerDelayMs)
        put("matchRules", JSONArray().apply { rule.matchRules.forEach { put(encodeMatchRule(it)) } })
        put("actions", JSONArray().apply { rule.actions.forEach { put(encodeAction(it)) } })
    }

    private fun encodeMatchRule(matchRule: MatchRule): JSONObject = JSONObject().apply {
        put("id", matchRule.id)
        matchRule.localNumber?.let { put("localNumber", encodeCondition(it)) }
        matchRule.senderNumber?.let { put("senderNumber", encodeCondition(it)) }
        matchRule.body?.let { put("body", encodeCondition(it)) }
    }

    private fun encodeCondition(condition: MatchCondition): JSONObject = JSONObject().apply {
        put("mode", condition.mode.name)
        put("value", condition.value)
    }

    private fun encodeAction(action: RuleAction): JSONObject = JSONObject().apply {
        put("type", action.type.name)
        put("enabled", action.enabled)
        put("delayMs", action.delayMs)
        put("duration", action.durationSeconds)
        put("repeatCount", action.repeatCount)
        put("soundUri", action.soundUri)
        put("forwardIds", JSONArray(action.forwardIds))
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
        return when (val schema = json.optInt("schema", 1)) {
            SCHEMA -> SentinelState(
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

    private fun decodeRule(json: JSONObject?): AlarmRule? {
        json ?: return null
        return AlarmRule(
            id = json.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
            name = json.optString("name"),
            description = json.optString("description"),
            enabled = json.optBoolean("enabled", true),
            trigger = io.github.smslistener.core.TriggerSource.entries
                .firstOrNull { it.name == json.optString("trigger") } ?: io.github.smslistener.core.TriggerSource.SMS,
            triggerDelayMs = json.optLong("triggerDelayMs", 0L).coerceIn(0, 600_000),
            matchRules = json.optJSONArray("matchRules")?.let { array ->
                (0 until array.length()).mapNotNull { decodeMatchRule(array.optJSONObject(it)) }
            } ?: emptyList(),
            actions = json.optJSONArray("actions")?.let { array ->
                (0 until array.length()).mapNotNull { decodeAction(array.optJSONObject(it)) }
            } ?: emptyList(),
        )
    }

    private fun decodeMatchRule(json: JSONObject?): MatchRule? {
        json ?: return null
        return MatchRule(
            id = json.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
            localNumber = decodeCondition(json.optJSONObject("localNumber")),
            senderNumber = decodeCondition(json.optJSONObject("senderNumber")),
            body = decodeCondition(json.optJSONObject("body")),
        )
    }

    private fun decodeCondition(json: JSONObject?): MatchCondition? {
        json ?: return null
        val mode = MatchMode.entries.firstOrNull { it.name == json.optString("mode") } ?: return null
        val value = json.optString("value")
        if (value.isBlank()) return null
        return MatchCondition(mode, value)
    }

    private fun decodeAction(json: JSONObject?): RuleAction? {
        json ?: return null
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
                        RuleAction(type = type, durationSeconds = action.optInt("duration", 0).coerceIn(0, 3600), forwardIds = ids)
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
                rule.optJSONArray("actions")?.let { rows ->
                    for (index in 0 until rows.length()) {
                        val action = rows.optJSONObject(index) ?: continue
                        when (action.optString("type")) {
                            "RING" -> actions += RuleAction(type = ActionType.RING, durationSeconds = action.optInt("duration", 0).coerceIn(0, 3600))
                            "VIBRATE" -> actions += RuleAction(type = ActionType.VIBRATE, durationSeconds = action.optInt("duration", 0).coerceIn(0, 3600))
                            "FLASH" -> actions += RuleAction(type = ActionType.FLASH, durationSeconds = action.optInt("duration", 0).coerceIn(0, 3600))
                            "FEISHU" -> {
                                val target = ForwardTarget(
                                    type = ForwardType.FEISHU,
                                    url = action.optString("url"),
                                    body = action.optString("body"),
                                    secret = action.optString("secret"),
                                )
                                library[target.id] = target; ids += target.id
                            }
                            "HTTP", "WEBHOOK" -> {
                                val target = ForwardTarget(
                                    type = ForwardType.HTTP,
                                    url = action.optString("url"),
                                    method = action.optString("method").ifBlank { "POST" },
                                    headers = action.optString("headers"),
                                    body = action.optString("body"),
                                )
                                library[target.id] = target; ids += target.id
                            }
                        }
                    }
                }
                if (ids.isNotEmpty()) actions += RuleAction(ActionType.FORWARD, forwardIds = ids)
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
