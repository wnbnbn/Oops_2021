package com.localfeed.app.core

import kotlin.math.sin

data class HoloPose(
    val x: Float,
    val y: Float,
    val rotationX: Float,
    val rotationY: Float
)

/** Pure touch-to-card transform shared by the native renderer and regression tests. */
object HoloMotion {
    fun pose(width: Int, height: Int, touchX: Float, touchY: Float, maxTilt: Float = 5.5f): HoloPose {
        if (width <= 0 || height <= 0) return HoloPose(0f, 0f, 0f, 0f)
        val x = ((touchX / width) * 2f - 1f).coerceIn(-1f, 1f)
        val y = ((touchY / height) * 2f - 1f).coerceIn(-1f, 1f)
        return HoloPose(x, y, -y * maxTilt, x * maxTilt)
    }

    fun ambient(phase: Float, maxTilt: Float = 2.2f): HoloPose {
        val p = phase.coerceIn(0f, 1f)
        val x = -0.82f + p * 1.64f
        val y = sin(p * Math.PI).toFloat() * 0.42f - 0.18f
        return HoloPose(x, y, -y * maxTilt, x * maxTilt)
    }
}
