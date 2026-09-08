package com.localfeed.app.ui

import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.RecyclerView

class AlbumPinchGridTouchListener(
    recyclerView: RecyclerView,
    private val getSpan: () -> Int,
    private val setSpan: (Int) -> Unit
) : RecyclerView.SimpleOnItemTouchListener() {
    private var accumulator = 1f
    private val detector = ScaleGestureDetector(recyclerView.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            accumulator = 1f
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            accumulator *= detector.scaleFactor
            val current = getSpan()
            if (accumulator > 1.18f && current > 3) {
                setSpan(current - 1)
                accumulator = 1f
            } else if (accumulator < 0.84f && current < 6) {
                setSpan(current + 1)
                accumulator = 1f
            }
            return true
        }
    })

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        detector.onTouchEvent(e)
        return detector.isInProgress
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        detector.onTouchEvent(e)
    }
}
