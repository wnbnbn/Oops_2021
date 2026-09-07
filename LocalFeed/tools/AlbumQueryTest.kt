import com.localfeed.app.core.MediaKind
import com.localfeed.app.core.MediaRecord
import com.localfeed.app.ui.*

private fun video(id: Long, path: String, duration: Long, w: Int = 1920, h: Int = 1080, fav: Boolean = false) = MediaRecord(
    id=id, uri="content://$id", rootUri="root", relativePath=path, name=path.substringAfterLast('/'), mime="video/mp4", kind=MediaKind.VIDEO,
    size=id*1000, durationMs=duration, width=w, height=h, favorited=fav, showCount=if (id % 2L == 0L) 1 else 0
)

fun main() {
    val src = listOf(
        video(1,"a/one.mp4",60_000),
        video(2,"a/two.mp4",900_000, fav=true),
        video(3,"b/three.mp4",900_000,1080,1920),
        MediaRecord(4,"content://4","root","b/pic.jpg","pic.jpg","image/jpeg",MediaKind.IMAGE,10)
    )
    val longOnly = AlbumQueryEngine.apply(src, AlbumQueryState(type=AlbumType.VIDEO,length=AlbumLength.LONG), 600_000, emptySet())
    check(longOnly.map { it.id }.toSet() == setOf(2L,3L))
    val folderFavLandscape = AlbumQueryEngine.apply(src, AlbumQueryState(type=AlbumType.VIDEO,orientation=AlbumOrientation.LANDSCAPE,special=AlbumSpecial.FAVORITE,rootUri="root",folderPrefix="a"),600_000,emptySet())
    check(folderFavLandscape.map { it.id } == listOf(2L))
    val dup = AlbumQueryEngine.apply(src, AlbumQueryState(special=AlbumSpecial.DUPLICATE),600_000,setOf(1L,3L))
    check(dup.map { it.id }.toSet() == setOf(1L,3L))
    val collectionSrc = src + video(5,"c/five.mp4",60_000).copy(liked=true, likeCount=7)
    val collection = AlbumQueryEngine.apply(collectionSrc, AlbumQueryState(special=AlbumSpecial.COLLECTION, collectionThreshold=7),600_000,emptySet())
    check(collection.map { it.id } == listOf(5L))
    println("album-query test=PASS")
}
