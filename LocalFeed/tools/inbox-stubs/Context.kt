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
    var failWrite=false
    var failList=false
    fun query(uri:Uri,projection:Array<String>,selection:String?,args:Array<String>?,sort:String?):Cursor? {
        if(failList) throw SecurityException("directory denied")
        val node=c.nodes[uri.toString().removeSuffix("/children")] ?: throw java.io.FileNotFoundException("directory missing")
        if(!node.readable) throw SecurityException("directory denied")
        return Cursor(node.children.map { android.provider.DocumentsContract.getDocumentId(Uri.parse(it)) })
    }
    fun openInputStream(uri:Uri):InputStream? {
        val n=c.nodes[uri.toString()] ?: return null
        if(!n.readable) throw SecurityException("read denied")
        return ByteArrayInputStream(n.bytes)
    }
    fun openOutputStream(uri:Uri,mode:String):OutputStream? {
        if(failWrite) throw IOException("disk full")
        val n=c.nodes[uri.toString()] ?: return null
        return object:ByteArrayOutputStream() { override fun close() { super.close(); n.bytes=toByteArray() } }
    }
}
class Cursor(val ids:List<String>):java.io.Closeable {
    var index=-1
    fun moveToNext():Boolean { index++; return index<ids.size }
    fun getString(column:Int)=ids[index]
    override fun close() {}
}
class Context {
    companion object { const val MODE_PRIVATE=0 }
    val nodes=mutableMapOf<String,Node>()
    val prefs=SharedPreferences()
    val contentResolver=ContentResolver(this)
    var denyDelete=false; var denyRename=false
    fun getSharedPreferences(name:String,mode:Int)=prefs
}
