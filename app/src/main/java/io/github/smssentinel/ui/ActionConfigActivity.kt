package io.github.smssentinel.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.content.res.ColorStateList
import io.github.smssentinel.core.ActionType
import io.github.smssentinel.core.RuleAction
import io.github.smssentinel.ui.SentinelIcon.Kind
import io.github.smssentinel.ui.UiKit.Companion.ACCENT
import io.github.smssentinel.ui.UiKit.Companion.BACKGROUND
import io.github.smssentinel.ui.UiKit.Companion.INK
import io.github.smssentinel.ui.UiKit.Companion.MUTED
import io.github.smssentinel.ui.UiKit.Companion.SECONDARY
import io.github.smssentinel.ui.UiKit.Companion.SOFT

/** Full configuration page for one result action. */
class ActionConfigActivity : Activity() {
    private lateinit var ui: UiKit
    private lateinit var enabledSwitch: Switch
    private lateinit var delayField: EditText
    private lateinit var durationField: EditText
    private lateinit var repeatField: EditText
    private lateinit var soundLabel: TextView
    private var type: ActionType = ActionType.VIBRATE
    private var soundUri: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        type = savedInstanceState?.getString(KEY_TYPE)
            ?.let { name -> ActionType.entries.firstOrNull { it.name == name } }
            ?: ActionType.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_TYPE) }
            ?: ActionType.VIBRATE
        soundUri = savedInstanceState?.getString(KEY_SOUND) ?: intent.getStringExtra(EXTRA_SOUND).orEmpty()
        buildScreen()
        enabledSwitch.isChecked = savedInstanceState?.getBoolean(KEY_ENABLED)
            ?: intent.getBooleanExtra(EXTRA_ENABLED, true)
        delayField.setText(savedInstanceState?.getString(KEY_DELAY) ?: intent.getLongExtra(EXTRA_DELAY, 0L).toString())
        durationField.setText(savedInstanceState?.getString(KEY_DURATION) ?: intent.getIntExtra(EXTRA_DURATION, 0).toString())
        repeatField.setText(savedInstanceState?.getString(KEY_REPEAT) ?: intent.getIntExtra(EXTRA_REPEAT, 0).toString())
        renderSound()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_TYPE, type.name)
        outState.putBoolean(KEY_ENABLED, enabledSwitch.isChecked)
        outState.putString(KEY_DELAY, delayField.text.toString())
        outState.putString(KEY_DURATION, durationField.text.toString())
        outState.putString(KEY_REPEAT, repeatField.text.toString())
        outState.putString(KEY_SOUND, soundUri)
        super.onSaveInstanceState(outState)
    }

    private fun buildScreen() = with(ui) {
        val root = column().apply { setBackgroundColor(BACKGROUND) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        val bar = row().apply { setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(Color.WHITE) }
        bar.addView(plainButton("返回", INK) { finish() }.apply {
            textSize = 13f; minHeight = dp(40); minimumHeight = dp(40)
            setPadding(dp(16), dp(8), dp(16), dp(8)); layoutParams = LinearLayout.LayoutParams(-2, -2)
        })
        bar.addView(label(type.label, 17, INK, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(plainButton("保存") { save() }.apply {
            textSize = 13f; minHeight = dp(40); minimumHeight = dp(40)
            setPadding(dp(18), dp(8), dp(18), dp(8)); layoutParams = LinearLayout.LayoutParams(-2, -2)
        })
        root.addView(bar)
        divider(root)

        val content = column().apply { setPadding(dp(18), dp(16), dp(18), dp(28)) }
        root.addView(ScrollView(this@ActionConfigActivity).apply {
            isFillViewport = true; clipToPadding = false; addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        val main = card(content)
        section(main, type.label, type.hint)
        val toggleRow = row().apply { setPadding(0, dp(6), 0, dp(6)); minimumHeight = dp(62) }
        val toggleText = column()
        toggleText.addView(label("启用", 15, INK, true)); gap(toggleText, 5)
        toggleText.addView(label("关闭后保留配置但不执行", 12, SECONDARY))
        toggleRow.addView(toggleText, LinearLayout.LayoutParams(0, -2, 1f))
        enabledSwitch = Switch(this@ActionConfigActivity).apply {
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, 0xFF7E8C8A.toInt()),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ACCENT, 0xFFD5DEDC.toInt()),
            )
        }
        toggleRow.addView(enabledSwitch)
        main.addView(toggleRow)
        divider(main); gap(main, 14)

        main.addView(label("延迟开始（毫秒）", 13, INK, true)); gap(main, 6)
        main.addView(label("0 表示收到触发后立刻执行", 11, MUTED)); gap(main, 8)
        delayField = field("0", false, "延迟毫秒").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        main.addView(delayField); gap(main, 16)

        main.addView(label("持续时间（秒）", 13, INK, true)); gap(main, 6)
        main.addView(label("0 表示一直持续到手动停止", 11, MUTED)); gap(main, 8)
        durationField = field("0", false, "持续时间秒").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        main.addView(durationField); gap(main, 16)

        main.addView(label("重复次数", 13, INK, true)); gap(main, 6)
        main.addView(label("0 表示不限次数，按“持续时间”停止", 11, MUTED)); gap(main, 8)
        repeatField = field("0", false, "重复次数").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        main.addView(repeatField)

        if (type == ActionType.RING) {
            val sound = card(content)
            section(sound, "提示音", "默认使用应用内置的双音提示音。")
            soundLabel = label("", 13, SECONDARY)
            sound.addView(soundLabel); gap(sound, 12)
            sound.addView(button("选择本地音乐", SOFT, ACCENT) { pickSound() }.apply {
                textSize = 14f; minHeight = dp(46); minimumHeight = dp(46)
            })
            gap(sound, 10)
            sound.addView(button("恢复默认提示音", BACKGROUND, SECONDARY) {
                soundUri = ""
                renderSound()
            }.apply { textSize = 13f; minHeight = dp(42); minimumHeight = dp(42) })
        }

        gap(content, 10)
        content.addView(label("延迟与次数只影响本机动作；转发动作不受影响。", 11, MUTED))
        setContentView(root)
    }

    private fun renderSound() {
        if (!::soundLabel.isInitialized) return
        soundLabel.text = if (soundUri.isBlank()) "当前：内置提示音"
        else "当前：自定义音乐\n" + soundUri.take(80)
        soundLabel.setTextColor(if (soundUri.isBlank()) SECONDARY else ACCENT)
    }

    private fun pickSound() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        runCatching { startActivityForResult(intent, REQUEST_SOUND) }
            .onFailure {
                android.widget.Toast.makeText(this, "没有可用的文件选择器", android.widget.Toast.LENGTH_SHORT).show()
            }
    }

    @Deprecated("Platform callback; AndroidX is not a dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SOUND && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            soundUri = uri.toString()
            renderSound()
        }
    }

    private fun save() {
        val delay = delayField.text.toString().trim().toLongOrNull() ?: -1L
        val duration = durationField.text.toString().trim().toIntOrNull() ?: -1
        val repeat = repeatField.text.toString().trim().toIntOrNull() ?: -1
        val draft = RuleAction(
            type = type,
            enabled = enabledSwitch.isChecked,
            delayMs = delay,
            durationSeconds = duration,
            repeatCount = repeat,
            soundUri = soundUri,
        )
        val error = draft.validationError()
        if (error != null) {
            android.app.AlertDialog.Builder(this).setTitle("还差一点").setMessage(error)
                .setPositiveButton("去修改", null).show()
            return
        }
        setResult(RESULT_OK, Intent()
            .putExtra(EXTRA_TYPE, type.name)
            .putExtra(EXTRA_ENABLED, draft.enabled)
            .putExtra(EXTRA_DELAY, draft.delayMs)
            .putExtra(EXTRA_DURATION, draft.durationSeconds)
            .putExtra(EXTRA_REPEAT, draft.repeatCount)
            .putExtra(EXTRA_SOUND, draft.soundUri))
        finish()
    }

    companion object {
        const val EXTRA_TYPE = "action_type"
        const val EXTRA_ENABLED = "action_enabled"
        const val EXTRA_DELAY = "action_delay"
        const val EXTRA_DURATION = "action_duration"
        const val EXTRA_REPEAT = "action_repeat"
        const val EXTRA_SOUND = "action_sound"
        private const val REQUEST_SOUND = 3001
        private const val KEY_TYPE = "draft_type"
        private const val KEY_ENABLED = "draft_enabled"
        private const val KEY_DELAY = "draft_delay"
        private const val KEY_DURATION = "draft_duration"
        private const val KEY_REPEAT = "draft_repeat"
        private const val KEY_SOUND = "draft_sound"
    }
}
