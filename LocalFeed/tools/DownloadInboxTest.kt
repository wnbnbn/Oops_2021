import android.content.*
import android.net.Uri
import com.localfeed.app.data.*
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
    println("Download inbox state-machine regression (stub provider): PASS")
}
