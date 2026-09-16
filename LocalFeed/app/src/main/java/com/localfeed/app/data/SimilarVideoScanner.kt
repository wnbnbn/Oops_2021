package com.localfeed.app.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.localfeed.app.core.MediaKind
import com.localfeed.app.core.MediaRecord
import kotlin.math.ceil
import kotlin.math.max


enum class SimilarityLevel { HIGH, POSSIBLE }

data class SimilarVideoPair(
    val first: MediaRecord,
    val second: MediaRecord,
    val level: SimilarityLevel,
    val score: Int,
    val averageDistance: Double
)

data class SimilarVideoGroup(
    val id: Long,
    val items: List<MediaRecord>,
    val level: SimilarityLevel,
    val score: Int,
    val recommendedKeepId: Long
)

data class SimilarVideoScanResult(
    val groups: List<SimilarVideoGroup>,
    val possiblePairs: List<SimilarVideoPair>,
    val analyzedVideos: Int,
    val failedVideos: Int
) {
    val groupedIds: Set<Long> get() = groups.flatMapTo(linkedSetOf()) { g -> g.items.map { it.id } }
    val contentGroupMap: Map<Long, Long> get() = buildMap {
        groups.filter { it.level == SimilarityLevel.HIGH }.forEach { group ->
            group.items.forEach { put(it.id, group.id) }
        }
    }
}

/**
 * On-device near-duplicate scanner for v0.5.
 *
 * It deliberately stays explainable and cheap enough for a phone:
 * 1) extract seven distributed frames,
 * 2) store full/cropped/mirrored 64-bit dHashes,
 * 3) duration-window prefilter,
 * 4) compare corresponding frames with a +/-1 timing slot tolerance,
 * 5) group only high-confidence relationships. Possible matches remain pairs for manual review.
 *
 * This is not semantic similarity and does not use a neural network. A user's explicit
 * "not duplicate" decision is persisted and excluded from future scans.
 */
