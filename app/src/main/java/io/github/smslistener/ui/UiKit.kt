package io.github.smslistener.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Layout tokens and reusable native widgets shared across the three screens. */
class UiKit(private val context: Context) {
    companion object {
        val BACKGROUND = Color.rgb(245, 247, 248)
        val INK = Color.rgb(24, 43, 44)
        val SECONDARY = Color.rgb(92, 111, 114)
        val MUTED = Color.rgb(116, 133, 135)
        val ACCENT = Color.rgb(13, 117, 102)
        val SOFT = Color.rgb(232, 244, 240)
        val LINE = Color.rgb(231, 237, 236)
        val DARK = Color.rgb(19, 57, 53)
        val MINT = Color.rgb(191, 238, 216)
        val RED = Color.rgb(170, 52, 53)
        val RED_SOFT = Color.rgb(255, 239, 237)
        val AMBER = Color.rgb(144, 95, 25)
        val AMBER_SOFT = Color.rgb(255, 246, 228)
    }

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
    fun rounded(color: Int, radius: Int = 20, border: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        border?.let { setStroke(dp(1), it) }
    }
    fun ripple(color: Int = Color.WHITE, radius: Int = 20) = RippleDrawable(
        ColorStateList.valueOf(0x140D7566), rounded(color, radius), rounded(Color.WHITE, radius),
    )
    fun column(padding: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun label(value: String, size: Int = 14, color: Int = INK, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1f)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }
    fun gap(parent: LinearLayout, height: Int) { parent.addView(View(context), LinearLayout.LayoutParams(1, dp(height))) }
    fun divider(parent: LinearLayout) { parent.addView(View(context).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, dp(1))) }
    fun card(parent: LinearLayout, padding: Int = 20): LinearLayout = column(padding).also {
        it.background = rounded(Color.WHITE, border = LINE)
        parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }
    fun icon(kind: SentinelIcon.Kind, color: Int = ACCENT, size: Int = 24) = ImageView(context).apply {
        setImageDrawable(SentinelIcon(kind, color))
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    fun badge(value: String, color: Int = ACCENT, fill: Int = SOFT) = label(value, 11, color, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = rounded(fill, 24)
    }
    fun button(value: String, fill: Int = ACCENT, color: Int = Color.WHITE, action: () -> Unit) = Button(context).apply {
        text = value; textSize = 15f; isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(color); minHeight = dp(52); minimumHeight = dp(52)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        background = ripple(fill, 15); stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        setOnClickListener { action() }
    }
    fun field(placeholder: String, multi: Boolean, description: String) = EditText(context).apply {
        hint = placeholder; contentDescription = description
        textSize = 16f; setTextColor(INK); setHintTextColor(MUTED)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        background = rounded(BACKGROUND, 14, LINE)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        inputType = InputType.TYPE_CLASS_TEXT or if (multi) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
        minLines = if (multi) 3 else 1
        maxLines = if (multi) 6 else 1
        gravity = Gravity.TOP or Gravity.START
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }
    /**
     * Top-bar action with no resting background: transparent until pressed, then a
     * translucent grey ripple. Padding keeps the touch target comfortable.
     */
    fun plainButton(value: String, color: Int = ACCENT, action: () -> Unit) = TextView(context).apply {
        text = value
        textSize = 13f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(color)
        setPadding(dp(14), dp(9), dp(14), dp(9))
        minimumHeight = dp(40)
        background = RippleDrawable(
            ColorStateList.valueOf(0x1F000000),
            null,
            rounded(Color.WHITE, 12),
        )
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(-2, -2)
        setOnClickListener { action() }
    }

    fun section(parent: LinearLayout, title: String, caption: String? = null) {
        parent.addView(label(title, 18, bold = true))
        if (caption != null) { gap(parent, 7); parent.addView(label(caption, 13, SECONDARY)) }
        gap(parent, 16)
    }
    fun actionRow(parent: LinearLayout, title: String, subtitle: String, kind: SentinelIcon.Kind, action: () -> Unit): TextView {
        val row = row().apply {
            setPadding(0, dp(14), 0, dp(14)); minimumHeight = dp(72)
            background = ripple()
            isFocusable = true; setOnClickListener { action() }
            contentDescription = "$title，$subtitle"
        }
        row.addView(icon(kind).apply {
            setPadding(dp(9), dp(9), dp(9), dp(9)); background = rounded(SOFT, 13)
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(13) })
        val text = column()
        text.addView(label(title, 15, bold = true))
        gap(text, 5)
        val description = label(subtitle, 12, SECONDARY)
        text.addView(description)
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(icon(SentinelIcon.Kind.ARROW, MUTED, 18))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        return description
    }
}
