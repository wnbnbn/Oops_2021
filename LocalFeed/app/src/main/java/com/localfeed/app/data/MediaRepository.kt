package com.localfeed.app.data

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.localfeed.app.core.MediaRecord
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class ScanSummary(
    val discovered: Int,
    val newFiles: Int,
    val updatedFiles: Int,
    val unchangedFiles: Int,
    val metadataQueued: Int,
    val indexErrors: Int,
    val metadataErrors: Int = 0,
    val newFileErrors: Int = 0,
    val newNames: List<String> = emptyList(),
    val authorizationNeeded: Int = 0,
    val pendingUris: Set<String> = emptySet()
)

data class DiagnosticSummary(val checked: Int, val issues: Int)
private data class DiagnosticFinding(val stage: String, val message: String)

class MediaRepository(private val context: Context) {
    private val db = MediaIndexDb(context)
    private val indexIo = Executors.newSingleThreadExecutor { r -> Thread(r, "media-index-io") }
    private val metadataIo = Executors.newSingleThreadExecutor { r -> Thread(r, "media-metadata-io") }
    private val duplicateIo = Executors.newSingleThreadExecutor { r -> Thread(r, "media-duplicate-io") }
    private val fileIo = Executors.newSingleThreadExecutor { r -> Thread(r, "media-file-io") }
    private val diagnosticIo = Executors.newSingleThreadExecutor { r -> Thread(r, "media-diagnostic-io") }
    private val generation = AtomicInteger(0)
    private val storageLock = ReentrantLock(true)
    private val trashManager = TrashManager(context, db)
    private val duplicateScanner = DuplicateScanner(context, db)

