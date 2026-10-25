package io.github.smslistener.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.DragEvent
import android.view.View
import android.widget.LinearLayout

/**
 * Vertical list with long-press drag sorting. The dragged row is dimmed while the
 * hovered row gets an accent outline; the move is committed on drop.
 */
class DragSortColumn(context: Context) : LinearLayout(context) {
    var onMove: ((from: Int, to: Int) -> Unit)? = null
    private var dragIndex = -1
    private var hoverIndex = -1
    private var dragView: View? = null
    private val originals = mutableMapOf<View, Drawable?>()

    init {
        orientation = VERTICAL
        setOnDragListener { _, event -> handle(event) }
    }

    fun beginDrag(row: View, index: Int) {
        dragIndex = index
        hoverIndex = index
        dragView = row
        row.alpha = 0.35f
        val started = row.startDragAndDrop(null, View.DragShadowBuilder(row), index, 0)
        if (!started) {
            // The platform refused the drag; do not leave the row dimmed forever.
            row.alpha = 1f
            dragView = null
            dragIndex = -1
            hoverIndex = -1
        }
    }

    private fun handle(event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true
            DragEvent.ACTION_DRAG_LOCATION -> {
                val index = indexAt(event.y.toInt())
                if (index >= 0 && index != hoverIndex) {
                    hoverIndex = index
                    paintTarget()
                }
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                val from = dragIndex
                val to = hoverIndex
                dragView?.alpha = 1f
                dragView = null
                clearTarget()
                dragIndex = -1
                hoverIndex = -1
                if (from >= 0 && to >= 0 && from != to) onMove?.invoke(from, to)
            }
            DragEvent.ACTION_DROP -> return true
        }
        return true
    }

    private fun indexAt(y: Int): Int {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (y >= child.top && y <= child.bottom) return index
        }
        return if (y < 0) 0 else if (childCount > 0) childCount - 1 else -1
    }

    private fun paintTarget() {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (index == hoverIndex && index != dragIndex) {
                // Keep the row's own background so it is restored after the drop.
                if (!originals.containsKey(child)) originals[child] = child.background
                child.background = GradientDrawable().apply {
                    setColor(0x00000000)
                    setStroke((1.5f * resources.displayMetrics.density).toInt(), UiKit.ACCENT)
                    cornerRadius = 16 * resources.displayMetrics.density
                }
            } else {
                originals.remove(child)?.let { child.background = it }
            }
        }
    }

    private fun clearTarget() {
        originals.forEach { (view, background) -> view.background = background }
        originals.clear()
    }
}
