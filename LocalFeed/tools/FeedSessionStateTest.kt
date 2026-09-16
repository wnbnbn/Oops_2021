import com.localfeed.app.core.MediaKind
import com.localfeed.app.core.MediaRecord
import com.localfeed.app.feed.FeedSession

fun main() {
    val videos = (1L..80L).map { id ->
        MediaRecord(
            id = id,
            uri = "content://test/$id",
            rootUri = "content://test",
            name = "$id.mp4",
            mime = "video/mp4",
            kind = MediaKind.VIDEO,
            size = 1000,
            width = 1920,
            height = 1080
        )
    }
    val image = MediaRecord(
        id = 999L,
        uri = "content://test/999",
        rootUri = "content://test",
        name = "999.jpg",
        mime = "image/jpeg",
        kind = MediaKind.IMAGE,
        size = 1000,
        width = 1080,
        height = 1440
    )
    val source = videos + image
    val session = FeedSession(source)
    session.rebuild(first = videos.first(), count = 30)
    check(session.queue.first().id == 1L)
    check(session.queue.none { it.kind == MediaKind.IMAGE }) { "images must never enter Feed" }

    val updated = videos.first().copy(liked = true, favorited = true)
    session.updateRecord(updated)
    check(session.queue.first().liked && session.queue.first().favorited)

    session.remove(1L)
    check(session.queue.none { it.id == 1L })

    val before = session.queue.size
    val added = session.ensureAhead(position = before - 1, minAhead = 20)
    check(added > 0)
    check(session.queue.drop(before).all { it.kind == MediaKind.VIDEO })

    val ordered = listOf(videos[7], videos[2], videos[19])
    session.rebuildOrdered(ordered)
    check(session.isOrdered())
    check(session.queue.map { it.id } == ordered.map { it.id })
    val orderedSize = session.queue.size
    check(session.ensureAhead(orderedSize - 1) == 0)
    check(session.queue.size == orderedSize) { "ordered album playback must not append random media" }
    println("feed-session state test=PASS queue=${session.queue.size}")
}