    fun allMedia(): List<MediaRecord> = db.allVisible()
    fun trashedMedia(): List<MediaRecord> = db.allTrashed()
    fun folderUris(): List<String> = db.folderUris()
    fun folderInfos(): List<FolderInfo> = db.folderInfos()
    fun problems(): List<ProblemMedia> = db.problems()
    fun mediaByUri(uri: String): com.localfeed.app.core.MediaRecord? = db.recordByUri(uri)
    fun mediaById(id: Long): com.localfeed.app.core.MediaRecord? = db.recordById(id)
    fun clearProblem(uri: String) = indexIo.execute { db.clearError(uri) }
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
        onIndexed: (List<MediaRecord>, ScanSummary) -> Unit,
        onMetadataDone: (List<MediaRecord>, ScanSummary) -> Unit,
        onFailed: (String) -> Unit = {}
    ) {
        val run = generation.incrementAndGet()
        indexIo.execute {
          try {
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
            var totalDiscovered = 0
            var totalNew = 0
            var totalUpdated = 0
            var totalUnchanged = 0
            val newNames = ArrayList<String>()
            roots.forEachIndexed { rootZero, value ->
                if (run != generation.get()) return@execute
                val scanner = TreeScanner(context, db)
                val result = storageLock.withLock {
                    scanner.scanBasic(Uri.parse(value)) { count ->
                        onProgress("快速索引 ${rootZero + 1}/${roots.size} · 已发现 $count 个媒体")
                    }
                }
                allTasks += result.metadataTasks
                totalErrors += result.errors
                totalDiscovered += result.discovered
                totalNew += result.newFiles
                totalUpdated += result.updatedFiles
                totalUnchanged += result.unchangedFiles
                if (newNames.size < 100) newNames += result.newNames.take(100 - newNames.size)
                onProgress("目录 ${rootZero + 1}/${roots.size} · ${result.discovered} 项 · 新增 ${result.newFiles} · 更新 ${result.updatedFiles} · 失败 ${result.errors}")
            }
            if (run != generation.get()) return@execute
            val indexedSummary = ScanSummary(
                discovered = totalDiscovered,
                newFiles = totalNew,
                updatedFiles = totalUpdated,
                unchangedFiles = totalUnchanged,
                metadataQueued = allTasks.size,
                indexErrors = totalErrors,
                newNames = newNames,
                authorizationNeeded = authorizationNeeded
            )
            onIndexed(db.allVisible(), indexedSummary)

            metadataIo.execute {
                try {
                    if (run != generation.get()) return@execute
                    val scanner = TreeScanner(context, db)
                    val meta = storageLock.withLock {
                        scanner.enrichMetadata(allTasks) { done, total ->
                            if (run == generation.get()) onProgress("媒体库已经可用 · 正在分析尺寸/时长 $done/$total")
                        }
                    }
                    if (run == generation.get()) onMetadataDone(
                        db.allVisible(),
                        indexedSummary.copy(
                            metadataErrors = meta.errors,
                            newFileErrors = meta.newFileErrors,
                            pendingUris = meta.failedNewUris
                        )
                    )
                } catch (error: Throwable) {
                    if (run == generation.get()) onFailed("媒体信息分析失败：${error.message ?: error.javaClass.simpleName}")
                }
            }
          } catch (error: Throwable) {
              if (run == generation.get()) onFailed("媒体索引失败：${error.message ?: error.javaClass.simpleName}")
          }
        }
    }

    fun scanDuplicates(onProgress: (String) -> Unit, onDone: (List<DuplicateGroup>) -> Unit) {
        val snapshot = db.allVisible().filter { it.kind == com.localfeed.app.core.MediaKind.VIDEO }
        duplicateIo.execute {
            val groups = runCatching { storageLock.withLock { duplicateScanner.scan(snapshot, onProgress) } }.getOrElse {
                onProgress("重复扫描失败 · ${it.message ?: it.javaClass.simpleName}")
                emptyList()
            }
            onDone(groups)
        }
    }

    fun diagnoseMedia(onProgress: (Int, Int, String) -> Unit, onDone: (DiagnosticSummary, List<ProblemMedia>) -> Unit) {
        val snapshot = db.allVisible()
        diagnosticIo.execute {
            var issues = 0
            storageLock.withLock { snapshot.forEachIndexed { index, record ->
                if (record.kind == com.localfeed.app.core.MediaKind.IMAGE) {
                    // Animated and modern still-image formats are decoded by ImageDecoder in the
                    // reader. BitmapFactory metadata is not a reliable corruption test for them.
                    db.clearError(record.uri)
                    onProgress(index + 1, snapshot.size, record.name)
                    return@forEachIndexed
                }
                val problem = diagnoseOne(record)
                if (problem == null) db.clearError(record.uri)
                else {
                    issues++
                    db.recordError(record.uri, record.name, problem.stage, problem.message)
                }
                onProgress(index + 1, snapshot.size, record.name)
            } }
            onDone(DiagnosticSummary(snapshot.size, issues), db.problems())
        }
    }

    private fun diagnoseOne(record: MediaRecord): DiagnosticFinding? = runCatching {
        if (record.size == 0L) return@runCatching DiagnosticFinding("已确认", "文件大小为 0，可能尚未下载完成或文件已损坏")
        context.contentResolver.openFileDescriptor(Uri.parse(record.uri), "r")?.use { descriptor ->
            if (descriptor.statSize == 0L) return@runCatching DiagnosticFinding("已确认", "文件内容为空")
        } ?: return@runCatching DiagnosticFinding("已确认", "无法打开文件，目录授权可能失效")
        if (record.kind == com.localfeed.app.core.MediaKind.IMAGE) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(Uri.parse(record.uri))?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return@runCatching DiagnosticFinding("已确认", "无法读取图片数据")
            if (opts.outWidth <= 0 || opts.outHeight <= 0) DiagnosticFinding("已确认", "无法解码图片，文件可能损坏或格式不受支持") else null
        } else {
            val retriever = MediaMetadataRetriever()
            val metaProblem = try {
                retriever.setDataSource(context, Uri.parse(record.uri))
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                when {
                    duration <= 0L -> "系统无法读取时长"
                    width <= 0 || height <= 0 -> "系统无法读取视频尺寸"
                    else -> null
                }
            } finally { retriever.release() }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, Uri.parse(record.uri), null)
                val tracks = (0 until extractor.trackCount).mapNotNull { track ->
                    extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)
                }
                val video = tracks.firstOrNull { it.startsWith("video/") }
                when {
                    video == null -> DiagnosticFinding("已确认", "容器中没有可识别的视频轨道；轨道：${tracks.joinToString().ifBlank { "无" }}")
                    metaProblem != null -> DiagnosticFinding("待确认", "$metaProblem；但已检测到视频轨道 $video，可尝试本应用或其他播放器")
                    else -> null
                }
            } finally { extractor.release() }
        }
    }.getOrElse { error ->
        when (error) {
            is SecurityException -> DiagnosticFinding("已确认", "没有读取权限：${error.message ?: "请重新授权目录"}")
            else -> DiagnosticFinding("待确认", "${error.javaClass.simpleName}：${error.message ?: "读取或解析失败"}")
        }
    }

    fun moveToTrash(record: MediaRecord, callback: (TrashManager.Result) -> Unit) = fileIo.execute {
        callback(storageLock.withLock { trashManager.moveToTrash(record) })
    }

    fun restoreFromTrash(record: MediaRecord, callback: (TrashManager.Result) -> Unit) = fileIo.execute {
        callback(storageLock.withLock { trashManager.restore(record) })
    }

    fun deletePermanently(record: MediaRecord, callback: (TrashManager.Result) -> Unit) = fileIo.execute {
        callback(storageLock.withLock { trashManager.deletePermanently(record) })
    }

    fun setLiked(id: Long, value: Boolean) = indexIo.execute { db.setLiked(id, value) }
    fun setLikeCount(id: Long, value: Int) = indexIo.execute { db.setLikeCount(id, value) }
    fun setSpecialMark(id: Long, value: Boolean) = indexIo.execute { db.setSpecialMark(id, value) }
    fun setLikedMany(ids: Collection<Long>, value: Boolean) = indexIo.execute { db.setLikedMany(ids, value) }
    fun setFavorited(id: Long, value: Boolean) = indexIo.execute { db.setFavorited(id, value) }
    fun setFavoritedMany(ids: Collection<Long>, value: Boolean) = indexIo.execute { db.setFavoritedMany(ids, value) }
    fun setPlaybackPosition(id: Long, value: Long) = indexIo.execute { db.setPlaybackPosition(id, value) }
    fun setFitMode(id: Long, value: Int) = indexIo.execute { db.setFitMode(id, value) }
    fun mergeDuplicateState(keepId: Long, removedIds: Collection<Long>, callback: (() -> Unit)? = null) = indexIo.execute {
        db.mergeDuplicateState(keepId, removedIds)
        callback?.invoke()
    }
    fun markShown(id: Long) = indexIo.execute { db.markShown(id) }
    fun hide(id: Long) = indexIo.execute { db.hide(id) }
    fun hideMany(ids: Collection<Long>) = indexIo.execute { db.hideMany(ids) }
    fun deleteRecord(id: Long) = indexIo.execute { db.deleteRecord(id) }
    fun recordPlaybackError(record: MediaRecord, message: String) = indexIo.execute {
        db.recordError(record.uri, record.name, "播放", message)
    }
}
