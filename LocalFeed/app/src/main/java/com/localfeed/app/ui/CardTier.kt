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
        private var limits = intArrayOf(1, 3, 6, 12, 25)
        fun configure(values: IntArray) {
            if (values.size == 5 && (0 until values.lastIndex).all { values[it] < values[it + 1] }) limits = values.copyOf()
        }
        fun thresholds(): IntArray = limits.copyOf()
        fun forCount(count: Int): CardTier = when {
            count >= limits[4] -> CardTier(5, "典藏", Color.rgb(235, 196, 92), 2.6f, "")
            count >= limits[3] -> CardTier(4, "幻彩", Color.rgb(171, 111, 255), 2.2f, "")
            count >= limits[2] -> CardTier(3, "金耀", Color.rgb(226, 178, 71), 2f, "")
            count >= limits[1] -> CardTier(2, "银曜", Color.rgb(177, 190, 201), 1.7f, "")
            count >= limits[0] -> CardTier(1, "铜辉", Color.rgb(181, 111, 69), 1.8f, "")
            else -> CardTier(0, "普通", Color.rgb(82, 82, 86), 1f, "")
        }

        fun frame(count: Int, density: Float): Drawable = CardFrameDrawable(forCount(count), density)
    }
}

/** Quiet thumbnail frames: material and light distinguish tiers without labels or corner clutter. */
private class CardFrameDrawable(private val tier: CardTier, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.MITER }
    override fun draw(canvas: Canvas) {
        val inset = (tier.widthDp + .45f) * density
        val r = RectF(bounds).apply { inset(inset, inset) }
        val colors = when(tier.level) {
            1 -> intArrayOf(0xFF56301F.toInt(),0xFFD58A54.toInt(),0xFF6E3A25.toInt(),0xFFF0B27A.toInt(),0xFF4B281C.toInt())
            2 -> intArrayOf(0xFF59636C.toInt(),0xFFE7EEF3.toInt(),0xFF778691.toInt(),0xFFFFFFFF.toInt(),0xFF4A545C.toInt())
            3 -> intArrayOf(0xFF6E4B17.toInt(),0xFFFFDF7A.toInt(),0xFFB77A20.toInt(),0xFFFFF0AB.toInt(),0xFF72501E.toInt())
            4 -> intArrayOf(0xFF26333B.toInt(),0xFF66D9F4.toInt(),0xFFB478EF.toInt(),0xFFF284AF.toInt(),0xFFF2CB6B.toInt(),0xFF29353B.toInt())
            5 -> intArrayOf(0xFF5E431F.toInt(),0xFFFFEAB0.toInt(),0xFFFFFFFF.toInt(),0xFFC98A37.toInt(),0xFFFFE8A0.toInt(),0xFF5B4020.toInt())
            else -> intArrayOf(0xFF35363A.toInt(),0xFF62646A.toInt(),0xFF333438.toInt())
        }
        paint.shader=LinearGradient(r.left,r.top,r.right,r.bottom,colors,null,Shader.TileMode.CLAMP)
        paint.strokeWidth=(tier.widthDp*density).coerceAtLeast(1f)
        paint.alpha=if(tier.level==0) 155 else 245
        canvas.drawRoundRect(r,6*density,6*density,paint)
        if(tier.level>0) {
            val inner=RectF(r).apply { inset(2.7f*density,2.7f*density) }
            paint.shader=null
            paint.color=when(tier.level) {
                1 -> 0x88FFD0A3.toInt()
                2 -> 0x99FFFFFF.toInt()
                3 -> 0x99FFF0B0.toInt()
                4 -> 0x886EEAFF.toInt()
                else -> 0xAAFFF1BF.toInt()
            }
            paint.strokeWidth=.65f*density
            paint.alpha=190
            canvas.drawRoundRect(inner,4*density,4*density,paint)
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha=alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter=colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
