import android.content.Context
import android.media.MediaMetadataRetriever
import com.localfeed.app.core.MediaKind
import com.localfeed.app.core.MediaRecord
import com.localfeed.app.data.*
import kotlin.random.Random

fun main() {
    val db = MediaIndexDb()
    val scanner = SimilarVideoScanner(Context(), db)
    val random = Random(1909)
    val frames = List(7) { List(4) { random.nextLong() } }
    fun encode(rows: List<List<Long>>) = rows.joinToString(";") { row -> row.joinToString(",") { java.lang.Long.toUnsignedString(it,16).padStart(16,'0') } }
    fun rec(id: Long, duration: Long = 60_000) = MediaRecord(id,"u$id","r",name="$id.mp4",mime="video/mp4",kind=MediaKind.VIDEO,size=1000,modifiedAt=10,durationMs=duration,width=640,height=480)
    fun cache(r: MediaRecord, rows: List<List<Long>> = frames) { db.visual[r.id] = VisualHashState(encode(rows),2,r.size,r.modifiedAt) }
    val a = rec(1); val b = rec(2).copy(width=1920,height=1080); val c = rec(3)
    listOf(a,b,c).forEach { cache(it) }
    val first = scanner.scan(listOf(a,b,c)) {}
    check(first.groups.single().items.size == 3)
    check(first.groups.single().recommendedKeepId == b.id)
    check(first.contentGroupMap.values.toSet().size == 1)
    check(MediaMetadataRetriever.extractionAttempts == 0) { "Valid cache unexpectedly decoded video" }
    check(scanner.findSimilar(a,listOf(a,b,c)) {}.size == 2)
    val mirror = rec(4); cache(mirror,frames.map { listOf(it[2],it[3],it[0],it[1]) })
    check(scanner.findSimilar(a,listOf(a,mirror)) {}.single().level == SimilarityLevel.HIGH)
    val jitter = rec(5); cache(jitter, listOf(frames[0]) + frames.dropLast(1))
    check(scanner.findSimilar(a,listOf(a,jitter)) {}.single().level == SimilarityLevel.HIGH)
    val flat = rec(6); val flat2 = rec(7)
    cache(flat,List(7) { List(4) { 0L } }); cache(flat2,List(7) { List(4) { 0L } })
    check(scanner.scan(listOf(flat,flat2)) {}.groups.isEmpty()) { "Blank frames created a false group" }
    val unrelated = rec(8); cache(unrelated,List(7) { List(4) { random.nextLong() } })
    check(scanner.findSimilar(a,listOf(a,unrelated)) {}.isEmpty())
    val long = rec(9,120_000); cache(long)
    check(scanner.findSimilar(a,listOf(a,long)) {}.isEmpty())
    scanner.markNotDuplicate(c.id,a.id)
    check(scanner.findSimilar(a,listOf(a,c)) {}.isEmpty())
    val excludedGroups = scanner.scan(listOf(a,b,c)) {}.groups
    check(excludedGroups.none { g -> g.items.any { it.id==a.id } && g.items.any { it.id==c.id } }) { "Transitive grouping overrode rejection" }
    check(SimilarVideoScanner(Context(),db).findSimilar(c,listOf(a,c)) {}.isEmpty())
    db.exact[a.id] = HashState("","exact",a.size,a.modifiedAt)
    db.exact[b.id] = HashState("","exact",b.size,b.modifiedAt)
    check(scanner.findSimilar(a,listOf(a,b)) {}.isEmpty())
    db.exact[b.id] = HashState("","exact",b.size,9)
    check(scanner.findSimilar(a,listOf(a,b)) {}.size==1) { "Stale exact hash suppressed candidate" }
    check(scanner.scan(listOf(a,b.copy(hidden=true),c.copy(trashedAt=20))) {}.groups.isEmpty())
    for (bad in listOf(db.visual[b.id]!!.copy(version=1), db.visual[b.id]!!.copy(size=999), db.visual[b.id]!!.copy(modifiedAt=9), db.visual[b.id]!!.copy(hashes="broken"))) {
        db.visual[b.id] = bad
        val before = MediaMetadataRetriever.extractionAttempts
        val result = scanner.scan(listOf(a,b)) {}
        check(result.failedVideos == 1)
        check(MediaMetadataRetriever.extractionAttempts == before + 1) { "Invalid cache was accepted" }
        cache(b)
    }
    println("similar-video scanner smoke=PASS (cache, invalidation, mirror, timing offset, blank rejection, grouping, explicit exclusions, recommendation, exact hash, failed decode)")
}
