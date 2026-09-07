package com.localfeed.app.ui

import com.localfeed.app.core.MediaKind
import com.localfeed.app.core.MediaRecord

enum class AlbumType { ALL, VIDEO, IMAGE }
enum class AlbumLength { ANY, SHORT, LONG }
enum class AlbumOrientation { ANY, LANDSCAPE, PORTRAIT }
enum class AlbumSpecial { NONE, RECENT_ADDED, RECENT_VIEWED, UNSEEN, LIKED, FAVORITE, COLLECTION, PROBLEM, LARGE, DUPLICATE }
enum class AlbumSort { FILE_TIME, ADDED_TIME, DURATION, SIZE, RECENT_VIEWED, NAME, RANDOM }
enum class TimeGrouping { NONE, FILE_DAY, ADDED_DAY }

data class AlbumQueryState(
    val type: AlbumType = AlbumType.ALL,
    val length: AlbumLength = AlbumLength.ANY,
    val orientation: AlbumOrientation = AlbumOrientation.ANY,
    val special: AlbumSpecial = AlbumSpecial.NONE,
    val rootUri: String? = null,
    val folderPrefix: String? = null,
    val sort: AlbumSort = AlbumSort.ADDED_TIME,
    val descending: Boolean = true,
    val grouping: TimeGrouping = TimeGrouping.ADDED_DAY,
    val randomSeed: Long = 0x4C6F63616C466565L
)

object AlbumQueryEngine {
    fun apply(
        source: List<MediaRecord>,
        state: AlbumQueryState,
        longVideoMs: Long,
        duplicateIds: Set<Long>,
        problemIds: Set<Long> = emptySet(),
        now: Long = System.currentTimeMillis()
    ): List<MediaRecord> {
        var result = source.asSequence()
        result = when (state.type) {
            AlbumType.ALL -> result
            AlbumType.VIDEO -> result.filter { it.kind == MediaKind.VIDEO }
            AlbumType.IMAGE -> result.filter { it.kind == MediaKind.IMAGE }
        }
        result = when (state.length) {
            AlbumLength.ANY -> result
            AlbumLength.SHORT -> result.filter { it.kind == MediaKind.VIDEO && it.durationMs in 1 until longVideoMs }
            AlbumLength.LONG -> result.filter { it.kind == MediaKind.VIDEO && it.durationMs >= longVideoMs }
        }
        result = when (state.orientation) {
            AlbumOrientation.ANY -> result
            AlbumOrientation.LANDSCAPE -> result.filter { it.kind == MediaKind.VIDEO && it.isLandscape() }
            AlbumOrientation.PORTRAIT -> result.filter { it.kind == MediaKind.VIDEO && it.width > 0 && it.height > 0 && !it.isLandscape() }
        }
        state.rootUri?.let { root -> result = result.filter { it.rootUri == root } }
        state.folderPrefix?.takeIf { it.isNotBlank() }?.let { prefix ->
            val clean = prefix.trim('/')
            result = result.filter {
                val parent = it.parentRelativePath().trim('/')
                parent == clean || parent.startsWith("$clean/")
            }
        }
        result = when (state.special) {
            AlbumSpecial.NONE -> result
            AlbumSpecial.RECENT_ADDED -> result.filter { now - it.addedAt <= 30L * 24 * 60 * 60 * 1000 }
            AlbumSpecial.RECENT_VIEWED -> result.filter { it.lastShownAt > 0 }
            AlbumSpecial.UNSEEN -> result.filter { it.showCount == 0 }
            AlbumSpecial.LIKED -> result.filter { it.liked }
            AlbumSpecial.FAVORITE -> result.filter { it.favorited }
            AlbumSpecial.COLLECTION -> result.filter { it.likeCount >= 6 }
            AlbumSpecial.PROBLEM -> result.filter { it.id in problemIds }
            AlbumSpecial.LARGE -> result.filter { it.size >= 500L * 1024 * 1024 }
            AlbumSpecial.DUPLICATE -> result.filter { it.id in duplicateIds }
        }
        val list = result.toList()
        if (state.sort == AlbumSort.RANDOM) {
            // A deterministic session seed prevents every DB refresh from replacing the list
            // underneath the reader. A new seed is generated only when the user asks to reshuffle.
            return list.sortedBy { stableRandomKey(it.id, state.randomSeed) }
        }
        val comparator = when (state.sort) {
            AlbumSort.FILE_TIME -> compareBy<MediaRecord> { it.modifiedAt }
            AlbumSort.ADDED_TIME -> compareBy { it.addedAt }
            AlbumSort.DURATION -> compareBy { it.durationMs }
            AlbumSort.SIZE -> compareBy { it.size }
            AlbumSort.RECENT_VIEWED -> compareBy { it.lastShownAt }
            AlbumSort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            AlbumSort.RANDOM -> compareBy { it.id }
        }
        return list.sortedWith(if (state.descending) comparator.reversed() else comparator)
    }

    private fun stableRandomKey(id: Long, seed: Long): Long {
        var z = id xor seed
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }
}
