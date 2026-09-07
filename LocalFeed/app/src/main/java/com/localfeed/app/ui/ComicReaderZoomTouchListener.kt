package com.localfeed.app.ui

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.pow

/** Pinches the complete continuous-reading canvas, so every page keeps the same zoom level. */
class ComicReaderZoomTouchListener(
    private val recycler: RecyclerView,
    private val onSingleTap: () -> Unit
) : RecyclerView.SimpleOnItemTouchListener() {
    private var scale = 1f
    private var lastEventTime = -1L
    private var lastEventAction = -1

    private val scaleDetector = ScaleGestureDetector(recycler.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                recycler.stopScroll()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val accelerated = detector.scaleFactor.toDouble().pow(1.55).toFloat()
                setScale((scale * accelerated).coerceIn(1f, 3.5f), detector.focusX, detector.focusY)
                return true
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
                if (scale > 1.05f) reset() else setScale(2.2f, e.x, e.y)
                return true
            }
        })

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        process(e)
        return scaleDetector.isInProgress
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        process(e)
    }

    private fun process(e: MotionEvent) {
        if (lastEventTime == e.eventTime && lastEventAction == e.action) return
        lastEventTime = e.eventTime
        lastEventAction = e.action
        scaleDetector.onTouchEvent(e)
        gestureDetector.onTouchEvent(e)
    }

    fun reset() = setScale(1f, recycler.width / 2f, recycler.height / 2f)

    private fun setScale(value: Float, focusX: Float, focusY: Float) {
        scale = value
        recycler.pivotX = focusX.coerceIn(0f, recycler.width.toFloat())
        recycler.pivotY = focusY.coerceIn(0f, recycler.height.toFloat())
        recycler.scaleX = scale
        recycler.scaleY = scale
    }
}
