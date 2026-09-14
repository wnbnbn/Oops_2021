package com.localfeed.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

enum class InboxStatus { WAITING, RUNNING, DONE, FAILED, PARTIAL }
data class InboxEvent(val key: String, val name: String, val uri: String, val status: InboxStatus, val detail: String)

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

    private class Trace(val rule: InboxRule, val name: String, val uri: String,
        val sourceLabel: String, val targetLabel: String, val emit: (InboxEvent) -> Unit) {
        var stage="检查目录授权"
        var archiveReady=false
        var sourceDeleted=false
        var archiveUri=""
        val steps=mutableListOf<String>()
        fun step(value: String) { stage=value; steps+=value; report(InboxStatus.RUNNING,"正在"+value) }
        fun report(status: InboxStatus, reason: String) {
            val detail="阶段："+stage+"\n文件："+name+"\n来源："+sourceLabel+"\n归档："+targetLabel+
                "\n结果："+reason+"\n来源状态："+(if(sourceDeleted) "已删除" else "未删除（保留来源）")+
                "\n副本状态："+(if(archiveReady) "已校验归档" else "尚未完成归档校验")+
                (if(archiveUri.isBlank()) "" else "\n副本位置："+archiveUri)+"\n\n本次步骤：\n"+steps.joinToString("\n")
            emit(InboxEvent(rule.id+"|"+uri,name,uri,status,detail))
        }
        fun failure(e: Exception) {
            val causes=generateSequence<Throwable>(e) { it.cause }.take(6).joinToString("\n") {
                it.javaClass.simpleName+": "+(it.message?.takeIf { m -> m.isNotBlank() } ?: "该异常未提供文字说明")
            }
            report(if(archiveReady && !sourceDeleted) InboxStatus.PARTIAL else InboxStatus.FAILED,causes)
        }
    }
    fun scan(onEvent: (InboxEvent)->Unit, onProgress: (String)->Unit = {}) {
        rules().filter { it.enabled }.forEach { rule ->
            var directory=Trace(rule,"下载收件箱",rule.source,rule.source,rule.target,onEvent)
            try {
                directory.step("检查目录授权")
                val source=DocumentFile.fromTreeUri(context,Uri.parse(rule.source)) ?: error("来源目录不可用")
                val target=DocumentFile.fromTreeUri(context,Uri.parse(rule.target)) ?: error("归档目录不可用")
                directory=Trace(rule,"下载收件箱",rule.source,source.name ?: rule.source,target.name ?: rule.target,onEvent)
                check(source.canRead()) { "来源目录不可读，请重新授权来源目录" }
                check(target.canWrite()) { "归档目录不可写，请重新授权归档目录" }
                // Failure to recreate a marker must not prevent valid transfers.
                runCatching { if(target.findFile(".nomedia")==null) target.createFile("application/octet-stream",".nomedia") }
                directory.step("读取来源目录")
                var count=0
                source.listFiles().forEach { file ->
                    val trace=Trace(rule,file.name ?: "文件",file.uri.toString(),source.name ?: rule.source,target.name ?: rule.target,onEvent)
                    try {
                        trace.stage="读取文件属性"
                        if(eligible(file)) { count++; onProgress("收件箱检查 · "+trace.name); transferWhenStable(file,target,trace) }
                    } catch(e: Exception) { trace.failure(e) }
                }
                directory.stage="目录检查完成"
                directory.report(InboxStatus.DONE,"已检查 "+count+" 个候选文件；逐项状态见文件任务")
            } catch(e: Exception) { directory.failure(e) }
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

    private fun transferWhenStable(file: DocumentFile,target: DocumentFile,trace: Trace) {
        trace.step("检测文件是否稳定")
        val key=file.uri.toString()
        val signature=file.length().toString()+":"+file.lastModified()
        val observed="observed:"+key; val seen="seen:"+key; val now=System.currentTimeMillis()
        if(prefs.getString(observed,null)!=signature) {
            check(prefs.edit().putString(observed,signature).putLong(seen,now).commit()) { "稳定检测记录保存失败" }
            trace.report(InboxStatus.WAITING,"首次发现或文件仍在变化，至少 30 秒后的下一次扫描再次检查"); return
        }
        if(now-prefs.getLong(seen,now)<30_000) { trace.report(InboxStatus.WAITING,"等待文件稳定，下一次扫描再次检查"); return }
        trace.step("恢复转移记录")
        val journalKey="transfer:"+key
        var journal=prefs.getString(journalKey,null)?.let { JSONObject(it) }
        if(journal!=null) {
            trace.archiveUri=journal.optString("uri")
            check(journal.optString("signature")==signature) { "来源文件已变化，保留来源与已有副本，请手动检查" }
        }
        val name=file.name ?: error("缺少文件名")
        trace.step("读取来源并计算校验值")
        val sourceHash=digest(file.uri)
        if(journal==null) {
            trace.step("创建临时副本")
            val temp=target.createFile("application/octet-stream",".localfeed-"+UUID.randomUUID()+".part") ?: error("无法创建归档文件，检查空间与目录授权")
            journal=JSONObject().put("signature",signature).put("uri",temp.uri.toString()).put("hash",sourceHash).put("ready",false)
            trace.archiveUri=temp.uri.toString()
            if(!prefs.edit().putString(journalKey,journal.toString()).commit()) { temp.delete(); error("转移记录保存失败") }
        }
        val state=requireNotNull(journal)
        check(state.getString("hash")==sourceHash) { "来源内容已变化，停止转移并保留来源" }
        trace.step("恢复归档副本")
        // Obtain a TreeDocumentFile from the chosen parent; fromSingleUri cannot rename.
        var candidate=target.listFiles().firstOrNull { it.uri.toString()==state.getString("uri") }
        if(candidate==null && state.has("finalName")) {
            val renamed=target.findFile(state.getString("finalName"))
            if(renamed!=null) {
                check(renamed.length()==file.length() && digest(renamed.uri)==sourceHash) { "恢复副本校验失败" }
                candidate=renamed
                state.put("uri",renamed.uri.toString()).put("ready",true)
                check(prefs.edit().putString(journalKey,state.toString()).commit()) { "恢复记录保存失败" }
            }
        }
        val archive=candidate ?: error("归档副本不可用；保留来源，不新建重复副本")
        trace.archiveUri=archive.uri.toString()
        if(!state.optBoolean("ready")) {
            // Reuse and verify an existing complete temporary copy rather than copying again.
            trace.step("检查已有临时副本")
            val complete=archive.length()==file.length() && digest(archive.uri)==sourceHash
            if(!complete) {
                trace.step("复制文件内容")
                context.contentResolver.openInputStream(file.uri)?.use { input ->
                    context.contentResolver.openOutputStream(archive.uri,"wt")?.use { output -> input.copyTo(output,256*1024) } ?: error("归档文件不可写")
                } ?: error("来源文件不可读")
            }
            trace.step("校验临时副本")
            check(archive.length()==file.length() && digest(archive.uri)==sourceHash) { "归档长度或校验值不符，保留来源" }
            var finalName=state.optString("finalName")
            if(finalName.isBlank()) {
                finalName=if(target.findFile(name)==null) name else {
                    val dot=name.lastIndexOf('.')
                    if(dot>0) name.substring(0,dot)+"-"+UUID.randomUUID().toString().take(8)+name.substring(dot)
                    else name+"-"+UUID.randomUUID().toString().take(8)
                }
                state.put("finalName",finalName)
                check(prefs.edit().putString(journalKey,state.toString()).commit()) { "重命名意图保存失败" }
            }
            trace.step("重命名归档副本")
            val namesake=target.findFile(finalName)
            check(namesake==null || namesake.uri==archive.uri) { "归档文件名已被其他文件占用；保留来源和副本，不覆盖" }
            if(archive.name!=finalName) check(archive.renameTo(finalName)) { "文件提供器拒绝重命名；保留来源与已复制副本" }
            state.put("uri",archive.uri.toString()).put("ready",true)
            check(prefs.edit().putString(journalKey,state.toString()).commit()) { "归档状态保存失败" }
        }
        trace.archiveUri=archive.uri.toString()
        trace.step("复核来源与归档副本")
        check(file.length().toString()+":"+file.lastModified()==signature && digest(file.uri)==sourceHash) { "来源文件仍在写入，暂不删除" }
        check(archive.length()==file.length() && digest(archive.uri)==sourceHash) { "归档复核失败，暂不删除" }
        trace.archiveReady=true
        trace.step("删除来源文件")
        check(file.delete()) { "已归档，但来源删除失败；下次扫描只重试删除，不重复复制" }
        trace.sourceDeleted=true
        trace.step("保存完成记录")
        check(prefs.edit().remove(journalKey).remove(observed).remove(seen).commit()) { "文件已转移，但完成记录保存失败" }
        trace.stage="转移完成"
        trace.report(InboxStatus.DONE,"已校验并完成转移")
    }
}
