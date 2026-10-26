package io.github.smslistener.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.smslistener.core.TriggerSource
import io.github.smslistener.ui.SentinelIcon.Kind

/** Full page, not a bottom sheet. Merely choosing a type never creates a row. */
class TriggerTypeActivity : Activity() {
    private var awaitingConfig = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        awaitingConfig = savedInstanceState?.getBoolean(KEY_AWAITING) ?: false
        val ui = UiKit(this)
        val content = EditorPage.create(this, "添加触发条件")
        content.addView(ui.label("选择触发事件", 13, UiKit.SECONDARY))
        ui.gap(content, 12)
        val options = ui.card(content, 16)
        val sources = listOf(TriggerSource.SMS, TriggerSource.UNLOCK, TriggerSource.LOCK, TriggerSource.HTTP)
        val hints = listOf("匹配本机号码、对方号码或短信正文", "解锁后触发，可设置毫秒延迟", "屏幕关闭时触发，可设置毫秒延迟", "暂未开放")
        sources.forEachIndexed { index, source ->
            val available = source != TriggerSource.HTTP
            options.addView(BottomSheet.option(ui, source.label, hints[index],
                if (source == TriggerSource.SMS) Kind.MESSAGE else Kind.TOOLS, available) {
                if (!awaitingConfig) {
                    awaitingConfig = true
                    startActivityForResult(Intent(this, MatchRuleActivity::class.java)
                        .putExtra(MatchRuleActivity.EXTRA_SOURCE, source.name), REQUEST_CONFIG)
                }
            }.apply { tag = "type_" + source.name })
            if (index < sources.lastIndex) ui.divider(options)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_AWAITING, awaitingConfig)
        super.onSaveInstanceState(outState)
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONFIG || !awaitingConfig) return
        awaitingConfig = false
        if (resultCode == RESULT_OK && data?.hasExtra(MatchRuleActivity.EXTRA_MATCH) == true) {
            setResult(RESULT_OK, data)
            finish()
        }
    }

    private companion object {
        const val REQUEST_CONFIG = 2101
        const val KEY_AWAITING = "awaiting_config"
    }
}
