import android.content.*
import android.net.Uri
import com.localfeed.app.core.*
import com.localfeed.app.data.*

fun main() {
    val context=Context(); val root="content://files/tree/Archive"
    val uri=root+"/document/photo-id"
    context.nodes[root]=Node("Archive",true)
    context.nodes[uri]=Node("photo.jpg",bytes="photo".toByteArray())
    context.nodes[root]!!.children+=uri
    val db=MediaIndexDb(context)
    val unrelated=MediaRecord(id=80L,uri=root+"/document/unrelated",rootUri=root,name="keep.jpg",
        mime="image/jpeg",kind=MediaKind.IMAGE,size=9L,likeCount=90,favorited=true)
    db.rows[80L]=unrelated
    val scanner=TreeScanner(context,db)
    val added=scanner.indexArchived(Uri.parse(root),Uri.parse(uri))!!
    check(added.uri==uri && added.rootUri==root && added.relativePath=="photo.jpg")
    check(added.width==400 && added.height==600)
    check(db.rows[80L]==unrelated && db.pruneCalls==0 && context.contentResolver.openCursors==0)
    val alias="content://files/tree/OtherGrant/document/photo-id"
    val resumed=scanner.indexArchived(Uri.parse(root),Uri.parse(alias))!!
    check(resumed.id==added.id && db.rows.size==2)
    context.nodes[uri]!!.name=".localfeed-hidden.part"
    check(scanner.indexArchived(Uri.parse(root),Uri.parse(uri))==null)
    check(runCatching { scanner.indexArchived(Uri.parse(root),Uri.parse(root+"/document/missing")) }.isFailure)
    check(db.rows.size==2 && db.pruneCalls==0 && context.contentResolver.openCursors==0)
    // A stale metadata task after deletion is skipped, not turned into a new problem row.
    db.rows.remove(added.id)
    scanner.enrichMetadata(listOf(TreeScanner.MetadataTask(Uri.parse(uri),MediaKind.IMAGE,"photo.jpg",false)))
    check(db.rows.size==1)
    println("Incremental archive indexing, canonical URI, no root pruning and closed cursors: PASS")
}
