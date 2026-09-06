package com.localfeed.app.data

data class HashState(val quickHash: String, val fullHash: String, val hashSize: Long, val hashModifiedAt: Long)
data class VisualHashState(val hashes: String, val version: Int, val size: Long, val modifiedAt: Long)
class MediaIndexDb {
    val visual = mutableMapOf<Long, VisualHashState>()
    val exact = mutableMapOf<Long, HashState>()
    val excluded = mutableSetOf<Pair<Long, Long>>()
    var errors = 0
    fun hashState(id: Long): HashState? = exact[id]
    fun notDuplicatePairs(): Set<Pair<Long, Long>> = excluded.toSet()
    fun setSimilarDecision(a: Long, b: Long, decision: String) { check(decision == "NOT_DUPLICATE"); excluded += minOf(a,b) to maxOf(a,b) }
    fun visualHashState(id: Long): VisualHashState? = visual[id]
    fun updateVisualHashes(id: Long, hashes: String, version: Int, size: Long, modifiedAt: Long) { visual[id] = VisualHashState(hashes,version,size,modifiedAt) }
    fun recordError(uri: String, name: String, stage: String, message: String) { errors++ }
}
