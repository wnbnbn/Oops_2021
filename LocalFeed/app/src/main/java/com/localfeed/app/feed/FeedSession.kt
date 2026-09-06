package com.localfeed.app.feed

import com.localfeed.app.core.MediaKind
import com.localfeed.app.core.MediaRecord
import com.localfeed.app.core.WeightedFeedEngine
import com.localfeed.app.core.RandomPreferences

/**
 * Runtime queue for the short-video feed.
 *
 * Images deliberately never enter this source. Images belong to the album/image viewer only.
 */
class FeedSession(source: List<MediaRecord>) {
    private var source = source.filter { it.kind == MediaKind.VIDEO && !it.hidden }
    private val engine = WeightedFeedEngine(this.source)
    val queue = mutableListOf<MediaRecord>()

    fun rebuild(first: MediaRecord? = null, count: Int = 50) {
        queue.clear()
        engine.resetRecent()
        if (first != null && first.kind == MediaKind.VIDEO && !first.hidden) {
            queue += first
            engine.markShown(first.id)
        }
        append(count - queue.size)
    }

    fun replaceSource(newSource: List<MediaRecord>) {
        source = newSource.filter { it.kind == MediaKind.VIDEO && !it.hidden }
        engine.replaceSource(source)
        val byId = source.associateBy { it.id }
        queue.replaceAll { old -> byId[old.id] ?: old }
        queue.removeAll { it.id !in byId }
    }

    fun updateRecord(record: MediaRecord) {
        if (record.kind != MediaKind.VIDEO) return
        source = source.map { if (it.id == record.id) record else it }
        for (i in queue.indices) if (queue[i].id == record.id) queue[i] = record
        engine.replaceSource(source)
    }

    fun remove(id: Long) {
        source = source.filterNot { it.id == id }
        queue.removeAll { it.id == id }
        engine.replaceSource(source)
    }

    fun ensureAhead(position: Int, minAhead: Int = 20): Int {
        val oldSize = queue.size
        if (queue.size - position < minAhead) append(30)
        return queue.size - oldSize
    }

    fun hasVideos(): Boolean = source.isNotEmpty()

    fun setContentGroups(groups: Map<Long, Long>) {
        engine.setContentGroups(groups)
    }

    fun setRandomPreferences(value: RandomPreferences) {
        engine.setPreferences(value)
    }

    private fun append(count: Int) {
        repeat(count.coerceAtLeast(0)) {
            val next = engine.next() ?: return
            queue += next
            engine.markShown(next.id)
        }
    }
}
