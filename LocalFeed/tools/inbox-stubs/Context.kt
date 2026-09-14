package android.content
import android.net.Uri
import java.io.*
class SharedPreferences {
    val values=mutableMapOf<String,Any>()
    val all get()=values.toMap()
    fun getString(k:String,d:String?):String?=values[k] as? String ?: d
    fun getLong(k:String,d:Long):Long=values[k] as? Long ?: d
    fun edit()=Editor(this)
    class Editor(val p:SharedPreferences) {
        val updates=mutableMapOf<String,Any>(); val removals=mutableListOf<String>()
        fun putString(k:String,v:String)=apply { updates[k]=v }
        fun putLong(k:String,v:Long)=apply { updates[k]=v }
        fun remove(k:String)=apply { removals+=k }
        fun commit():Boolean { removals.forEach { p.values.remove(it) }; p.values.putAll(updates); return true }
    }
}
class Node(var name:String,val directory:Boolean=false,var bytes:ByteArray=byteArrayOf(),var modified:Long=1) {
    val children=mutableListOf<String>(); var readable=true; var writable=true
}
class ContentResolver(val c:Context) {
    val inputReads=mutableMapOf<String,Int>()
    var openCursors=0
    var failWrite=false
    var failList=false
    fun query(uri:Uri,projection:Array<String>,selection:String?,args:Array<String>?,sort:String?):Cursor? {
        if(failList) throw SecurityException("directory denied")
        val node=c.nodes[uri.toString().removeSuffix("/children")] ?: throw java.io.FileNotFoundException("directory missing")
        if(!node.readable) throw SecurityException("directory denied")
        val entries=if(uri.toString().endsWith("/children")) node.children.map { it to c.nodes.getValue(it) }
            else listOf(uri.toString() to node)
        val rows=entries.map { (key,n) -> projection.map { column ->
            when(column) {
                "document_id" -> android.provider.DocumentsContract.getDocumentId(Uri.parse(key))
                "_display_name" -> n.name
                "mime_type" -> if(n.directory) "vnd.android.document/directory" else if(n.name.endsWith(".jpg")) "image/jpeg" else "application/octet-stream"
                "_size" -> n.bytes.size.toLong()
                "last_modified" -> n.modified
                else -> null
            }
        } }
        openCursors++
        return Cursor(rows,projection) { openCursors-- }
    }
    fun openInputStream(uri:Uri):InputStream? {
        val n=c.nodes[uri.toString()] ?: return null
        if(!n.readable) throw SecurityException("read denied")
        inputReads[uri.toString()]=(inputReads[uri.toString()] ?: 0)+1
        return ByteArrayInputStream(n.bytes)
    }
    fun openOutputStream(uri:Uri,mode:String):OutputStream? {
        if(failWrite) throw IOException("disk full")
        val n=c.nodes[uri.toString()] ?: return null
        return object:ByteArrayOutputStream() { override fun close() { super.close(); n.bytes=toByteArray() } }
    }
}
class Cursor(val rows:List<List<Any?>>,val columns:Array<String>,val onClose:()->Unit):java.io.Closeable {
    var index=-1
    fun moveToNext():Boolean { index++; return index<rows.size }
    fun moveToFirst():Boolean { index=0; return rows.isNotEmpty() }
    fun getString(column:Int)=rows[index][column]?.toString() ?: ""
    fun getLong(column:Int)=(rows[index][column] as Number).toLong()
    fun isNull(column:Int)=rows[index][column]==null
    fun getColumnIndex(name:String)=columns.indexOf(name)
    fun getColumnIndexOrThrow(name:String)=getColumnIndex(name).also { check(it>=0) }
    override fun close() { onClose() }
}
class Context {
    companion object { const val MODE_PRIVATE=0 }
    val nodes=mutableMapOf<String,Node>()
    val prefs=SharedPreferences()
    val contentResolver=ContentResolver(this)
    var denyDelete=false; var denyRename=false
    fun getSharedPreferences(name:String,mode:Int)=prefs
}
