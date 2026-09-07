package com.localfeed.app.ui

import android.graphics.Color
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

data class CardTier(val level: Int, val title: String, val color: Int, val widthDp: Float, val badge: String) {
    companion object {
        fun forCount(count: Int): CardTier = when {
            count >= 25 -> CardTier(5, "典藏", Color.rgb(235, 196, 92), 2.5f, "典藏 $count")
            count >= 12 -> CardTier(4, "幻彩", Color.rgb(171, 111, 255), 2f, "幻彩 $count")
            count >= 6 -> CardTier(3, "金耀", Color.rgb(242, 190, 61), 2f, "金耀 $count")
            count >= 3 -> CardTier(2, "银曜", Color.rgb(159, 199, 224), 1.5f, "银曜 $count")
            count >= 1 -> CardTier(1, "铜辉", Color.rgb(194, 119, 74), 1.5f, "铜辉 $count")
            else -> CardTier(0, "普通", Color.rgb(82, 82, 86), 1f, "")
        }

        fun frame(count: Int, density: Float): Drawable = CardFrameDrawable(forCount(count), density)
    }
}

/** Distinct constructions inspired by mature collectible-card rarity frames. */
private class CardFrameDrawable(private val tier: CardTier, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.MITER }
    override fun draw(canvas: Canvas) {
        val inset = tier.widthDp * density
        val r = RectF(bounds).apply { inset(inset, inset) }
        paint.strokeWidth = inset.coerceAtLeast(1f)
        paint.shader = if (tier.level == 4) LinearGradient(r.left, r.top, r.right, r.bottom,
            intArrayOf(Color.rgb(67,190,255), Color.rgb(157,91,255), Color.rgb(255,91,126), Color.rgb(250,194,72)), null, Shader.TileMode.CLAMP) else null
        paint.color = tier.color
        canvas.drawRoundRect(r, 5*density, 5*density, paint)
        when (tier.level) {
            1 -> {
                val c=10*density; canvas.drawLine(r.left,r.top+c,r.left+c,r.top,paint); canvas.drawLine(r.right-c,r.bottom,r.right,r.bottom-c,paint)
            }
            2 -> {
                val inner=RectF(r).apply { inset(3*density,3*density) }; paint.strokeWidth=density; canvas.drawRoundRect(inner,3*density,3*density,paint)
            }
            3 -> {
                val c=12*density; paint.strokeWidth=3*density
                canvas.drawLine(r.left,r.top+c,r.left,r.top,paint); canvas.drawLine(r.left,r.top,r.left+c,r.top,paint)
                canvas.drawLine(r.right-c,r.top,r.right,r.top,paint); canvas.drawLine(r.right,r.top,r.right,r.top+c,paint)
                canvas.drawLine(r.left,r.bottom-c,r.left,r.bottom,paint); canvas.drawLine(r.left,r.bottom,r.left+c,r.bottom,paint)
                canvas.drawLine(r.right-c,r.bottom,r.right,r.bottom,paint); canvas.drawLine(r.right,r.bottom,r.right,r.bottom-c,paint)
            }
            5 -> {
                val inner=RectF(r).apply { inset(4*density,4*density) }; paint.shader=null; paint.color=Color.rgb(151,91,238); paint.strokeWidth=1.4f*density; canvas.drawRoundRect(inner,3*density,3*density,paint)
            }
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha=alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter=colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
