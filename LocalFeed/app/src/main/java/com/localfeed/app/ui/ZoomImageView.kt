package com.localfeed.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

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
    var onVerticalSwipe: ((Int) -> Unit)? = null

    private var zoom = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var downX = 0f
    private var downY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val accelerated = detector.scaleFactor.toDouble().pow(1.55).toFloat()
            setZoom((zoom * accelerated).coerceIn(1f, 6f), detector.focusX, detector.focusY)
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
            if (zoom > 1.01f) resetZoom() else setZoom(2.5f, e.x, e.y)
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
                downX = event.x
                downY = event.y
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
                if (event.actionMasked == MotionEvent.ACTION_UP && zoom <= 1.01f) {
                    val dy = event.y - downY
                    val dx = event.x - downX
                    if (kotlin.math.abs(dy) > height * 0.12f && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.35f) {
                        onVerticalSwipe?.invoke(if (dy < 0) 1 else -1)
                    }
                }
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

    private fun setZoom(value: Float, focusX: Float = width / 2f, focusY: Float = height / 2f) {
        val oldZoom = zoom
        zoom = value
        scaleX = zoom
        scaleY = zoom
        if (oldZoom <= 1.01f && zoom > 1.01f) {
            translationX = (width / 2f - focusX) * (zoom - 1f)
            translationY = (height / 2f - focusY) * (zoom - 1f)
        }
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
