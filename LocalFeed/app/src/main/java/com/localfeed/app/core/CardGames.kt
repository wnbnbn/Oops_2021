package com.localfeed.app.core

/** Pure state machines: games never modify media, likes or files. */
class CardDuel(ids: List<Long>, val survival: Boolean = false) {
    private var round = ids.distinct().toMutableList()
    private val next = mutableListOf<Long>()
    private var cursor = 0
    var streak = 0; private set
    var bestStreak = 0; private set
    val history = mutableListOf<Pair<Long, Long>>()
    init { require(round.size >= 2); require(survival || round.size.countOneBits() == 1) }
    val pair: Pair<Long, Long>? get() = if (round.size > 1 && cursor + 1 < round.size) round[cursor] to round[cursor + 1] else null
    val champion: Long? get() = round.singleOrNull()
    fun choose(id: Long) {
        val p = requireNotNull(pair)
        require(id == p.first || id == p.second)
        history += id to if (id == p.first) p.second else p.first
        if (survival) {
            streak = if (id == p.first) streak + 1 else 1
            bestStreak = maxOf(bestStreak, streak)
            round[cursor + 1] = id
            cursor++
            if (cursor == round.lastIndex) round = mutableListOf(id)
        } else {
            next += id; cursor += 2
            if (cursor >= round.size) { round = next.toMutableList(); next.clear(); cursor = 0 }
        }
    }
}

class MemoryPairs(val cards: List<Long>) {
    val matched = mutableSetOf<Int>()
    val open = mutableListOf<Int>()
    var turns = 0; private set
    init { require(cards.isNotEmpty() && cards.groupingBy { it }.eachCount().values.all { it == 2 }) }
    val done get() = matched.size == cards.size
    fun flip(index: Int): Boolean {
        if (index !in cards.indices || index in matched || index in open || open.size == 2) return false
        open += index
        if (open.size == 2) {
            turns++
            if (cards[open[0]] == cards[open[1]]) { matched.addAll(open); open.clear() }
        }
        return true
    }
    fun closeMismatch() { if (open.size == 2) open.clear() }
}