class SimilarVideoScanner(
    private val context: Context,
    private val db: MediaIndexDb
) {
    companion object {
        private const val HASH_VERSION = 2
        private val FRAME_FRACTIONS = doubleArrayOf(0.08, 0.22, 0.36, 0.50, 0.64, 0.78, 0.92)
        private const val MID_GATE_DISTANCE = 20
        private const val HIGH_FRAME_DISTANCE = 12
        private const val POSSIBLE_FRAME_DISTANCE = 18
    }

    private data class FrameSig(val full: Long, val crop: Long, val mirror: Long, val mirrorCrop: Long)
    private data class Feature(val record: MediaRecord, val frames: List<FrameSig>)

    private var exactStates: Map<Long, HashState> = emptyMap()
    private var excludedPairs: Set<Pair<Long, Long>> = emptySet()

    private fun prepareLookup(videos: List<MediaRecord>) {
        exactStates = videos.mapNotNull { r -> db.hashState(r.id)?.let { r.id to it } }.toMap()
        excludedPairs = db.notDuplicatePairs()
    }

    fun scan(videos: List<MediaRecord>, onProgress: (String) -> Unit): SimilarVideoScanResult {
        val source = videos.filter { it.kind == MediaKind.VIDEO && it.durationMs > 0L && it.trashedAt == 0L && !it.hidden }
        if (source.size < 2) return SimilarVideoScanResult(emptyList(), emptyList(), 0, 0)

        prepareLookup(source)
        var failed = 0
        val features = ArrayList<Feature>(source.size)
        source.forEachIndexed { index, record ->
            val frames = runCatching { loadOrExtract(record) }.getOrNull()
            if (frames == null || frames.size != FRAME_FRACTIONS.size) {
                failed++
                db.recordError(record.uri, record.name, "相似扫描", "无法完整提取七个时间点的视频帧")
            } else features += Feature(record, frames)
            if ((index + 1) % 3 == 0 || index + 1 == source.size) {
                onProgress("相似视频 · 提取视觉指纹 ${index + 1}/${source.size} · 失败 $failed")
            }
        }

        val sorted = features.sortedBy { it.record.durationMs }
        val highPairs = mutableListOf<SimilarVideoPair>()
        val possiblePairs = mutableListOf<SimilarVideoPair>()
        var compared = 0

        for (i in sorted.indices) {
            val a = sorted[i]
            val maxDuration = durationUpperBound(a.record.durationMs)
            var j = i + 1
            while (j < sorted.size && sorted[j].record.durationMs <= maxDuration) {
                val b = sorted[j]
                j++
                if (!durationCompatible(a.record.durationMs, b.record.durationMs)) continue
                if (pairKey(a.record.id, b.record.id) in excludedPairs) continue
                if (knownExactDuplicate(a.record, b.record)) continue

                // Cheap middle-frame gate reduces multi-frame work; equal-duration candidates remain quadratic.
                val midA = a.frames[a.frames.size / 2]
                if ((2..4).minOf { distance(midA, b.frames[it]) } > MID_GATE_DISTANCE) continue

                compared++
                val distances = alignedDistances(a.frames, b.frames)
                if (distances.isEmpty()) continue
                val avg = distances.average()
                val highCount = distances.count { it <= HIGH_FRAME_DISTANCE }
                val possibleCount = distances.count { it <= POSSIBLE_FRAME_DISTANCE }
                val score = similarityScore(avg, possibleCount, distances.size)
                when {
                    highCount >= minOf(5, distances.size) && avg <= 12.5 ->
                        highPairs += SimilarVideoPair(a.record, b.record, SimilarityLevel.HIGH, score, avg)
                    possibleCount >= minOf(4, distances.size) && avg <= 18.0 ->
                        possiblePairs += SimilarVideoPair(a.record, b.record, SimilarityLevel.POSSIBLE, score, avg)
                }
            }
            if ((i + 1) % 10 == 0 || i + 1 == sorted.size) {
                onProgress("相似视频 · 比较 ${i + 1}/${sorted.size} · 候选 $compared · 高度相似 ${highPairs.size}")
            }
        }

        val groups = buildHighGroups(highPairs)
        val groupMap = groups.flatMap { group -> group.items.map { it.id to group.id } }.toMap()
        val remainingPossible = possiblePairs
            .filterNot { groupMap[it.first.id] != null && groupMap[it.first.id] == groupMap[it.second.id] }
            .sortedByDescending { it.score }

        return SimilarVideoScanResult(groups, remainingPossible, features.size, failed)
    }

    fun findSimilar(target: MediaRecord, videos: List<MediaRecord>, onProgress: (String) -> Unit): List<SimilarVideoPair> {
        if (target.kind != MediaKind.VIDEO || target.durationMs <= 0L) return emptyList()
        prepareLookup(videos)
        val targetFrames = runCatching { loadOrExtract(target) }.getOrNull() ?: return emptyList()
        val candidates = videos.filter {
            it.kind == MediaKind.VIDEO && it.id != target.id && it.durationMs > 0L && !it.hidden && it.trashedAt == 0L &&
                durationCompatible(target.durationMs, it.durationMs)
        }
        val out = mutableListOf<SimilarVideoPair>()
        candidates.forEachIndexed { index, other ->
            if (index % 5 == 0 || index + 1 == candidates.size) onProgress("查找相似 · ${index + 1}/${candidates.size}")
            if (pairKey(target.id, other.id) in excludedPairs) return@forEachIndexed
            if (knownExactDuplicate(target, other)) return@forEachIndexed
            val otherFrames = runCatching { loadOrExtract(other) }.getOrNull() ?: return@forEachIndexed
            if ((2..4).minOf { distance(targetFrames[3], otherFrames[it]) } > MID_GATE_DISTANCE) return@forEachIndexed
            val distances = alignedDistances(targetFrames, otherFrames)
            val avg = distances.average()
            val high = distances.count { it <= HIGH_FRAME_DISTANCE }
            val possible = distances.count { it <= POSSIBLE_FRAME_DISTANCE }
            val level = when {
                high >= minOf(5, distances.size) && avg <= 12.5 -> SimilarityLevel.HIGH
                possible >= minOf(4, distances.size) && avg <= 18.0 -> SimilarityLevel.POSSIBLE
                else -> null
            }
            if (level != null) out += SimilarVideoPair(target, other, level, similarityScore(avg, possible, distances.size), avg)
        }
        return out.sortedWith(compareByDescending<SimilarVideoPair> { it.level == SimilarityLevel.HIGH }.thenByDescending { it.score })
    }

    fun markNotDuplicate(firstId: Long, secondId: Long) = db.setSimilarDecision(firstId, secondId, "NOT_DUPLICATE")

    private fun loadOrExtract(record: MediaRecord): List<FrameSig> {
        val state = db.visualHashState(record.id)
        if (state != null && state.version == HASH_VERSION && state.size == record.size && state.modifiedAt == record.modifiedAt && state.hashes.isNotBlank()) {
            decode(state.hashes)?.let { if (it.isNotEmpty()) return it }
        }
        val frames = extract(record)
        if (frames.size != FRAME_FRACTIONS.size) throw IllegalStateException("无法提取视频帧")
        db.updateVisualHashes(record.id, encode(frames), HASH_VERSION, record.size, record.modifiedAt)
        return frames
    }

    private fun extract(record: MediaRecord): List<FrameSig> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(record.uri))
            val durationMs = record.durationMs.takeIf { it > 0L }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return emptyList()
            buildList {
                FRAME_FRACTIONS.forEach { fraction ->
                    val timeUs = (durationMs * fraction * 1000.0).toLong().coerceAtLeast(0L)
                    val frame = if (android.os.Build.VERSION.SDK_INT >= 27) {
                        retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 256, 256)
                    } else retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frame == null) throw IllegalStateException("无法提取视频帧")
                    try { add(signature(frame)) } finally { frame.recycle() }
                }
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun signature(bitmap: Bitmap): FrameSig {
        val fullGrid = grayscaleGrid(bitmap, cropFraction = 0.0)
        val cropGrid = grayscaleGrid(bitmap, cropFraction = 0.07)
        return FrameSig(
            full = dHash(fullGrid, mirrored = false),
            crop = dHash(cropGrid, mirrored = false),
            mirror = dHash(fullGrid, mirrored = true),
            mirrorCrop = dHash(cropGrid, mirrored = true)
        )
    }

    private fun grayscaleGrid(bitmap: Bitmap, cropFraction: Double): IntArray {
        val left = (bitmap.width * cropFraction).toInt()
        val top = (bitmap.height * cropFraction).toInt()
        val width = (bitmap.width - left * 2).coerceAtLeast(1)
        val height = (bitmap.height - top * 2).coerceAtLeast(1)
        val crop = Bitmap.createBitmap(bitmap, left, top, width, height)
        val scaled = Bitmap.createScaledBitmap(crop, 9, 8, true)
        return try {
            val pixels = IntArray(72)
            scaled.getPixels(pixels, 0, 9, 0, 0, 9, 8)
            IntArray(72) { i ->
                val color = pixels[i]
                (((color ushr 16) and 255) * 299 + ((color ushr 8) and 255) * 587 +
                    (color and 255) * 114) / 1000
            }
        } finally {
            if (scaled !== crop && scaled !== bitmap) scaled.recycle()
            if (crop !== bitmap) crop.recycle()
        }
    }

    private fun dHash(grid: IntArray, mirrored: Boolean): Long {
        var hash = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val first = if (mirrored) 8 - x else x
            val second = if (mirrored) 7 - x else x + 1
            if (grid[y * 9 + first] > grid[y * 9 + second]) {
                hash = hash or (1L shl (y * 8 + x))
            }
        }
        return hash
    }

    private fun informative(frame: FrameSig): Boolean =
        java.lang.Long.bitCount(frame.full) in 4..60 || java.lang.Long.bitCount(frame.crop) in 4..60

    private fun distance(a: FrameSig, b: FrameSig): Int {
        // Flat/blank frames have identical dHashes across unrelated clips. They are not evidence.
        if (!informative(a) || !informative(b)) return 64
        fun h(x: Long, y: Long) = java.lang.Long.bitCount(x xor y)
        return minOf(
            h(a.full, b.full), h(a.crop, b.crop), h(a.full, b.crop), h(a.crop, b.full),
            h(a.full, b.mirror), h(a.crop, b.mirrorCrop), h(a.full, b.mirrorCrop), h(a.crop, b.mirror)
        )
    }

    private fun alignedDistances(a: List<FrameSig>, b: List<FrameSig>): List<Int> {
        if (a.size != FRAME_FRACTIONS.size || b.size != FRAME_FRACTIONS.size) return emptyList()
        // Use one shared offset in both directions, never match every frame to a single lucky frame.
        return (-1..1).map { offset ->
            a.indices.mapNotNull { i ->
                val j = i + offset
                if (j in b.indices) distance(a[i], b[j]) else null
            }
        }.minByOrNull { it.average() } ?: emptyList()
    }

    private fun durationCompatible(first: Long, second: Long): Boolean {
        if (first <= 0 || second <= 0) return false
        return maxOf(first, second) <= durationUpperBound(minOf(first, second))
    }

    private fun durationUpperBound(duration: Long): Long {
        val tolerance = max(1_500L, ceil(duration * 0.05).toLong())
        return if (duration > Long.MAX_VALUE - tolerance) Long.MAX_VALUE else duration + tolerance
    }

    private fun knownExactDuplicate(first: MediaRecord, second: MediaRecord): Boolean {
        if (first.size != second.size) return false
        val a = exactStates[first.id] ?: return false
        val b = exactStates[second.id] ?: return false
        return a.fullHash.isNotBlank() && a.fullHash == b.fullHash &&
            a.hashSize == first.size && b.hashSize == second.size &&
            a.hashModifiedAt == first.modifiedAt && b.hashModifiedAt == second.modifiedAt
    }

    private fun similarityScore(average: Double, matching: Int, total: Int): Int {
        if (total == 0) return 0
        return ((1.0 - average / 64.0) * 80.0 + matching.toDouble() / total * 20.0).toInt().coerceIn(0, 100)
    }

    private fun encode(frames: List<FrameSig>): String = frames.joinToString(";") { frame ->
        listOf(frame.full, frame.crop, frame.mirror, frame.mirrorCrop)
            .joinToString(",") { java.lang.Long.toUnsignedString(it, 16).padStart(16, '0') }
    }

    private fun decode(value: String): List<FrameSig>? = runCatching {
        val rows = value.split(';')
        require(rows.size == FRAME_FRACTIONS.size)
        rows.map { row ->
            val fields = row.split(',')
            require(fields.size == 4 && fields.all { it.matches(Regex("[0-9a-fA-F]{16}")) })
            val hashes = fields.map { java.lang.Long.parseUnsignedLong(it, 16) }
            FrameSig(hashes[0], hashes[1], hashes[2], hashes[3])
        }
    }.getOrNull()

    private fun pairKey(first: Long, second: Long): Pair<Long, Long> = minOf(first, second) to maxOf(first, second)

    private fun buildHighGroups(pairs: List<SimilarVideoPair>): List<SimilarVideoGroup> {
        val records = pairs.flatMap { listOf(it.first, it.second) }.associateBy { it.id }
        val parent = records.keys.associateWith { it }.toMutableMap()
        val members = records.keys.associateWith { linkedSetOf(it) }.toMutableMap()
        fun root(id: Long): Long {
            var current = id
            while (parent.getValue(current) != current) {
                parent[current] = parent.getValue(parent.getValue(current))
                current = parent.getValue(current)
            }
            return current
        }
        pairs.sortedByDescending { it.score }.forEach { pair ->
            val a = root(pair.first.id)
            val b = root(pair.second.id)
            if (a != b) {
                // A-B and B-C must never silently override an explicit A-C rejection.
                val conflict = members.getValue(a).any { x ->
                    members.getValue(b).any { y -> pairKey(x, y) in excludedPairs }
                }
                if (!conflict) {
                    val keep = minOf(a, b)
                    val remove = maxOf(a, b)
                    parent[remove] = keep
                    members.getValue(keep).addAll(members.remove(remove)!!)
                }
            }
        }
        val scores = mutableMapOf<Long, Int>()
        pairs.forEach { pair ->
            val r = root(pair.first.id)
            if (r == root(pair.second.id)) scores[r] = minOf(scores[r] ?: 100, pair.score)
        }
        return members.filterValues { it.size >= 2 }.map { (id, ids) ->
            val items = ids.map { records.getValue(it) }.sortedWith(
                compareByDescending<MediaRecord> { it.width.toLong() * it.height }
                    .thenByDescending { it.size.toDouble() / it.durationMs.coerceAtLeast(1) }
                    .thenByDescending { it.favorited }.thenByDescending { it.liked }.thenBy { it.id }
            )
            SimilarVideoGroup(id, items, SimilarityLevel.HIGH, scores[id] ?: 0, items.first().id)
        }.sortedWith(compareByDescending<SimilarVideoGroup> { it.score }.thenBy { it.id })
    }
}
