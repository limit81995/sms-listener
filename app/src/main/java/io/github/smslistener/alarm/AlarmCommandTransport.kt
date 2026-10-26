package io.github.smslistener.alarm

import android.content.Intent
import android.os.Build
import android.os.Bundle
import io.github.smslistener.core.ActionOperation
import io.github.smslistener.core.ActionType
import io.github.smslistener.core.RuleAction
import io.github.smslistener.core.StopScope

/** Internal-only command batches. Legacy extras remain supported for the built-in test buttons. */
internal object AlarmCommandTransport {
    const val COMMANDS = "alarm_commands"
    const val GENERATION = "alarm_generation"
    private const val LEGACY_OWNER = "__local_test__"

    fun put(intent: Intent, commands: List<AlarmCommand>): Intent {
        require(commands.size <= 1000) { "一次触发的本机动作过多" }
        return intent.putParcelableArrayListExtra(COMMANDS, ArrayList(commands.map { command ->
            validate(command)
            Bundle().apply {
                putString("rule", command.sourceRuleId)
                putString("slot", command.slotId)
                putString("type", command.type.name)
                putString("operation", command.operation.name)
                putString("scope", command.stopScope.name)
                putString("target", command.targetRuleId)
                putLong("delay", command.delayMs)
                putInt("duration", command.durationSeconds)
                putInt("repeat", command.repeatCount)
                putString("sound", command.soundUri)
                putString("reason", command.reason)
                putBoolean("fromSms", command.fromSms)
                command.cancellationEpoch?.let { putLong("cancellationEpoch", it) }
            }
        }))
    }

    fun read(intent: Intent): List<AlarmCommand> {
        if (!intent.hasExtra(COMMANDS)) return legacy(intent)
        val payload = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(COMMANDS, Bundle::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Bundle>(COMMANDS)
        require(payload != null && payload.isNotEmpty() && payload.size <= 1000) { "本机动作参数缺失" }
        return payload.map { bundle ->
            AlarmCommand(
                sourceRuleId = bundle.getString("rule").orEmpty(),
                slotId = bundle.getString("slot").orEmpty(),
                type = ActionType.valueOf(bundle.getString("type").orEmpty()),
                operation = ActionOperation.valueOf(bundle.getString("operation").orEmpty()),
                stopScope = StopScope.valueOf(bundle.getString("scope").orEmpty()),
                targetRuleId = bundle.getString("target").orEmpty(),
                delayMs = bundle.getLong("delay"),
                durationSeconds = bundle.getInt("duration"),
                repeatCount = bundle.getInt("repeat"),
                soundUri = bundle.getString("sound").orEmpty(),
                reason = bundle.getString("reason").orEmpty(),
                fromSms = bundle.getBoolean("fromSms"),
                cancellationEpoch = if (bundle.containsKey("cancellationEpoch")) bundle.getLong("cancellationEpoch") else null,
            ).also(::validate)
        }
    }

    private fun validate(command: AlarmCommand) {
        require(command.type.isLocal && command.sourceRuleId.isNotBlank() && command.slotId.isNotBlank()) { "本机动作来源缺失" }
        val action = RuleAction(type = command.type, delayMs = command.delayMs,
            durationSeconds = command.durationSeconds, repeatCount = command.repeatCount, soundUri = command.soundUri,
            operation = command.operation, stopScope = command.stopScope, targetRuleId = command.targetRuleId)
        require(action.validationError() == null) { "本机动作参数有误" }
    }

    private fun legacy(intent: Intent): List<AlarmCommand> {
        val types = listOf(ActionType.RING to AlarmService.SOUND, ActionType.VIBRATE to AlarmService.VIBRATION,
            ActionType.FLASH to AlarmService.FLASH).filter { intent.getBooleanExtra(it.second, false) }
        require(types.isNotEmpty()) { "没有可执行的本机动作" }
        return types.map { (type, _) ->
            AlarmCommand(sourceRuleId = LEGACY_OWNER, slotId = type.name, type = type,
                delayMs = intent.getLongExtra(AlarmService.DELAY, 0L).coerceIn(0L, 600_000L),
                durationSeconds = intent.getIntExtra(AlarmService.DURATION, 0).coerceIn(0, 3600),
                repeatCount = intent.getIntExtra(AlarmService.REPEAT, 0).coerceIn(0, 999),
                soundUri = if (type == ActionType.RING) intent.getStringExtra(AlarmService.SOUND_URI).orEmpty() else "",
                reason = intent.getStringExtra(AlarmService.REASON).orEmpty(),
                fromSms = intent.getBooleanExtra(AlarmService.FROM_SMS, false))
        }
    }
}
