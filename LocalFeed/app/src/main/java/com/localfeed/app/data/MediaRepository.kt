package com.localfeed.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.localfeed.app.core.MediaRecord
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MediaRepository(private val context: Context) {
    private val db = MediaIndexDb(context)
    private val indexIo = Executors.newSingleThreadExecutor { r -> Thread(r, "media-index-io") }
    private val metadataIo = Executors.newSingleThreadExecutor { r -> Thread(r, "media-metadata-io") }
    private val utilityIo = Executors.newSingleThreadExecutor { r -> Thread(r, "media-utility-io") }
    private val generation = AtomicInteger(0)
    private val trashManager = TrashManager(context, db)
    private val duplicateScanner = DuplicateScanner(context, db)
    private val similarVideoScanner = SimilarVideoScanner(context, db)

    fun allMedia(): List<MediaRecord> = db.allVisible()
    fun trashedMedia(): List<MediaRecord> = db.allTrashed()
    fun folderUris(): List<String> = db.folderUris()
    fun folderInfos(): List<FolderInfo> = db.folderInfos()
    fun problems(): List<ProblemMedia> = db.problems()
    fun originalRelativePath(id: Long): String = db.originalRelativePath(id)

    fun addFolder(treeUri: Uri): Boolean {
        val doc = DocumentFile.fromTreeUri(context, treeUri)
        val displayName = doc?.name ?: "媒体目录"
        val marker = try {
            val existing = doc?.findFile(".nomedia")
            existing != null || doc?.createFile("application/octet-stream", ".nomedia") != null
        } catch (_: Exception) {
            false
        }
        db.addFolder(treeUri.toString(), displayName, marker)
        return marker
    }

    fun removeFolder(rootUri: String, deleteNoMedia: Boolean): Boolean {
        if (deleteNoMedia) {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(rootUri))
            runCatching { root?.findFile(".nomedia")?.delete() }
        }
        db.removeFolder(rootUri)
        return true
    }

    fun deleteNoMedia(rootUri: String): Boolean {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(rootUri)) ?: return false
        val marker = root.findFile(".nomedia") ?: run {
            db.setFolderNoMedia(rootUri, false)
            return true
        }
        val ok = runCatching { marker.delete() }.getOrDefault(false)
        if (ok) db.setFolderNoMedia(rootUri, false)
        return ok
    }

    fun recreateNoMedia(rootUri: String): Boolean {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(rootUri)) ?: return false
        val ok = runCatching { root.findFile(".nomedia") != null || root.createFile("application/octet-stream", ".nomedia") != null }.getOrDefault(false)
        db.setFolderNoMedia(rootUri, ok)
        return ok
    }

    fun folderConfigJson(): String {
        val arr = JSONArray()
        folderInfos().forEach { folder ->
            arr.put(JSONObject().apply {
                put("rootUri", folder.rootUri)
                put("displayName", folder.displayName)
                put("path", MediaPathUtils.rootPath(folder.rootUri, folder.displayName))
                put("nomedia", folder.noMediaCreated)
            })
        }
        return JSONObject().apply {
            put("format", "LocalFeedFolderConfig")
            put("version", 1)
            put("folders", arr)
        }.toString(2)
    }

    fun parseFolderConfig(text: String): List<Pair<String, String>> {
        val root = JSONObject(text)
        if (root.optString("format") != "LocalFeedFolderConfig") return emptyList()
        val arr = root.optJSONArray("folders") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val uri = obj.optString("rootUri")
                val path = obj.optString("path", obj.optString("displayName", "媒体目录"))
                if (uri.isNotBlank()) add(uri to path)
            }
        }
    }

    /**
     * Large-library scan:
     * 1) enumerate and index cheap fields;
     * 2) call onIndexed immediately so album/feed can be used;
     * 3) enrich duration/dimensions on a separate worker.
     */
    fun scanAll(
        onProgress: (String) -> Unit,
        onIndexed: (List<MediaRecord>, Int) -> Unit,
        onMetadataDone: (List<MediaRecord>, Int) -> Unit
    ) {
        val run = generation.incrementAndGet()
        indexIo.execute {
            val configuredRoots = db.folderUris()
            val grantedRoots = context.contentResolver.persistedUriPermissions
                .asSequence()
                .filter { it.isReadPermission }
                .map { it.uri.toString() }
                .toHashSet()
            // A reinstall during certificate migration revokes persisted SAF grants. Keep restored
            // rows until the user selects each folder again; an unauthorized scan must not prune.
            val roots = configuredRoots.filter { it in grantedRoots }
            val authorizationNeeded = configuredRoots.size - roots.size
            if (authorizationNeeded > 0) {
                onProgress("有 $authorizationNeeded 个媒体目录需要重新授权 · 已保留原有记录")
            }
            val allTasks = ArrayList<TreeScanner.MetadataTask>(1024)
            var totalErrors = 0
            roots.forEachIndexed { rootZero, value ->
                if (run != generation.get()) return@execute
                val scanner = TreeScanner(context, db)
                val result = scanner.scanBasic(Uri.parse(value)) { count ->
                    onProgress("快速索引 ${rootZero + 1}/${roots.size} · 已发现 $count 个媒体")
                }
                allTasks += result.metadataTasks
                totalErrors += result.errors
                onProgress("目录 ${rootZero + 1}/${roots.size} · ${result.discovered} 个媒体 · 索引错误 ${result.errors}")
            }
            if (run != generation.get()) return@execute
            onIndexed(db.allVisible(), allTasks.size)

            metadataIo.execute {
                if (run != generation.get()) return@execute
                val scanner = TreeScanner(context, db)
                val metaErrors = scanner.enrichMetadata(allTasks) { done, total ->
                    if (run == generation.get()) onProgress("媒体库已经可用 · 正在分析尺寸/时长 $done/$total")
                }
                if (run == generation.get()) onMetadataDone(db.allVisible(), totalErrors + metaErrors)
            }
        }
    }

    fun scanDuplicates(onProgress: (String) -> Unit, onDone: (List<DuplicateGroup>) -> Unit) {
        val snapshot = db.allVisible().filter { it.kind == com.localfeed.app.core.MediaKind.VIDEO }
        utilityIo.execute {
            val groups = runCatching { duplicateScanner.scan(snapshot, onProgress) }.getOrElse {
                onProgress("重复扫描失败 · ${it.message ?: it.javaClass.simpleName}")
                emptyList()
            }
            onDone(groups)
        }
    }

    fun scanSimilarVideos(onProgress: (String) -> Unit, onDone: (SimilarVideoScanResult) -> Unit) {
        val snapshot = db.allVisible().filter { it.kind == com.localfeed.app.core.MediaKind.VIDEO }
        utilityIo.execute {
            val result = runCatching { similarVideoScanner.scan(snapshot, onProgress) }.getOrElse {
                onProgress("相似视频扫描失败 · ${it.message ?: it.javaClass.simpleName}")
                SimilarVideoScanResult(emptyList(), emptyList(), 0, snapshot.size)
            }
            onDone(result)
        }
    }

    fun findSimilarVideos(record: com.localfeed.app.core.MediaRecord, onProgress: (String) -> Unit, onDone: (List<SimilarVideoPair>) -> Unit) {
        val snapshot = db.allVisible().filter { it.kind == com.localfeed.app.core.MediaKind.VIDEO }
        utilityIo.execute {
            val result = runCatching { similarVideoScanner.findSimilar(record, snapshot, onProgress) }.getOrElse {
                onProgress("查找相似失败 · ${it.message ?: it.javaClass.simpleName}")
                emptyList()
            }
            onDone(result)
        }
    }

    fun markNotSimilar(firstId: Long, secondId: Long) = utilityIo.execute {
        similarVideoScanner.markNotDuplicate(firstId, secondId)
    }

    fun moveToTrash(record: MediaRecord, callback: (TrashManager.Result) -> Unit) = utilityIo.execute {
        callback(trashManager.moveToTrash(record))
    }

    fun restoreFromTrash(record: MediaRecord, callback: (TrashManager.Result) -> Unit) = utilityIo.execute {
        callback(trashManager.restore(record))
    }

    fun deletePermanently(record: MediaRecord, callback: (TrashManager.Result) -> Unit) = utilityIo.execute {
        callback(trashManager.deletePermanently(record))
    }

    fun setLiked(id: Long, value: Boolean) = indexIo.execute { db.setLiked(id, value) }
    fun setLikedMany(ids: Collection<Long>, value: Boolean) = indexIo.execute { db.setLikedMany(ids, value) }
    fun setFavorited(id: Long, value: Boolean) = indexIo.execute { db.setFavorited(id, value) }
    fun setFavoritedMany(ids: Collection<Long>, value: Boolean) = indexIo.execute { db.setFavoritedMany(ids, value) }
    fun markShown(id: Long) = indexIo.execute { db.markShown(id) }
    fun hide(id: Long) = indexIo.execute { db.hide(id) }
    fun hideMany(ids: Collection<Long>) = indexIo.execute { db.hideMany(ids) }
    fun deleteRecord(id: Long) = indexIo.execute { db.deleteRecord(id) }
    fun recordPlaybackError(record: MediaRecord, message: String) = indexIo.execute {
        db.recordError(record.uri, record.name, "播放", message)
    }
}
