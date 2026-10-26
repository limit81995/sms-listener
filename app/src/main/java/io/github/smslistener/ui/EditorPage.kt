package io.github.smslistener.ui

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView

/** Shared header and inset handling for the transactional editor pages. */
internal object EditorPage {
    fun create(activity: Activity, title: String, action: String? = null, onAction: (() -> Unit)? = null): LinearLayout {
        val ui = UiKit(activity)
        val root = ui.column().apply { setBackgroundColor(UiKit.BACKGROUND) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        val header = ui.row().apply {
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6)); setBackgroundColor(Color.WHITE)
        }
        header.addView(ui.plainButton("返回", UiKit.INK) { activity.finish() }.apply { minimumHeight = ui.dp(48) })
        header.addView(ui.label(title, 17, UiKit.INK, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null && onAction != null) {
            header.addView(ui.plainButton(action) { onAction() }.apply { minimumHeight = ui.dp(48) })
        } else {
            header.addView(android.view.View(activity), LinearLayout.LayoutParams(ui.dp(54), 1))
        }
        root.addView(header); ui.divider(root)
        val content = ui.column().apply { setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(28)) }
        root.addView(ScrollView(activity).apply { isFillViewport = true; addView(content) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        activity.setContentView(root)
        return content
    }
}
