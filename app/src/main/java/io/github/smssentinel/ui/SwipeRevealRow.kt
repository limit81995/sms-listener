package io.github.smssentinel.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * A row that reveals a delete button when swiped left.
 * Written by hand to keep the app free of AndroidX widget dependencies.
 */
class SwipeRevealRow(
    context: Context,
    deleteLabel: String = "删除",
    private val onDelete: () -> Unit,
) : FrameLayout(context) {

    private val deleteWidth: Int = (72 * resources.displayMetrics.density + 0.5f).toInt()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val cornerRadius = 20 * resources.displayMetrics.density

    private val deleteButton: TextView = TextView(context).apply {
        text = deleteLabel
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        // Round only the trailing corners so the red block tucks under the card shape.
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(UiKit.RED)
            cornerRadii = floatArrayOf(0f, 0f, cornerRadius, cornerRadius, cornerRadius, cornerRadius, 0f, 0f)
        }
        contentDescription = deleteLabel
        tag = "delete_button"
        isClickable = true
        visibility = INVISIBLE
        setOnClickListener { close(); onDelete() }
    }

    private var contentRow: View? = null
    private var downX = 0f
    private var downY = 0f
    private var startOffset = 0f
    private var dragging = false
    private var opened = false

    init {
        clipChildren = true
        addView(deleteButton, LayoutParams(deleteWidth, LayoutParams.MATCH_PARENT, Gravity.END))
    }

    fun setRow(content: View) {
        contentRow?.let { removeView(it) }
        contentRow = content
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.translationX = 0f
        opened = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val content = contentRow
        var height = 0
        if (content != null) {
            content.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            height = content.measuredHeight
        }
        deleteButton.measure(
            MeasureSpec.makeMeasureSpec(deleteWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        deleteButton.layout(width - deleteWidth, 0, width, height)
        contentRow?.layout(0, 0, width, height)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                startOffset = contentRow?.translationX ?: 0f
                dragging = false
                // A tap on the revealed delete button belongs to that button. Swallowing
                // DOWN here made the row close instead of deleting.
                if (opened && ev.x >= width - deleteWidth) return false
                if (opened) return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.5f) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val content = contentRow ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                val target = (startOffset + (ev.x - downX)).coerceIn(-deleteWidth.toFloat(), 0f)
                content.translationX = target
                deleteButton.visibility = if (target < -1f) VISIBLE else INVISIBLE
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) {
                    if (opened) close()
                    return true
                }
                if (content.translationX < -deleteWidth / 2f) open() else close()
                dragging = false
            }
        }
        return true
    }

    fun open() {
        opened = true
        deleteButton.visibility = VISIBLE
        contentRow?.animate()?.translationX(-deleteWidth.toFloat())?.setDuration(160)?.start()
    }

    fun close() {
        opened = false
        contentRow?.animate()?.translationX(0f)?.setDuration(160)?.withEndAction {
            if (!opened) deleteButton.visibility = INVISIBLE
        }?.start()
    }
}
