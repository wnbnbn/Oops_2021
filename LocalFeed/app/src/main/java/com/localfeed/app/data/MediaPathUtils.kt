package com.localfeed.app.data

import android.net.Uri
import android.provider.DocumentsContract
import com.localfeed.app.core.MediaRecord

object MediaPathUtils {
    fun absoluteFilePath(record: MediaRecord): String {
        val direct = externalStoragePath(Uri.parse(record.uri))
        if (direct != null) return direct
        val root = externalStoragePathFromTree(Uri.parse(record.rootUri))
        if (root != null) return if (record.relativePath.isBlank()) root else "$root/${record.relativePath}"
        return record.relativePath.ifBlank { record.uri }
    }

    fun absoluteDirectoryPath(record: MediaRecord): String {
        val file = absoluteFilePath(record)
        return file.substringBeforeLast('/', file)
    }

    fun rootPath(rootUri: String, fallbackName: String): String {
        return externalStoragePathFromTree(Uri.parse(rootUri)) ?: fallbackName
    }

    private fun externalStoragePath(uri: Uri): String? = runCatching {
        val id = DocumentsContract.getDocumentId(uri)
        docIdToPath(id)
    }.getOrNull()

    private fun externalStoragePathFromTree(uri: Uri): String? = runCatching {
        val id = DocumentsContract.getTreeDocumentId(uri)
        docIdToPath(id)
    }.getOrNull()

    private fun docIdToPath(id: String): String? {
        val colon = id.indexOf(':')
        if (colon <= 0) return null
        val volume = id.substring(0, colon)
        val path = id.substring(colon + 1).trimStart('/')
        val base = if (volume.equals("primary", true)) "/storage/emulated/0" else "/storage/$volume"
        return if (path.isBlank()) base else "$base/$path"
    }
}
