package com.localfeed.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

/**
 * Feed-friendly pinch zoom for still images.
 *
 * At 1x the parent ViewPager2 is allowed to intercept vertical swipes normally.
 * Once a pinch starts or the image is zoomed, interception is disabled so one-finger pan
 * and multi-touch zoom do not accidentally change feed pages.
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    var onSingleTap: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null

    private var zoom = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            setZoom((zoom * detector.scaleFactor).coerceIn(1f, 5f))
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (zoom <= 1.01f) resetZoom()
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap?.invoke()
            return true
        }
    })

    init {
        isClickable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragging = zoom > 1.01f
                if (dragging) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (zoom > 1.01f && event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    translationX = clampTranslation(translationX + dx, width.toFloat(), zoom)
                    translationY = clampTranslation(translationY + dy, height.toFloat(), zoom)
                    lastX = event.x
                    lastY = event.y
                    dragging = true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (zoom <= 1.01f) parent?.requestDisallowInterceptTouchEvent(false)
                if (event.pointerCount - 1 == 1) {
                    val remain = if (event.actionIndex == 0) 1 else 0
                    if (remain < event.pointerCount) {
                        lastX = event.getX(remain)
                        lastY = event.getY(remain)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (zoom <= 1.01f) parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    fun resetZoom() {
        zoom = 1f
        scaleX = 1f
        scaleY = 1f
        translationX = 0f
        translationY = 0f
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    fun isZoomed(): Boolean = zoom > 1.01f

    private fun setZoom(value: Float) {
        zoom = value
        scaleX = zoom
        scaleY = zoom
        translationX = clampTranslation(translationX, width.toFloat(), zoom)
        translationY = clampTranslation(translationY, height.toFloat(), zoom)
        if (zoom <= 1.01f) resetZoom()
    }

    private fun clampTranslation(value: Float, viewport: Float, scale: Float): Float {
        if (viewport <= 0f || scale <= 1f) return 0f
        val maxShift = viewport * (scale - 1f) / 2f
        return min(max(value, -maxShift), maxShift)
    }
}
