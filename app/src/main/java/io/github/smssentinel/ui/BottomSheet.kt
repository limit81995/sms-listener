package io.github.smssentinel.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A bottom sheet without AndroidX: slides in from the bottom edge, dims the page
 * behind it, and closes on an outside tap.
 */
object BottomSheet {
    fun show(
        context: Context,
        title: String,
        subtitle: String? = null,
        confirmLabel: String? = null,
        onConfirm: ((Dialog) -> Unit)? = null,
        build: (LinearLayout, Dialog) -> Unit,
    ): Dialog {
        val ui = UiKit(context)
        val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)

        val panel = ui.column().apply {
            setBackgroundColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                val radius = ui.dp(22).toFloat()
                // Rounded at the top edge only.
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
        }
        val handle = View(context).apply {
            setBackgroundColor(UiKit.LINE)
            layoutParams = LinearLayout.LayoutParams(ui.dp(38), ui.dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = ui.dp(10)
            }
            background = ui.rounded(UiKit.LINE, 3)
        }
        panel.addView(handle)

        val header = ui.column(20).apply { setPadding(ui.dp(20), ui.dp(14), ui.dp(20), 0) }
        header.addView(ui.label(title, 18, bold = true))
        if (!subtitle.isNullOrBlank()) {
            ui.gap(header, 7)
            header.addView(ui.label(subtitle, 12, UiKit.SECONDARY))
        }
        panel.addView(header)

        val body = ui.column(20).apply { setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(6)) }
        build(body, dialog)
        val screenHeight = context.resources.displayMetrics.heightPixels
        val scroll = MaxHeightScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            maxHeight = (screenHeight * 0.56f).toInt()
            addView(body)
        }
        // Wrap the content so short sheets stay compact; tall ones scroll.
        panel.addView(scroll, LinearLayout.LayoutParams(-1, -2))

        val footer = ui.column(20).apply { setPadding(ui.dp(20), ui.dp(6), ui.dp(20), ui.dp(18)) }
        if (onConfirm == null) {
            footer.addView(ui.button("取消", UiKit.BACKGROUND, UiKit.SECONDARY) { dialog.dismiss() })
        } else {
            val buttons = ui.row()
            val half = LinearLayout.LayoutParams(0, -2, 1f)
            buttons.addView(ui.button("取消", UiKit.BACKGROUND, UiKit.SECONDARY) { dialog.dismiss() }.apply {
                layoutParams = half
            })
            buttons.addView(ui.button(confirmLabel ?: "确定", UiKit.ACCENT, Color.WHITE) { onConfirm(dialog) }.apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(10) })
            footer.addView(buttons)
        }
        panel.addView(footer)

        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.45f)
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnShowListener {
            // Keep the cancel button above the system navigation bar.
            val inset = dialog.window?.decorView?.rootWindowInsets
            @Suppress("DEPRECATION")
            val bottom = inset?.systemWindowInsetBottom ?: 0
            footer.setPadding(ui.dp(20), ui.dp(6), ui.dp(20), ui.dp(18) + bottom)
            panel.post {
                panel.translationY = panel.height.toFloat()
                panel.animate().translationY(0f).setDuration(190).start()
            }
        }
        dialog.show()
        return dialog
    }

    /** ScrollView that never grows past [maxHeight], so the sheet keeps its footer visible. */
    private class MaxHeightScrollView(context: Context) : ScrollView(context) {
        var maxHeight = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            if (maxHeight > 0 && measuredHeight > maxHeight) {
                setMeasuredDimension(measuredWidth, maxHeight)
            }
        }
    }

    /** A tappable option row used inside sheets. */
    fun option(
        ui: UiKit,
        title: String,
        subtitle: String,
        icon: SentinelIcon.Kind,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): View {
        val row = ui.row().apply {
            setPadding(0, ui.dp(12), 0, ui.dp(12))
            minimumHeight = ui.dp(62)
            isFocusable = enabled
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.38f
            background = ui.ripple()
            if (enabled) setOnClickListener { onClick() }
            contentDescription = title + "，" + subtitle + if (enabled) "" else "，暂不可用"
        }
        row.addView(ui.icon(icon, if (enabled) UiKit.ACCENT else UiKit.MUTED).apply {
            setPadding(ui.dp(9), ui.dp(9), ui.dp(9), ui.dp(9))
            background = ui.rounded(if (enabled) UiKit.SOFT else UiKit.BACKGROUND, 12)
        }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(13) })
        val text = ui.column()
        text.addView(ui.label(title, 15, if (enabled) UiKit.INK else UiKit.MUTED, true))
        ui.gap(text, 5)
        text.addView(ui.label(subtitle, 12, UiKit.SECONDARY))
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        if (enabled) row.addView(ui.icon(SentinelIcon.Kind.ARROW, UiKit.MUTED, 18))
        return row
    }
}
