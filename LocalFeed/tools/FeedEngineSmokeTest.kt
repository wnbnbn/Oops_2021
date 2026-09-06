import com.localfeed.app.core.MediaKind
import com.localfeed.app.core.MediaRecord
import com.localfeed.app.core.WeightedFeedEngine
import kotlin.random.Random

fun rec(id: Long, liked: Boolean=false, fav: Boolean=false) = MediaRecord(
    id=id, uri="u$id", rootUri="r", name="$id.mp4", mime="video/mp4", kind=MediaKind.VIDEO,
    size=1, width=1920, height=1080, liked=liked, favorited=fav
)

fun main() {
    val items = listOf(rec(1), rec(2, liked=true), rec(3, fav=true), rec(4, liked=true, fav=true), rec(5))
    val engine = WeightedFeedEngine(items, Random(20260906))
    val counts = mutableMapOf<Long, Int>()
    var previous = -1L
    repeat(50000) {
        val x = engine.next()!!
        check(x.id != previous) { "same item must not be generated twice in a row" }
        counts[x.id] = counts.getOrDefault(x.id, 0) + 1
        engine.markShown(x.id)
        previous = x.id
    }
    println("weighted counts=$counts")
    check(counts.getValue(4) > counts.getValue(1))
    check(counts.getValue(3) > counts.getValue(1))
    check(counts.getValue(4) < counts.getValue(1) * 1.8) { "preference bias became too strong for a random feed" }

    val grouped = WeightedFeedEngine(listOf(rec(10), rec(11), rec(20), rec(30)), Random(11))
    grouped.setContentGroups(mapOf(10L to 1L, 11L to 1L))
    var prevId: Long? = null
    repeat(5000) {
        val x = grouped.next()!!
        val prev = prevId
        if (prev != null) {
            val sameGroup = prev in setOf(10L, 11L) && x.id in setOf(10L, 11L)
            check(!sameGroup) { "adjacent near-duplicate group collision" }
        }
        grouped.markShown(x.id)
        prevId = x.id
    }
    println("random-feed-v2 test=PASS")
}
