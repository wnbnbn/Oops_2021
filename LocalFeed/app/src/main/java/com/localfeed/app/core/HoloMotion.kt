package com.localfeed.app.core

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
}
