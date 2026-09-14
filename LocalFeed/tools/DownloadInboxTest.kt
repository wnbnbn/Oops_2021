import android.content.*
import android.net.Uri
import com.localfeed.app.data.*
import org.json.JSONObject
fun fixture():Pair<Context,DownloadInbox> {
    val c=Context()
    val s="content://files/tree/Pictures/Inbox"; val t="content://files/tree/Archive"
    c.nodes[s]=Node("Inbox",true); c.nodes[t]=Node("Archive",true)
    c.nodes[s+"/image"]=Node("image.jpg",bytes="image bytes".toByteArray())
    c.nodes[s]!!.children+=s+"/image"
    val inbox=DownloadInbox(c); inbox.add(Uri.parse(s),Uri.parse(t)); return c to inbox
}
fun stable(c:Context) { c.prefs.values.keys.filter { it.startsWith("seen:") }.forEach { c.prefs.values[it]=0L } }
fun main() {
    val (c,inbox)=fixture(); val events=mutableListOf<InboxEvent>()
    inbox.scan({ events+=it }); check(events.any { it.status==InboxStatus.WAITING })
    check(c.nodes.containsKey("content://files/tree/Pictures/Inbox/image"))
    stable(c); inbox.scan({events+=it})
    check(!c.nodes.containsKey("content://files/tree/Pictures/Inbox/image"))
    check(c.nodes.values.count { it.name=="image.jpg" }==1)
    check(events.any { it.status==InboxStatus.DONE && it.detail.contains("转移完成") })
    val (d,other)=fixture(); val out=mutableListOf<InboxEvent>()
    other.scan({out+=it}); stable(d); d.denyRename=true; other.scan({out+=it})
    check(out.any { it.status==InboxStatus.FAILED && it.detail.contains("重命名归档副本") })
    check(d.nodes.containsKey("content://files/tree/Pictures/Inbox/image"))
    check(d.nodes.values.count { it.name.startsWith(".localfeed-") }==1)
    d.denyRename=false; d.denyDelete=true; other.scan({out+=it})
    check(out.any { it.status==InboxStatus.PARTIAL && it.detail.contains("已归档，但来源删除失败") })
    d.denyDelete=false; DownloadInbox(d).scan({out+=it})
    check(d.nodes.values.count { it.name=="image.jpg" }==1)
    check(!d.nodes.containsKey("content://files/tree/Pictures/Inbox/image"))
    val (e,last)=fixture(); val failures=mutableListOf<InboxEvent>()
    last.scan({failures+=it}); stable(e); e.contentResolver.failWrite=true; last.scan({failures+=it})
    check(failures.any { it.status==InboxStatus.FAILED && it.detail.contains("IOException: disk full") && it.detail.contains("复制文件内容") })
    check(e.nodes.containsKey("content://files/tree/Pictures/Inbox/image"))
    // A failed legacy transfer must survive a changed target without deleting its source.
    val (f,migrate)=fixture(); val migrated=mutableListOf<InboxEvent>()
    migrate.scan({migrated+=it}); stable(f); f.contentResolver.failWrite=true; migrate.scan({migrated+=it})
    val saved=f.prefs.values.keys.first { it.startsWith("transfer:") }
    val legacy=JSONObject(f.prefs.getString(saved,null)!!)
    legacy.remove("target")
    f.prefs.edit().remove(saved).putString("transfer:content://files/tree/Pictures/Inbox/image",legacy.toString()).commit()
    val newTarget="content://files/tree/NewArchive"; f.nodes[newTarget]=Node("NewArchive",true)
    migrate.remove(migrate.rules().single().id)
    migrate.add(Uri.parse("content://files/tree/Pictures/Inbox"),Uri.parse(newTarget))
    f.contentResolver.failWrite=false; migrate.scan({migrated+=it})
    check(!f.nodes.containsKey("content://files/tree/Pictures/Inbox/image"))
    check(f.nodes.values.count { it.name=="image.jpg" }==1)
    check(f.nodes.values.none { it.name.startsWith(".localfeed-") })
    check(migrated.any { it.detail.contains("迁移旧归档记录") })
    // Same document, different tree URI: resume the existing copy, not a duplicate.
    val (g,aliases)=fixture(); aliases.scan({}); stable(g); g.denyRename=true; aliases.scan({})
    val aliasKey=g.prefs.values.keys.first { it.startsWith("transfer:") }
    val state=JSONObject(g.prefs.getString(aliasKey,null)!!)
    val doc=android.provider.DocumentsContract.getDocumentId(Uri.parse(state.getString("uri")))
    state.put("uri","content://files/tree/OtherGrant/document/"+doc)
    g.prefs.edit().putString(aliasKey,state.toString()).commit()
    g.denyRename=false; aliases.scan({})
    check(g.nodes.values.count { it.name=="image.jpg" }==1)
    check(!g.nodes.containsKey("content://files/tree/Pictures/Inbox/image"))
    // A genuinely missing unfinished temp can be recreated after successful directory queries.
    val (h,missing)=fixture(); missing.scan({}); stable(h); h.contentResolver.failWrite=true; missing.scan({})
    val temp=h.nodes.keys.first { h.nodes[it]!!.name.startsWith(".localfeed-") }
    h.nodes.remove(temp); h.nodes.values.forEach { it.children.remove(temp) }
    h.contentResolver.failWrite=false; missing.scan({})
    check(h.nodes.values.count { it.name=="image.jpg" }==1)
    // Provider failure is not proof of absence, and must not create another temporary file.
    val (j,denied)=fixture(); denied.scan({}); stable(j); j.contentResolver.failWrite=true; denied.scan({})
    j.contentResolver.failWrite=false; j.contentResolver.failList=true
    val deniedEvents=mutableListOf<InboxEvent>(); denied.scan({deniedEvents+=it})
    check(j.nodes.containsKey("content://files/tree/Pictures/Inbox/image"))
    check(j.nodes.values.count { it.name.startsWith(".localfeed-") }==1)
    check(deniedEvents.any { it.status==InboxStatus.FAILED && it.detail.contains("directory denied") })
    // A finished archive in an old target is retained; changing targets never duplicates it.
    val (k,finished)=fixture(); finished.scan({}); stable(k); k.denyDelete=true; finished.scan({})
    k.nodes[newTarget]=Node("NewArchive",true); finished.remove(finished.rules().single().id)
    finished.add(Uri.parse("content://files/tree/Pictures/Inbox"),Uri.parse(newTarget))
    k.denyDelete=false; val retained=mutableListOf<InboxEvent>(); finished.scan({retained+=it})
    check(k.nodes.containsKey("content://files/tree/Pictures/Inbox/image"))
    check(k.nodes.values.count { it.name=="image.jpg" }==2) // source + one verified archive
    check(k.nodes[newTarget]!!.children.none { k.nodes[it]?.name=="image.jpg" })
    check(retained.any { it.status==InboxStatus.FAILED && it.detail.contains("旧目录已有正式归档文件") })
    // Restart during migration cleanup: keep both verified replacement and source, then resume.
    val (m,restart)=fixture(); restart.scan({}); stable(m); m.contentResolver.failWrite=true; restart.scan({})
    m.nodes[newTarget]=Node("NewArchive",true); restart.remove(restart.rules().single().id)
    restart.add(Uri.parse("content://files/tree/Pictures/Inbox"),Uri.parse(newTarget))
    m.contentResolver.failWrite=false; m.denyDelete=true; val paused=mutableListOf<InboxEvent>()
    restart.scan({paused+=it})
    check(paused.any { it.status==InboxStatus.PARTIAL && it.detail.contains("旧临时副本清理失败") })
    check(m.nodes.containsKey("content://files/tree/Pictures/Inbox/image"))
    m.denyDelete=false; DownloadInbox(m).scan({})
    check(!m.nodes.containsKey("content://files/tree/Pictures/Inbox/image"))
    check(m.nodes.values.count { it.name=="image.jpg" }==1)
    check(m.nodes.values.none { it.name.startsWith(".localfeed-") })
    // A changed source must never overwrite a journaled copy or be deleted.
    val (n,changed)=fixture(); changed.scan({}); stable(n); n.contentResolver.failWrite=true; changed.scan({})
    n.contentResolver.failWrite=false; n.nodes["content://files/tree/Pictures/Inbox/image"]!!.bytes="other bytes".toByteArray()
    val changedEvents=mutableListOf<InboxEvent>(); changed.scan({changedEvents+=it})
    check(changedEvents.any { it.status==InboxStatus.FAILED && it.detail.contains("来源内容已变化") })
    check(n.nodes.containsKey("content://files/tree/Pictures/Inbox/image"))
    println("Download inbox state-machine regression (stub provider): PASS")
}
