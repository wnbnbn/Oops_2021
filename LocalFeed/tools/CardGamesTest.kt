import com.localfeed.app.core.*
fun main() {
    for (size in listOf(8,16,32)) {
        val g = CardDuel((1L..size.toLong()).toList())
        while (g.pair != null) g.choose(g.pair!!.first)
        check(g.champion == 1L && g.history.size == size-1)
    }
    val s = CardDuel(listOf(1,2,3,4), true)
    s.choose(1); s.choose(3); s.choose(3)
    check(s.champion == 3L && s.bestStreak == 2)
    val m = MemoryPairs(listOf(1,2,1,2))
    check(m.flip(0)); check(!m.flip(0)); m.flip(1); check(!m.flip(2)); m.closeMismatch()
    m.flip(0); m.flip(2); m.flip(1); m.flip(3)
    check(m.done && m.turns == 3)
    println("Card games: PASS")
}
