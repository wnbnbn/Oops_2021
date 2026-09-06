package com.localfeed.app.core

enum class MediaKind { IMAGE, VIDEO }

data class MediaRecord(
    val id: Long,
    val uri: String,
    val rootUri: String,
    val relativePath: String = "",
    val name: String,
    val mime: String,
    val kind: MediaKind,
    val size: Long,
    val modifiedAt: Long = 0L,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val rotation: Int = 0,
    val liked: Boolean = false,
    val favorited: Boolean = false,
    val lastShownAt: Long = 0L,
    val showCount: Int = 0,
    val hidden: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val trashedAt: Long = 0L
) {
    fun displayWidth(): Int = if (rotation % 180 == 0) width else height
    fun displayHeight(): Int = if (rotation % 180 == 0) height else width
    fun aspectRatio(): Float {
        val h = displayHeight()
        return if (h > 0) displayWidth().toFloat() / h else 0f
    }
    fun isLandscape(): Boolean = aspectRatio() >= 1.20f
    fun parentRelativePath(): String = relativePath.substringBeforeLast('/', "")
}
