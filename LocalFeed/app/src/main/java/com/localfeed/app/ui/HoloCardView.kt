package com.localfeed.app.ui

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import com.localfeed.app.core.HoloMotion
import kotlin.math.hypot

/**
 * Native holo surface. It observes touch in dispatchTouchEvent so click/long-click ownership stays
 * with the existing card game. Effects are clipped to the actual card, never its weighted slot.
 */
class HoloCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    var dynamic = true
    /** A dedicated preview must always look holographic, including a zero-like ordinary card. */
    var minimumEffectLevel = 0
        set(value) {
            field = value.coerceIn(0, 5)
            invalidate()
        }
    /** Slow idle sweep used by the dedicated viewer; game cards stay touch-driven. */
    var ambientMotion = false
        set(value) {
            field = value
            if (value && isAttachedToWindow) startAmbient() else stopAmbient()
        }
    private var tier = CardTier.forCount(0)
    private val density = resources.displayMetrics.density
    private val rect = RectF()
    private val clip = Path()
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ridgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glarePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shaderMatrix = Matrix()
    private var px = 0f
    private var py = 0f
    private var returnAnimator: ValueAnimator? = null
    private var ambientAnimator: ValueAnimator? = null
    private var touching = false

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        // Keep receiving MOVE/UP even when the card's child ImageView has no click listener.
        isClickable = true
        cameraDistance = 8000f * density
        if (Build.VERSION.SDK_INT >= 29) {
            shinePaint.blendMode = BlendMode.SCREEN
            ridgePaint.blendMode = BlendMode.SCREEN
            glarePaint.blendMode = BlendMode.SCREEN
        } else {
            @Suppress("DEPRECATION")
            PorterDuffXfermode(PorterDuff.Mode.SCREEN).also {
                shinePaint.xfermode = it; ridgePaint.xfermode = it; glarePaint.xfermode = it
            }
        }
    }

    fun setLikeCount(count: Int) {
        tier = CardTier.forCount(count)
        background = CardTier.frame(count, density)
        invalidate()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (dynamic) when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                stopAmbient()
                returnAnimator?.cancel()
                animate().cancel()
                updatePose(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> updatePose(event.x, event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                returnToRest()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun updatePose(x: Float, y: Float) {
        val pose = HoloMotion.pose(width, height, x, y)
        px = pose.x
        py = pose.y
        rotationX = pose.rotationX
        rotationY = pose.rotationY
        invalidate()
    }

    private fun returnToRest() {
        val sx = px; val sy = py
        returnAnimator?.cancel()
        returnAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360L
            interpolator = android.view.animation.DecelerateInterpolator(1.8f)
            addUpdateListener {
                val remain = 1f - it.animatedFraction
                px = sx * remain; py = sy * remain
                rotationX = -py * 5.5f
                rotationY = px * 5.5f
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!touching && ambientMotion) startAmbient()
                }
            })
            start()
        }
    }

    private fun startAmbient() {
        if (!ambientMotion || !isAttachedToWindow || touching || ambientAnimator?.isRunning == true) return
        ambientAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2800L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener {
                val pose = HoloMotion.ambient(it.animatedValue as Float)
                px = pose.x
                py = pose.y
                rotationX = pose.rotationX
                rotationY = pose.rotationY
                invalidate()
            }
            start()
        }
    }

    private fun stopAmbient() {
        ambientAnimator?.cancel()
        ambientAnimator = null
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val effectLevel = maxOf(tier.level, minimumEffectLevel)
        if (width <= 0 || height <= 0 || effectLevel == 0) return
        val inset = 3f * density
        rect.set(inset, inset, width - inset, height - inset)
        val radius = 8f * density
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        val checkpoint = canvas.save()
        canvas.clipPath(clip)

        val activity = hypot(px.toDouble(), py.toDouble()).toFloat().coerceIn(0f, 1f)
        val levelStrength = when (effectLevel) {
            1 -> 0.05f
            2 -> 0.08f
            3 -> 0.12f
            4 -> 0.30f
            else -> 0.38f
        }
        val colors = intArrayOf(
            0x00FF446E, 0xB8FF446E.toInt(), 0xB8FFD84A.toInt(),
            0xB850F4CC.toInt(), 0xB85E7BFF.toInt(), 0xB8D85CFF.toInt(), 0x00FF446E
        )
        val shine = LinearGradient(
            -width.toFloat(), 0f, width * 2f, height.toFloat(),
            colors, null, Shader.TileMode.CLAMP
        )
        shaderMatrix.reset()
        shaderMatrix.setTranslate(px * width * 0.72f, py * height * 0.32f)
        shine.setLocalMatrix(shaderMatrix)
        shinePaint.shader = shine
        shinePaint.alpha = (255f * (levelStrength + activity * levelStrength)).toInt().coerceIn(0, 170)
        canvas.drawRoundRect(rect, radius, radius, shinePaint)

        if (effectLevel >= 4) {
            ridgePaint.shader = LinearGradient(
                0f, 0f, 18f * density, 18f * density,
                intArrayOf(0x00FFFFFF, 0x58FFFFFF, 0x00FFFFFF, 0x24FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, .08f, .18f, .55f, 1f), Shader.TileMode.REPEAT
            )
            shaderMatrix.reset()
            shaderMatrix.setTranslate(px * 24f * density, py * 18f * density)
            ridgePaint.shader?.setLocalMatrix(shaderMatrix)
            ridgePaint.alpha = if (effectLevel == 5) 90 else 62
            canvas.drawRoundRect(rect, radius, radius, ridgePaint)
        }

        val gx = width * (0.5f + px * 0.42f)
        val gy = height * (0.5f + py * 0.42f)
        glarePaint.shader = RadialGradient(
            gx, gy, maxOf(width, height) * .62f,
            intArrayOf(0xC8FFFFFF.toInt(), 0x38FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, .28f, 1f), Shader.TileMode.CLAMP
        )
        glarePaint.alpha = when (effectLevel) {
            1 -> 28
            2 -> 38
            3 -> 52
            4 -> (72 + activity * 55).toInt()
            else -> (90 + activity * 65).toInt()
        }
        canvas.drawRoundRect(rect, radius, radius, glarePaint)
        canvas.restoreToCount(checkpoint)

        if (effectLevel == 5) {
            edgePaint.shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(0xFFFFE8A3.toInt(), 0xFFFFFFFF.toInt(), 0xFFB9863B.toInt(), 0xFFFFE9A8.toInt()),
                null, Shader.TileMode.CLAMP
            )
            edgePaint.strokeWidth = 1.1f * density
            edgePaint.alpha = 225
            val inner = RectF(rect).apply { inset(4f * density, 4f * density) }
            canvas.drawRoundRect(inner, radius * .65f, radius * .65f, edgePaint)
        }
    }

    override fun onDetachedFromWindow() {
        stopAmbient()
        returnAnimator?.cancel()
        animate().cancel()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ambientMotion) startAmbient()
    }
}
