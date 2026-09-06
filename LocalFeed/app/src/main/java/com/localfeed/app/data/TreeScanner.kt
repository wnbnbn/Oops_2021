package com.localfeed.app.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.localfeed.app.core.MediaKind
import com.localfeed.app.core.MediaRecord
import java.util.ArrayDeque

/**
 * Two-stage tree scanner for large libraries.
 * Stage 1 enumerates cheap fields, including a stable relative path for folder browsing.
 * Stage 2 parses dimensions/duration later, so large libraries become usable quickly.
 */
class TreeScanner(
    private val context: Context,
    private val db: MediaIndexDb
) {
    data class MetadataTask(val uri: Uri, val kind: MediaKind, val name: String, val isNew: Boolean)
    data class BasicResult(
        val discovered: Int,
        val indexed: Int,
        val newFiles: Int,
        val updatedFiles: Int,
        val unchangedFiles: Int,
        val newNames: List<String>,
        val metadataTasks: List<MetadataTask>,
        val errors: Int
    )

    data class MetadataResult(val errors: Int, val newFileErrors: Int)

    private data class PendingDir(val documentId: String, val relativeDir: String)

    fun scanBasic(treeUri: Uri, onProgress: (Int) -> Unit = {}): BasicResult {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val queue = ArrayDeque<PendingDir>().apply { add(PendingDir(rootId, "")) }
        val tasks = ArrayList<MetadataTask>(512)
        var discovered = 0
        var indexed = 0
        var newFiles = 0
        var updatedFiles = 0
        var unchangedFiles = 0
        val newNames = ArrayList<String>()
        var errors = 0
        val scanToken = System.currentTimeMillis() * 1000L + (System.nanoTime() and 0x3FFL)

        while (queue.isNotEmpty()) {
            val pending = queue.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, pending.documentId)
            try {
                resolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val modifiedCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                    while (cursor.moveToNext()) {
                        val docId = cursor.getString(idCol)
                        val name = cursor.getString(nameCol) ?: ""
                        val rawMime = cursor.getString(mimeCol) ?: "application/octet-stream"
                        if (rawMime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (name == ".LocalFeedTrash") continue
                            queue.addLast(PendingDir(docId, pending.relativeDir + name + "/"))
                            continue
                        }
                        val kind = mediaKind(rawMime, name) ?: continue
                        discovered++
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        val resolvedMime = if (rawMime == "application/octet-stream") guessMime(name, kind) else rawMime
                        val relativePath = pending.relativeDir + name
                        val record = MediaRecord(
                            id = -1L,
                            uri = fileUri.toString(),
                            rootUri = treeUri.toString(),
                            relativePath = relativePath,
                            name = name,
                            mime = resolvedMime,
                            kind = kind,
                            size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L,
                            modifiedAt = if (modifiedCol >= 0 && !cursor.isNull(modifiedCol)) cursor.getLong(modifiedCol) else 0L
                        )
                        val outcome = db.upsertBasic(record, scanToken)
                        if (outcome.id >= 0) indexed++
                        when {
                            outcome.isNew -> { newFiles++; if (newNames.size < 100) newNames += relativePath }
                            outcome.contentChanged -> updatedFiles++
                            else -> unchangedFiles++
                        }
                        if (outcome.metadataNeeded) tasks += MetadataTask(fileUri, kind, name, outcome.isNew)
                        if (discovered % 100 == 0) onProgress(discovered)
                    }
                }
            } catch (e: Exception) {
                errors++
                db.recordError(children.toString(), pending.relativeDir.ifBlank { "根目录" }, "索引", e.javaClass.simpleName + ": " + (e.message ?: "读取失败"))
            }
        }

        if (errors == 0) db.pruneRootNotSeen(treeUri.toString(), scanToken)
        db.updateFolderScan(treeUri.toString())
        return BasicResult(discovered, indexed, newFiles, updatedFiles, unchangedFiles, newNames, tasks, errors)
    }

    fun enrichMetadata(tasks: List<MetadataTask>, onProgress: (Int, Int) -> Unit = { _, _ -> }): MetadataResult {
        val resolver = context.contentResolver
        var errors = 0
        var newFileErrors = 0
        tasks.forEachIndexed { index, task ->
            try {
                val meta = if (task.kind == MediaKind.VIDEO) videoMetadata(task.uri) else imageMetadata(resolver, task.uri)
                db.updateMetadata(task.uri.toString(), meta.durationMs, meta.width, meta.height, meta.rotation)
                db.clearError(task.uri.toString())
            } catch (e: Exception) {
                errors++
                if (task.isNew) newFileErrors++
                db.recordError(task.uri.toString(), task.name, "元数据", e.javaClass.simpleName + ": " + (e.message ?: "读取失败"))
            }
            if ((index + 1) % 25 == 0 || index == tasks.lastIndex) onProgress(index + 1, tasks.size)
        }
        return MetadataResult(errors, newFileErrors)
    }

    private data class Meta(val durationMs: Long, val width: Int, val height: Int, val rotation: Int)

    private fun videoMetadata(uri: Uri): Meta {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            Meta(
                durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            )
        } finally {
            mmr.release()
        }
    }

    private fun imageMetadata(resolver: ContentResolver, uri: Uri): Meta {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        return Meta(0L, opts.outWidth.coerceAtLeast(0), opts.outHeight.coerceAtLeast(0), 0)
    }

    private fun mediaKind(mime: String, name: String): MediaKind? {
        if (mime.startsWith("video/")) return MediaKind.VIDEO
        if (mime.startsWith("image/")) return MediaKind.IMAGE
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in setOf("mp4", "mkv", "webm", "mov", "m4v", "3gp", "ts", "flv")) return MediaKind.VIDEO
        if (ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "avif")) return MediaKind.IMAGE
        return null
    }

    private fun guessMime(name: String, kind: MediaKind): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (kind == MediaKind.VIDEO) "video/*" else "image/*"
    }
}
