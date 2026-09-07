package com.localfeed.app.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Matrix zoom that stays independent from page-transition view properties. */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    var onSingleTap: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onVerticalSwipe: ((Int) -> Unit)? = null

    private val drawMatrix = Matrix()
    private var baseMode = ScaleType.FIT_CENTER
    private var baseScale = 1f
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor.toDouble().pow(1.35).toFloat()
                setZoomAround((zoom * factor).coerceIn(1f, 6f), detector.focusX, detector.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (zoom <= 1.015f) resetZoom()
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (zoom > 1.02f) resetZoom() else setZoomAround(2.5f, e.x, e.y)
                onDoubleTap?.invoke()
                return true
            }
        })

    init {
        super.setScaleType(ScaleType.MATRIX)
        isClickable = true
    }

    override fun setScaleType(scaleType: ScaleType?) {
        if (scaleType != null && scaleType != ScaleType.MATRIX) baseMode = scaleType
        super.setScaleType(ScaleType.MATRIX)
        post { rebuildBase(reset = false) }
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { rebuildBase(reset = true) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildBase(reset = true)
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
                if (isZoomed()) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_MOVE -> {
                if (isZoomed() && event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    offsetX += event.x - lastX
                    offsetY += event.y - lastY
                    clampOffsets()
                    applyMatrix()
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                if (remaining < event.pointerCount) {
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP && !isZoomed()) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dy) > height * 0.12f && abs(dy) > abs(dx) * 1.35f) {
                        onVerticalSwipe?.invoke(if (dy < 0f) 1 else -1)
                    }
                }
                if (!isZoomed()) parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    fun resetZoom() {
        zoom = 1f
        offsetX = 0f
        offsetY = 0f
        applyMatrix()
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    fun isZoomed(): Boolean = zoom > 1.02f

    private fun rebuildBase(reset: Boolean) {
        val d = drawable ?: return
        if (width <= 0 || height <= 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        val fit = min(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        val crop = max(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        baseScale = if (baseMode == ScaleType.CENTER_CROP) crop else fit
        if (reset) {
            zoom = 1f
            offsetX = 0f
            offsetY = 0f
        }
        applyMatrix()
    }

    private fun setZoomAround(value: Float, focusX: Float, focusY: Float) {
        val d = drawable ?: return
        if (width <= 0 || height <= 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        val oldW = d.intrinsicWidth * baseScale * zoom
        val oldH = d.intrinsicHeight * baseScale * zoom
        val oldLeft = (width - oldW) / 2f + offsetX
        val oldTop = (height - oldH) / 2f + offsetY
        val contentX = (focusX - oldLeft) / max(1f, oldW)
        val contentY = (focusY - oldTop) / max(1f, oldH)

        zoom = value
        val newW = d.intrinsicWidth * baseScale * zoom
        val newH = d.intrinsicHeight * baseScale * zoom
        offsetX = focusX - contentX * newW - (width - newW) / 2f
        offsetY = focusY - contentY * newH - (height - newH) / 2f
        clampOffsets()
        applyMatrix()
    }

    private fun clampOffsets() {
        val d = drawable ?: return
        val scaledW = d.intrinsicWidth * baseScale * zoom
        val scaledH = d.intrinsicHeight * baseScale * zoom
        val maxX = max(0f, (scaledW - width) / 2f)
        val maxY = max(0f, (scaledH - height) / 2f)
        offsetX = offsetX.coerceIn(-maxX, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
    }

    private fun applyMatrix() {
        val d = drawable ?: return
        if (width <= 0 || height <= 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        val scale = baseScale * zoom
        val left = (width - d.intrinsicWidth * scale) / 2f + offsetX
        val top = (height - d.intrinsicHeight * scale) / 2f + offsetY
        drawMatrix.reset()
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(left, top)
        imageMatrix = drawMatrix
    }
}
