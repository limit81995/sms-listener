package io.github.smssentinel.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Small, consistent vector icons. No icon font or bitmap dependency. */
class SentinelIcon(private val kind: Kind, tint: Int) : Drawable() {
    enum class Kind { SHIELD, RULES, TOOLS, BELL, VIBRATE, CHECK, ARROW, MESSAGE, BATTERY, STOP, CLOCK, LOCK, COPY, INFO, PLAY }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint; style = Paint.Style.STROKE; strokeWidth = 1.65f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        fun line(vararg xy: Float) {
            val path = Path().apply {
                moveTo(xy[0], xy[1])
                for (i in 2 until xy.size step 2) lineTo(xy[i], xy[i + 1])
            }
            canvas.drawPath(path, paint)
        }
        when (kind) {
            Kind.SHIELD -> {
                canvas.drawPath(Path().apply {
                    moveTo(12f, 2.5f); lineTo(20f, 6f); lineTo(20f, 12f)
                    cubicTo(20f, 17f, 16f, 20f, 12f, 22f)
                    cubicTo(8f, 20f, 4f, 17f, 4f, 12f); lineTo(4f, 6f); close()
                }, paint)
                line(8.5f, 12f, 11f, 14.5f, 15.5f, 9.5f)
            }
            Kind.RULES -> {
                line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 20f, 18f)
                canvas.drawCircle(9f, 6f, 2f, paint); canvas.drawCircle(16f, 12f, 2f, paint); canvas.drawCircle(8f, 18f, 2f, paint)
            }
            Kind.TOOLS -> {
                canvas.drawRoundRect(3f, 3f, 10f, 10f, 2f, 2f, paint)
                canvas.drawRoundRect(14f, 3f, 21f, 10f, 2f, 2f, paint)
                canvas.drawRoundRect(3f, 14f, 10f, 21f, 2f, 2f, paint)
                canvas.drawRoundRect(14f, 14f, 21f, 21f, 2f, 2f, paint)
            }
            Kind.BELL -> {
                canvas.drawPath(Path().apply {
                    moveTo(5f, 16.5f); lineTo(7f, 14f); lineTo(7f, 9f)
                    cubicTo(7f, 2f, 17f, 2f, 17f, 9f); lineTo(17f, 14f); lineTo(19f, 16.5f); close()
                }, paint)
                canvas.drawArc(9f, 17f, 15f, 22f, 0f, 180f, false, paint)
            }
            Kind.VIBRATE -> {
                canvas.drawRoundRect(8f, 4f, 16f, 20f, 2f, 2f, paint)
                line(4f, 8f, 2f, 10f, 4f, 12f, 2f, 14f, 4f, 16f)
                line(20f, 8f, 22f, 10f, 20f, 12f, 22f, 14f, 20f, 16f)
            }
            Kind.CHECK -> line(5f, 12f, 10f, 17f, 19f, 7f)
            Kind.ARROW -> line(9f, 6f, 15f, 12f, 9f, 18f)
            Kind.MESSAGE -> {
                canvas.drawPath(Path().apply {
                    moveTo(5f, 4f); lineTo(19f, 4f); quadTo(21f, 4f, 21f, 6f)
                    lineTo(21f, 16f); quadTo(21f, 18f, 19f, 18f); lineTo(9f, 18f)
                    lineTo(4f, 21f); lineTo(4f, 18f); quadTo(2f, 18f, 2f, 16f)
                    lineTo(2f, 6f); quadTo(2f, 4f, 5f, 4f)
                }, paint)
                line(7f, 9f, 16f, 9f); line(7f, 13f, 13f, 13f)
            }
            Kind.BATTERY -> {
                canvas.drawRoundRect(2f, 6f, 20f, 18f, 2f, 2f, paint)
                line(23f, 10f, 23f, 14f); line(12f, 8f, 9f, 12f, 13f, 12f, 10f, 16f)
            }
            Kind.STOP -> canvas.drawRoundRect(6f, 6f, 18f, 18f, 2f, 2f, paint)
            Kind.CLOCK -> { canvas.drawCircle(12f, 12f, 9f, paint); line(12f, 6f, 12f, 12f, 16f, 14f) }
            Kind.LOCK -> {
                canvas.drawRoundRect(5f, 10f, 19f, 21f, 2f, 2f, paint)
                canvas.drawArc(8f, 2f, 16f, 15f, 180f, 180f, false, paint); line(12f, 14f, 12f, 17f)
            }
            Kind.COPY -> {
                canvas.drawRoundRect(8f, 7f, 20f, 21f, 2f, 2f, paint); line(15f, 3f, 4f, 3f, 4f, 16f)
            }
            Kind.INFO -> { canvas.drawCircle(12f, 12f, 9f, paint); line(12f, 11f, 12f, 17f); canvas.drawPoint(12f, 7f, paint) }
            Kind.PLAY -> line(8f, 4f, 20f, 12f, 8f, 20f, 8f, 4f)
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable opacity is no longer used")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
