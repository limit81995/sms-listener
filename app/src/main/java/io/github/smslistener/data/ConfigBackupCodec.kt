package io.github.smslistener.data

import io.github.smslistener.core.ActionOperation
import io.github.smslistener.core.StopScope
import io.github.smslistener.core.SentinelState
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Local configuration only. Secrets are preserved; messages never include configuration values. */
object ConfigBackupCodec {
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val FORMAT = "smslistener-config"
    private const val VERSION = 1

    /** Validate before writing so an invalid current state cannot replace a usable backup. */
    fun encode(state: SentinelState): String = checked {
        validateState(state)
        val config = JSONObject(RuleCodec.encode(state).also(::checkSize))
        require(decodeConfig(config) == state) { "配置存在不支持保存的字段" }
        JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("config", config)
        }.toString().also(::checkSize)
    }

    /** Accept format-1 backups or raw schema 5/6 documents, preserving every original field. */
    fun decode(raw: String): SentinelState = checked {
        checkSize(raw)
        val root = StrictJsonReader(raw).readObject()
        val config = if (root.has("format") || root.has("version") || root.has("config")) {
            require(root.opt("format") == FORMAT) { "配置文件格式不匹配" }
            require(sameNumber(root.opt("version"), VERSION)) { "配置文件版本不支持" }
            requireNotNull(root.optJSONObject("config")) { "配置文件的 config 必须是对象" }
        } else {
            root
        }
        decodeConfig(config)
    }

    private fun decodeConfig(config: JSONObject): SentinelState {
        val legacy = sameNumber(config.opt("schema"), 5)
        require(legacy || sameNumber(config.opt("schema"), RuleCodec.SCHEMA)) { "配置版本不支持" }
        val decoded = RuleCodec.decode(config.toString())
        // Compare a schema-5 input against exactly its former field set. New action fields in a
        // schema-5 document are not accepted; missing old fields still fail the lossless check.
        val canonical = JSONObject(RuleCodec.encode(decoded))
        if (legacy) {
            canonical.put("schema", 5)
            val rules = canonical.getJSONArray("rules")
            for (index in 0 until rules.length()) {
                val actions = rules.getJSONObject(index).getJSONArray("actions")
                for (actionIndex in 0 until actions.length()) {
                    actions.getJSONObject(actionIndex).apply {
                        remove("operation"); remove("stopScope"); remove("targetRuleId")
                    }
                }
            }
        }
        // Never silently drop conditions, coerce types, clamp numbers, or discard unknown fields.
        require(sameJson(config, canonical)) { "配置字段无效或不受支持" }
        validateState(decoded)
        return decoded
    }

    private fun validateState(state: SentinelState) {
        val ruleIds = HashSet<String>()
        val targetIds = HashSet<String>()
        val groupIds = HashSet<String>()
        state.forwards.forEach { target ->
            require(target.id.isNotBlank() && targetIds.add(target.id)) { "转发目标标识重复或为空" }
            require(target.validationError() == null) { "转发目标配置无效" }
        }
        state.rules.forEach { rule ->
            require(rule.id.isNotBlank() && ruleIds.add(rule.id)) { "规则标识重复或为空" }
        }
        state.rules.forEach { rule ->
            // Intentionally validate disabled rules/actions too, including legacy FORWARD actions
            // whose last target was deleted. They must be repaired before export/import.
            require(rule.validationError() == null) { "规则配置无效" }
            rule.matchRules.forEach { group ->
                require(group.id.isNotBlank() && groupIds.add(group.id)) { "条件组标识重复或为空" }
                require(group.delayMs in 0L..600_000L) { "触发延迟超出范围" }
                require(group.conditions().all { it.second.validationError() == null }) { "触发条件无效" }
            }
            rule.actions.forEach { action ->
                require(action.forwardIds.all { it in targetIds }) { "转发目标引用缺失" }
                if (action.operation == ActionOperation.STOP && action.stopScope == StopScope.SPECIFIC_RULE) {
                    require(action.targetRuleId in ruleIds) { "停止目标规则已删除，请重新选择" }
                }
            }
        }
        // Do not call SentinelState.validationError(): empty/all-disabled backups are valid.
    }

    private fun checkSize(raw: String) {
        require(raw.length <= MAX_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "配置超过2MB"
        }
    }

    private fun sameJson(left: Any?, right: Any?): Boolean = when {
        left is JSONObject && right is JSONObject -> {
            val keys = left.keys().asSequence().toSet()
            keys == right.keys().asSequence().toSet() && keys.all { sameJson(left.get(it), right.get(it)) }
        }
        left is JSONArray && right is JSONArray ->
            left.length() == right.length() && (0 until left.length()).all { sameJson(left.get(it), right.get(it)) }
        left is Number && right is Number -> sameNumber(left, right)
        left is String && right is String -> left == right
        left is Boolean && right is Boolean -> left == right
        else -> left === JSONObject.NULL && right === JSONObject.NULL
    }

    private fun sameNumber(left: Any?, right: Number): Boolean =
        left is Number && try {
            BigDecimal(left.toString()).compareTo(BigDecimal(right.toString())) == 0
        } catch (_: NumberFormatException) {
            false
        }

    private inline fun <T> checked(block: () -> T): T = try {
        block()
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (_: Exception) {
        // In particular, never attach JSON/parser exceptions that may expose a token or SMS text.
        throw IllegalArgumentException("配置格式错误")
    }

    /**
     * Android's JSONTokener accepts non-JSON syntax and rounds numbers to doubles. Read strict
     * JSON instead, retaining exact decimal values for the lossless comparison above. A depth
     * limit also prevents a small, deeply nested document from exhausting the Java stack.
     */
    private class StrictJsonReader(private val raw: String) {
        private var position = 0

        fun readObject(): JSONObject {
            val result = value(0)
            whitespace()
            require(position == raw.length) { "JSON末尾存在多余内容" }
            require(result is JSONObject) { "配置必须是JSON对象" }
            return result
        }

        private fun value(depth: Int): Any {
            require(depth <= 64) { "JSON嵌套过深" }
            whitespace()
            return when (peek()) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", JSONObject.NULL)
                '-', in '0'..'9' -> numberValue()
                else -> invalid()
            }
        }

        private fun objectValue(depth: Int): JSONObject {
            expect('{')
            whitespace()
            val result = JSONObject()
            if (take('}')) return result
            while (true) {
                val key = stringValue()
                require(!result.has(key)) { "JSON字段重复" }
                whitespace()
                expect(':')
                result.put(key, value(depth + 1))
                whitespace()
                if (take('}')) return result
                expect(',')
                whitespace()
            }
        }

        private fun arrayValue(depth: Int): JSONArray {
            expect('[')
            whitespace()
            val result = JSONArray()
            if (take(']')) return result
            while (true) {
                result.put(value(depth + 1))
                whitespace()
                if (take(']')) return result
                expect(',')
            }
        }

        private fun stringValue(): String {
            expect('"')
            val result = StringBuilder()
            while (position < raw.length) {
                val char = raw[position++]
                when {
                    char == '"' -> return result.toString()
                    char == '\\' -> {
                        if (position == raw.length) invalid()
                        result.append(when (val escaped = raw[position++]) {
                            '"', '\\', '/' -> escaped
                            'b' -> '\b'
                            'f' -> '\u000C'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> {
                                if (raw.length - position < 4) invalid()
                                val hex = raw.substring(position, position + 4)
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) invalid()
                                position += 4
                                hex.toInt(16).toChar()
                            }
                            else -> invalid()
                        })
                    }
                    char < ' ' -> invalid()
                    else -> result.append(char)
                }
            }
            invalid()
        }

        private fun numberValue(): Number {
            val start = position
            take('-')
            if (!take('0')) {
                if (peek() !in '1'..'9') invalid()
                digits()
            }
            if (take('.')) digits()
            if (take('e') || take('E')) {
                if (!take('+')) take('-')
                digits()
            }
            return try {
                BigDecimal(raw.substring(start, position))
            } catch (_: NumberFormatException) {
                invalid()
            }
        }

        private fun digits() {
            val start = position
            while (peek() in '0'..'9') position++
            if (start == position) invalid()
        }

        private fun literal(text: String, result: Any): Any {
            if (!raw.startsWith(text, position)) invalid()
            position += text.length
            return result
        }

        private fun whitespace() {
            while (position < raw.length && raw[position] in " \t\r\n") position++
        }

        private fun peek(): Char = raw.getOrNull(position) ?: '\u0000'

        private fun take(char: Char): Boolean = if (peek() == char && position < raw.length) {
            position++
            true
        } else false

        private fun expect(char: Char) {
            if (!take(char)) invalid()
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("JSON格式错误")
    }
}
