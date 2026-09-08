package com.localfeed.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Minimal short-form style progress control.
 * - 2dp line while idle, 4dp while scrubbing.
 * - A thumb is only shown during scrubbing.
 * - Touch handling is limited to this view's narrow bottom strip, so vertical paging keeps priority.
 */
class FeedProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onScrubStart(fraction: Float)
        fun onScrubMove(fraction: Float)
        fun onScrubStop(fraction: Float, canceled: Boolean)
    }

    var listener: Listener? = null
    var progressFraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private var scrubbing = false
    private val density = resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Keep the visible line close to the video edge while the taller view supplies a generous
        // touch target above the system gesture strip.
        val centerY = height - 6f * density
        val idleStroke = 1.5f * density
        val activeStroke = 3.5f * density
        val stroke = if (scrubbing) activeStroke else idleStroke
        bgPaint.strokeWidth = stroke
        fgPaint.strokeWidth = stroke
        bgPaint.strokeCap = Paint.Cap.ROUND
        fgPaint.strokeCap = Paint.Cap.ROUND

        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        canvas.drawLine(left, centerY, right, centerY, bgPaint)
        val x = left + (right - left) * progressFraction
        canvas.drawLine(left, centerY, x, centerY, fgPaint)
        if (scrubbing) {
            canvas.drawCircle(x, centerY, 5.5f * density, fgPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val fraction = fractionFor(event.x)
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scrubbing = true
                parent?.requestDisallowInterceptTouchEvent(true)
                progressFraction = fraction
                listener?.onScrubStart(fraction)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scrubbing) return false
                progressFraction = fraction
                listener?.onScrubMove(fraction)
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!scrubbing) return false
                progressFraction = fraction
                listener?.onScrubStop(fraction, false)
                scrubbing = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                performClick()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!scrubbing) return false
                listener?.onScrubStop(progressFraction, true)
                scrubbing = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                true
            }
            else -> scrubbing
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun fractionFor(x: Float): Float {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        if (right <= left) return 0f
        return ((x - left) / (right - left)).coerceIn(0f, 1f)
    }
}
