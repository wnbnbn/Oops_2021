package com.localfeed.app.core

object CardGeometry {
    /** Dimensions inside the available slot; no cropping and no oversized decorative frame. */
    fun fit(width: Int, height: Int, aspect: Float): Pair<Int,Int> {
        val w=width.coerceAtLeast(1); val h=height.coerceAtLeast(1)
        val ratio=aspect.takeIf { it.isFinite() && it>0f } ?: 1f
        return if(w.toFloat()/h>ratio) (h*ratio).toInt().coerceIn(1,w) to h
        else w to (w/ratio).toInt().coerceIn(1,h)
    }
    fun preferVertical(first: Float, second: Float, width: Int, height: Int): Boolean {
        fun score(vertical: Boolean): Long {
            val w=if(vertical) width else width/2
            val h=if(vertical) height/2 else height
            val a=fit(w,h,first); val b=fit(w,h,second)
            val x=a.first.toLong()*a.second; val y=b.first.toLong()*b.second
            return x+y+2*minOf(x,y)
        }
        return score(true)>score(false)
    }
}
