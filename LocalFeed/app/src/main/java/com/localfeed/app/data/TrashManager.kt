package com.localfeed.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.localfeed.app.core.MediaRecord
import java.io.FileNotFoundException

class TrashManager(private val context: Context, private val db: MediaIndexDb) {
    data class Result(val ok: Boolean, val message: String)

    fun moveToTrash(record: MediaRecord): Result = runCatching {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(record.rootUri)) ?: error("目录授权已失效")
        val sourceParent = findDirectory(root, record.parentRelativePath()) ?: error("找不到原文件夹")
        val trashRoot = root.findFile(".LocalFeedTrash") ?: root.createDirectory(".LocalFeedTrash") ?: error("无法建立最近删除目录")
        val slot = trashRoot.findFile(record.id.toString()) ?: trashRoot.createDirectory(record.id.toString()) ?: error("无法建立回收目录")
        val moved = DocumentsContract.moveDocument(
            context.contentResolver,
            Uri.parse(record.uri),
            sourceParent.uri,
            slot.uri
        ) ?: error("当前文件提供器不支持移动到最近删除")
        db.markTrashed(
            id = record.id,
            trashUri = moved.toString(),
            originalUri = record.uri,
            originalRelativePath = record.relativePath,
            trashRelativePath = ".LocalFeedTrash/${record.id}/${record.name}"
        )
        Result(true, "已移到最近删除")
    }.getOrElse { Result(false, it.message ?: "移动失败") }

    fun restore(record: MediaRecord): Result = runCatching {
        val original = db.originalRelativePath(record.id)
        if (original.isBlank()) error("缺少原路径记录")
        val root = DocumentFile.fromTreeUri(context, Uri.parse(record.rootUri)) ?: error("目录授权已失效")
        val targetParent = findDirectory(root, original.substringBeforeLast('/', "")) ?: error("原文件夹已经不存在")
        val trashRoot = root.findFile(".LocalFeedTrash") ?: error("最近删除目录不存在")
        val slot = trashRoot.findFile(record.id.toString()) ?: error("回收目录不存在")
        val moved = DocumentsContract.moveDocument(
            context.contentResolver,
            Uri.parse(record.uri),
            slot.uri,
            targetParent.uri
        ) ?: error("恢复失败")
        db.restoreTrashed(record.id, moved.toString(), original)
        runCatching { slot.delete() }
        Result(true, "已恢复到原位置")
    }.getOrElse { Result(false, it.message ?: "恢复失败") }

    fun deletePermanently(record: MediaRecord): Result = runCatching {
        val deleted = DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(record.uri))
        if (!deleted) error("文件提供器拒绝删除")
        db.deleteRecord(record.id)
        val root = DocumentFile.fromTreeUri(context, Uri.parse(record.rootUri))
        runCatching { root?.findFile(".LocalFeedTrash")?.findFile(record.id.toString())?.delete() }
        Result(true, "已永久删除")
    }.getOrElse { error ->
        // A process may die after the provider deleted the file but before SQLite was updated.
        // Treat a missing document as an already-completed retry; permission failures stay failed.
        if (error is FileNotFoundException) {
            db.deleteRecord(record.id)
            Result(true, "文件已不存在，已清理索引")
        } else Result(false, error.message ?: "删除失败")
    }

    private fun findDirectory(root: DocumentFile, relativeDir: String): DocumentFile? {
        if (relativeDir.isBlank()) return root
        var current: DocumentFile = root
        relativeDir.split('/').filter { it.isNotBlank() }.forEach { part ->
            val next = current.findFile(part)
            if (next == null || !next.isDirectory) return null
            current = next
        }
        return current
    }
}
