package com.localfeed.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

data class InboxRule(val id: String, val source: String, val target: String, val enabled: Boolean = true)

/** Scan-bound, single-worker inbox. Source deletion only follows a verified, durable copy. */
class DownloadInbox(private val context: Context) {
    private val prefs = context.getSharedPreferences("localfeed_inboxes", Context.MODE_PRIVATE)
    fun rules(): List<InboxRule> = runCatching {
        val a = JSONArray(prefs.getString("rules","[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { InboxRule(it.getString("id"),it.getString("source"),it.getString("target"),it.optBoolean("enabled",true)) }
    }.getOrDefault(emptyList())
    private fun store(rules: List<InboxRule>) {
        val a=JSONArray(); rules.forEach { a.put(JSONObject().put("id",it.id).put("source",it.source).put("target",it.target).put("enabled",it.enabled)) }
        check(prefs.edit().putString("rules",a.toString()).commit()) { "收件箱配置保存失败" }
    }
    fun add(source: Uri, target: Uri) {
        require(source != target) { "来源和归档目录不能相同" }
        val sourceId=android.provider.DocumentsContract.getTreeDocumentId(source)
        val targetId=android.provider.DocumentsContract.getTreeDocumentId(target)
        fun overlaps(a: String,b: String)=a==b || a.startsWith(if(b.endsWith(':')) b else "$b/") || b.startsWith(if(a.endsWith(':')) a else "$a/")
        require(source.authority != target.authority || !overlaps(sourceId,targetId)) { "来源和归档目录不能互相包含" }
        require(rules().none { old ->
            val s=Uri.parse(old.source); val t=Uri.parse(old.target)
            (s.authority==source.authority && overlaps(android.provider.DocumentsContract.getTreeDocumentId(s),sourceId)) ||
            (t.authority==source.authority && overlaps(android.provider.DocumentsContract.getTreeDocumentId(t),sourceId)) ||
            (s.authority==target.authority && overlaps(android.provider.DocumentsContract.getTreeDocumentId(s),targetId))
        }) { "目录已用于其他收件箱，请勿交叉转移" }
        store(rules()+InboxRule(UUID.randomUUID().toString(),source.toString(),target.toString()))
    }
    fun toggle(id: String) = store(rules().map { if(it.id==id) it.copy(enabled=!it.enabled) else it })
    fun remove(id: String) = store(rules().filterNot { it.id==id })
    fun scan(onFile: (String,String,String,Boolean) -> Unit) {
        rules().filter { it.enabled }.forEach { rule ->
            try {
                val source=DocumentFile.fromTreeUri(context,Uri.parse(rule.source)) ?: error("来源目录不可用")
                val target=DocumentFile.fromTreeUri(context,Uri.parse(rule.target)) ?: error("归档目录不可用")
                check(source.canRead() && target.canWrite()) { "目录需要重新授权" }
                // Keep archive private from the system gallery; SAF scanning is unaffected.
                if(target.findFile(".nomedia")==null) target.createFile("application/octet-stream",".nomedia")
                source.listFiles().filter { eligible(it) }.forEach { file ->
                    try { transferWhenStable(file,target,onFile) }
                    catch(e: Exception) { onFile(file.name ?: "文件",file.uri.toString(),e.message ?: "转移失败",false) }
                }
            } catch(e: Exception) { onFile("下载收件箱",rule.source,e.message ?: "目录读取失败",false) }
        }
    }
    private fun eligible(f: DocumentFile): Boolean {
        val name=f.name?.lowercase() ?: return false
        if(!f.isFile || name.startsWith('.') || f.length()<=0) return false
        if(listOf(".part",".tmp",".download",".crdownload").any { name.endsWith(it) }) return false
        return f.type?.let { it.startsWith("image/") || it.startsWith("video/") } == true ||
            name.substringAfterLast('.',"") in setOf("jpg","jpeg","png","webp","gif","bmp","avif","heic","mp4","mkv","mov","webm","m4v","avi","3gp")
    }
    private fun digest(uri: Uri): String {
        val hash=MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer=ByteArray(256*1024)
            while(true) { val n=input.read(buffer); if(n<0) break; hash.update(buffer,0,n) }
        } ?: error("文件无法读取")
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun transferWhenStable(file: DocumentFile,target: DocumentFile,onFile: (String,String,String,Boolean)->Unit) {
        val key=file.uri.toString(); val signature="${file.length()}:${file.lastModified()}"
        val observed="observed:$key"; val seen="seen:$key"; val now=System.currentTimeMillis()
        if(prefs.getString(observed,null)!=signature) {
            check(prefs.edit().putString(observed,signature).putLong(seen,now).commit()); return
        }
        if(now-prefs.getLong(seen,now)<30_000) return
        val journalKey="transfer:$key"
        var journal=runCatching { prefs.getString(journalKey,null)?.let(::JSONObject) }.getOrNull()
        if(journal!=null && journal.optString("signature")!=signature) error("来源文件已变化，保留来源与归档副本，请手动检查")
        val name=file.name ?: error("缺少文件名")
        val sourceHash=digest(file.uri)
        if(journal==null) {
            // Never overwrite a namesake. The UUID temporary filename survives interrupted copies.
            val temp=target.createFile(file.type ?: "application/octet-stream",".localfeed-${UUID.randomUUID()}.part") ?: error("无法创建归档文件")
            journal=JSONObject().put("signature",signature).put("uri",temp.uri.toString()).put("hash",sourceHash).put("ready",false)
            if(!prefs.edit().putString(journalKey,journal.toString()).commit()) { temp.delete(); error("转移记录保存失败") }
        }
        val state=journal
        check(state.getString("hash")==sourceHash) { "来源内容已变化，停止转移" }
        var copy=DocumentFile.fromSingleUri(context,Uri.parse(state.getString("uri"))) ?: error("归档副本不可用")
        if(!copy.exists() && state.has("finalName")) {
            copy=target.findFile(state.getString("finalName")) ?: error("归档副本已丢失，保留来源文件")
            check(copy.length()==file.length() && digest(copy.uri)==sourceHash) { "恢复副本校验失败" }
            state.put("uri",copy.uri.toString()).put("ready",true)
            check(prefs.edit().putString(journalKey,state.toString()).commit())
        }
        check(copy.exists()) { "归档副本已丢失，保留来源文件" }
        if(!state.optBoolean("ready")) {
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                context.contentResolver.openOutputStream(copy.uri,"wt")?.use { output -> input.copyTo(output,256*1024) } ?: error("归档文件不可写")
            } ?: error("来源文件不可读")
            check(copy.length()==file.length() && digest(copy.uri)==sourceHash) { "归档校验失败，保留来源文件" }
            val finalName=if(target.findFile(name)==null) name else name.substringBeforeLast('.',name)+"-${UUID.randomUUID().toString().take(8)}"+name.substringAfterLast('.',"").let { if(it.isBlank()) "" else ".$it" }
            // Persist intent before rename; providers may change the URI during rename.
            state.put("finalName",finalName)
            check(prefs.edit().putString(journalKey,state.toString()).commit())
            check(copy.renameTo(finalName)) { "归档重命名失败，保留来源文件" }
            state.put("uri",copy.uri.toString()).put("ready",true)
            check(prefs.edit().putString(journalKey,state.toString()).commit())
        }
        check("${file.length()}:${file.lastModified()}"==signature && digest(file.uri)==sourceHash) { "来源文件仍在写入，暂不删除" }
        check(copy.length()==file.length() && digest(copy.uri)==sourceHash) { "归档复核失败，暂不删除" }
        check(file.delete()) { "归档已完成，但来源删除失败，下次检查会重试" }
        check(prefs.edit().remove(journalKey).remove(observed).remove(seen).commit())
        onFile(name,copy.uri.toString(),"已校验并转移到 ${target.name ?: "归档目录"}",true)
    }
}
