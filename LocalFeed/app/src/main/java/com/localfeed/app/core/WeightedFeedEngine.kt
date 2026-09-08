package com.localfeed.app.core

import kotlin.random.Random

data class RandomPreferences(
    val liked: Boolean = false,
    val favorite: Boolean = false,
    val unseen: Boolean = false
)

/**
 * LocalFeed random feed V2.
 *
 * Randomness is the primary rule. Like/favorite only add a mild bias and there is no
 * long recent-history exclusion window. The only hard guard is preventing the exact
 * same item from being generated twice in a row when the library has another choice.
 *
 * Near-duplicate content groups can later be supplied through [setContentGroups].
 * When present, two adjacent generated items will not come from the same group if an
 * alternative exists. This does not permanently suppress the group; it can appear again
 * immediately after another piece of content.
 */
class WeightedFeedEngine(
    private var source: List<MediaRecord>,
    private val random: Random = Random.Default,
    @Suppress("UNUSED_PARAMETER") recentWindow: Int = 1
) {
    private var lastPickedId: Long? = null
    private var contentGroupByMediaId: Map<Long, Long> = emptyMap()
    private var preferences = RandomPreferences()

    fun setPreferences(value: RandomPreferences) {
        preferences = value
    }

    fun replaceSource(newSource: List<MediaRecord>) {
        source = newSource.filterNot { it.hidden }
        if (lastPickedId != null && source.none { it.id == lastPickedId }) lastPickedId = null
        if (contentGroupByMediaId.isNotEmpty()) {
            val validIds = source.asSequence().map { it.id }.toHashSet()
            contentGroupByMediaId = contentGroupByMediaId.filterKeys { it in validIds }
        }
    }

    fun resetRecent() {
        // Kept for FeedSession compatibility. V2 has no long recent history.
        lastPickedId = null
    }

    fun markShown(id: Long) {
        // FeedSession currently calls this when queue entries are generated. Keeping the latest
        // generated id here is enough to prevent adjacent duplicates without biasing the queue.
        lastPickedId = id
    }

    /**
     * Associate near-duplicate media with one content group. Missing ids are treated as unique.
     * The map is deliberately ephemeral; scanner persistence belongs to the data layer.
     */
    fun setContentGroups(groups: Map<Long, Long>) {
        contentGroupByMediaId = groups
    }

    fun next(landscapeOnly: Boolean = false, excluding: Set<Long> = emptySet()): MediaRecord? {
        val base = source.asSequence()
            .filterNot { it.hidden || it.id in excluding }
            .filter { !landscapeOnly || it.isLandscape() }
            .toList()
        if (base.isEmpty()) return null
        if (base.size == 1) return base.first()

        val lastId = lastPickedId
        val lastGroup = lastId?.let(contentGroupByMediaId::get)

        // First avoid exact adjacent repeat. If near-duplicate groups are known, also avoid an
        // adjacent item from the same group. Both constraints are relaxed if they exhaust choices.
        var candidates = base.filterNot { it.id == lastId || (lastGroup != null && contentGroupByMediaId[it.id] == lastGroup) }
        if (candidates.isEmpty()) candidates = base.filterNot { it.id == lastId }
        if (candidates.isEmpty()) candidates = base

        val total = candidates.sumOf(::weight)
        if (total <= 0.0) return candidates.random(random)
        var needle = random.nextDouble(total)
        for (item in candidates) {
            needle -= weight(item)
            if (needle <= 0.0) return item
        }
        return candidates.last()
    }

    /**
     * Preference is intentionally mild: random selection must remain the dominant experience.
     * Show count and viewing duration are not used as negative feedback.
     */
    fun weight(item: MediaRecord): Double {
        var value = 1.0
        if (preferences.liked && item.liked) value *= 1.10
        if (preferences.favorite && item.favorited) value *= 1.15
        if (preferences.unseen && item.lastShownAt <= 0L) value *= 1.10
        return value.coerceAtMost(1.35)
    }
}
