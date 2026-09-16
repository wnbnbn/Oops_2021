package com.localfeed.app.ui

import android.view.GestureDetector
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

/** Long-press, keep the finger down, and slide across tiles to select a continuous range. */
class AlbumDragSelectTouchListener(
    private val recyclerView: RecyclerView,
    private val adapter: AlbumAdapter
) : RecyclerView.SimpleOnItemTouchListener() {
    private var active = false
    private var startPosition = RecyclerView.NO_POSITION
    private var baseSelection: Set<Long> = emptySet()
    private var lastPosition = RecyclerView.NO_POSITION

    private val detector = GestureDetector(recyclerView.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onLongPress(e: MotionEvent) {
            val child = recyclerView.findChildViewUnder(e.x, e.y) ?: return
            val position = recyclerView.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION || adapter.isHeader(position)) return
            baseSelection = adapter.selectedIds()
            if (!adapter.startSelectionAt(position)) return
            active = true
            startPosition = position
            lastPosition = position
            recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
        }
    })

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        detector.onTouchEvent(e)
        if (!active) return false
        handle(e)
        return true
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        detector.onTouchEvent(e)
        if (active) handle(e)
    }

    private fun handle(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                autoScroll(e.y)
                val child = recyclerView.findChildViewUnder(e.x.coerceIn(1f, recyclerView.width - 1f), e.y.coerceIn(1f, recyclerView.height - 1f))
                val p = child?.let { recyclerView.getChildAdapterPosition(it) } ?: return
                if (p != RecyclerView.NO_POSITION && p != lastPosition) {
                    lastPosition = p
                    adapter.applySelectionRange(startPosition, p, baseSelection)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finish()
        }
    }

    private fun autoScroll(y: Float) {
        val edge = recyclerView.height * 0.12f
        when {
            y < edge -> recyclerView.scrollBy(0, -36)
            y > recyclerView.height - edge -> recyclerView.scrollBy(0, 36)
        }
    }

    private fun finish() {
        active = false
        startPosition = RecyclerView.NO_POSITION
        lastPosition = RecyclerView.NO_POSITION
        baseSelection = emptySet()
        recyclerView.parent?.requestDisallowInterceptTouchEvent(false)
    }
}
