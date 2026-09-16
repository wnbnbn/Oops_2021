package com.localfeed.app.ui

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/** Zooms the continuous reader as one canvas and adds bounded horizontal focus panning. */
class ComicReaderZoomTouchListener(
    private val recycler: RecyclerView,
    private val onSingleTap: () -> Unit
) : RecyclerView.SimpleOnItemTouchListener() {
    private var scale = 1f
    private var offsetX = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var horizontalPan = false
    private var pinchStartScale = 1f
    private var pinchStartSpan = 1f
    private val touchSlop = ViewConfiguration.get(recycler.context).scaledTouchSlop

    private val scaleDetector = ScaleGestureDetector(recycler.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                recycler.stopScroll()
                recycler.parent?.requestDisallowInterceptTouchEvent(true)
                pinchStartScale = scale
                pinchStartSpan = detector.currentSpan.coerceAtLeast(1f)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val old = scale
                val ratio = (detector.currentSpan / pinchStartSpan).coerceAtLeast(0.05f)
                scale = (pinchStartScale * ratio.toDouble().pow(1.35).toFloat()).coerceIn(1f, 4f)
                if (old > 0f) {
                    val center = recycler.width / 2f
                    offsetX = (offsetX + center - detector.focusX) * (scale / old) - (center - detector.focusX)
                }
                applyTransform()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (scale <= 1.001f) reset()
            }
        })

    private val gestureDetector = GestureDetector(recycler.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scale > 1.02f) {
                    reset()
                } else {
                    scale = 2.2f
                    offsetX = (recycler.width / 2f - e.x) * (scale - 1f)
                    applyTransform()
                }
                return true
            }
        })

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        processDetectors(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastX = e.x
                horizontalPan = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                horizontalPan = false
                rv.parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (scale > 1.02f && e.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (!horizontalPan && abs(dx) > touchSlop * 0.7f && abs(dx) > abs(dy) * 0.9f) horizontalPan = true
                    if (horizontalPan) {
                        offsetX += (e.x - lastX) * 1.8f
                        applyTransform()
                    }
                    lastX = e.x
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                horizontalPan = false
                if (!scaleDetector.isInProgress) rv.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return scaleDetector.isInProgress || horizontalPan
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        processDetectors(e)
        if (e.actionMasked == MotionEvent.ACTION_MOVE && horizontalPan && e.pointerCount == 1 && !scaleDetector.isInProgress) {
            offsetX += (e.x - lastX) * 1.8f
            lastX = e.x
            applyTransform()
        }
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            horizontalPan = false
            rv.parent?.requestDisallowInterceptTouchEvent(false)
        }
    }

    fun reset() {
        scale = 1f
        offsetX = 0f
        applyTransform()
        recycler.parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun processDetectors(event: MotionEvent) {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
    }

    private fun applyTransform() {
        val maxShift = max(0f, recycler.width * (scale - 1f) / 2f)
        offsetX = offsetX.coerceIn(-maxShift, maxShift)
        recycler.pivotX = recycler.width / 2f
        recycler.pivotY = recycler.height / 2f
        recycler.scaleX = scale
        recycler.scaleY = scale
        recycler.translationX = offsetX
    }
}
