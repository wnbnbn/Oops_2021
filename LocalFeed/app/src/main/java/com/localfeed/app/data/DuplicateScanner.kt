package com.localfeed.app.data

import android.content.Context
import android.net.Uri
import com.localfeed.app.core.MediaKind
import com.localfeed.app.core.MediaRecord
import java.io.FileInputStream
import java.security.MessageDigest


data class DuplicateGroup(
    val hash: String,
    val items: List<MediaRecord>
) {
    val totalBytes: Long get() = items.sumOf { it.size }
    val reclaimableBytes: Long get() = (items.size - 1).coerceAtLeast(0) * (items.firstOrNull()?.size ?: 0L)
}

class DuplicateScanner(
    private val context: Context,
    private val db: MediaIndexDb
) {
    fun scan(videos: List<MediaRecord>, onProgress: (String) -> Unit): List<DuplicateGroup> {
        val sizeCandidates = videos
            .filter { it.kind == MediaKind.VIDEO && it.size > 0L }
            .groupBy { it.size }
            .values
            .filter { it.size > 1 }
        val candidateCount = sizeCandidates.sumOf { it.size }
        if (candidateCount == 0) return emptyList()

        var done = 0
        val quickGroups = mutableListOf<List<MediaRecord>>()
        sizeCandidates.forEach { sameSize ->
            val byQuick = sameSize.groupBy { record ->
                val state = db.hashState(record.id)
                if (state != null && state.quickHash.isNotBlank() && state.hashSize == record.size && state.hashModifiedAt == record.modifiedAt) {
                    state.quickHash
                } else {
                    val hash = quickHash(record)
                    db.updateQuickHash(record.id, hash, record.size, record.modifiedAt)
                    hash
                }.also {
                    done++
                    if (done % 5 == 0 || done == candidateCount) onProgress("重复扫描 · 快速指纹 $done/$candidateCount")
                }
            }
            quickGroups += byQuick.values.filter { it.size > 1 }
        }

        val fullCandidates = quickGroups.sumOf { it.size }
        done = 0
        val result = mutableListOf<DuplicateGroup>()
        quickGroups.forEach { sameQuick ->
            val byFull = sameQuick.groupBy { record ->
                val state = db.hashState(record.id)
                if (state != null && state.fullHash.isNotBlank() && state.hashSize == record.size && state.hashModifiedAt == record.modifiedAt) {
                    state.fullHash
                } else {
                    val hash = fullHash(record)
                    db.updateFullHash(record.id, hash, record.size, record.modifiedAt)
                    hash
                }.also {
                    done++
                    if (done % 2 == 0 || done == fullCandidates) onProgress("重复扫描 · 完整校验 $done/$fullCandidates")
                }
            }
            byFull.forEach { (hash, items) -> if (items.size > 1) result += DuplicateGroup(hash, items) }
        }
        return result.sortedByDescending { it.reclaimableBytes }
    }

    private fun quickHash(record: MediaRecord): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(record.size.toString().toByteArray())
        return try {
            context.contentResolver.openFileDescriptor(Uri.parse(record.uri), "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { input ->
                    val ch = input.channel
                    val len = ch.size()
                    val sample = 64 * 1024L
                    val positions = longArrayOf(0L, (len / 2L - sample / 2L).coerceAtLeast(0L), (len - sample).coerceAtLeast(0L))
                    val buffer = java.nio.ByteBuffer.allocate(sample.toInt())
                    positions.distinct().forEach { pos ->
                        ch.position(pos)
                        buffer.clear()
                        val read = ch.read(buffer)
                        if (read > 0) digest.update(buffer.array(), 0, read)
                    }
                }
            } ?: return fullHash(record)
            digest.digest().toHex()
        } catch (_: Exception) {
            fullHash(record)
        }
    }

    private fun fullHash(record: MediaRecord): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(Uri.parse(record.uri))?.use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        } ?: throw IllegalStateException("无法读取文件")
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
