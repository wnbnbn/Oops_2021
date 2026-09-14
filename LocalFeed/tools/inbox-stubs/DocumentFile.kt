package androidx.documentfile.provider
import android.content.*
import android.net.Uri
class DocumentFile(val context:Context,val uri:Uri,val single:Boolean=false) {
    val node get()=context.nodes[uri.toString()]
    val name get()=node?.name
    val isFile get()=node?.directory==false
    val type get()=if(name?.endsWith(".jpg")==true) "image/jpeg" else "application/octet-stream"
    fun length()=node?.bytes?.size?.toLong() ?: 0L
    fun lastModified()=node?.modified ?: 0L
    fun canRead()=node?.readable==true
    fun canWrite()=node?.writable==true
    fun exists()=node!=null
    fun listFiles()=node!!.children.map { DocumentFile(context,Uri.parse(it)) }.toTypedArray()
    fun findFile(name:String)=listFiles().firstOrNull { it.name==name }
    fun createFile(mime:String,name:String):DocumentFile? {
        if(!canWrite()) return null
        val child=uri.toString().substringBefore("/document/")+"/document/"+java.util.UUID.randomUUID()
        context.nodes[child]=Node(name); node!!.children+=child
        return DocumentFile(context,Uri.parse(child))
    }
    fun renameTo(name:String):Boolean {
        if(single) throw UnsupportedOperationException()
        if(context.denyRename) return false
        node!!.name=name; return true
    }
    fun delete():Boolean {
        if(context.denyDelete) return false
        context.nodes.remove(uri.toString())
        context.nodes.values.forEach { it.children.remove(uri.toString()) }
        return true
    }
    companion object {
        fun fromTreeUri(c:Context,u:Uri):DocumentFile? {
            val root=Uri.parse(u.toString().substringBefore("/document/"))
            return if(c.nodes.containsKey(root.toString())) DocumentFile(c,root) else null
        }
        fun fromSingleUri(c:Context,u:Uri)=DocumentFile(c,u,true)
    }
}
