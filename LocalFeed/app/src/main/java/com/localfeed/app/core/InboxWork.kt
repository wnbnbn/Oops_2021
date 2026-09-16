package com.localfeed.app.core

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** Process-wide: separate activities/repositories must not mutate SAF files concurrently. */
object InboxWork {
    val storageLock = ReentrantLock(true)
    private val active = AtomicBoolean(false)
    fun begin(): Boolean = active.compareAndSet(false, true)
    fun end() { active.set(false) }
}

/** Coalesce arrivals by document, publish bounded batches rather than a snapshot per file. */
class InboxBatchQueue<T> {
    private val pending = LinkedHashMap<String, T>()
    @Synchronized fun offer(key: String, value: T) { pending[key] = value }
    @Synchronized fun drain(limit: Int): List<T> {
        require(limit > 0)
        val out = ArrayList<T>(minOf(limit, pending.size))
        val iterator = pending.entries.iterator()
        while (iterator.hasNext() && out.size < limit) {
            out += iterator.next().value
            iterator.remove()
        }
        return out
    }
    @Synchronized fun isEmpty(): Boolean = pending.isEmpty()
}
