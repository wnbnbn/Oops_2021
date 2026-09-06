package com.localfeed.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.localfeed.app.core.MediaKind
import com.localfeed.app.core.MediaRecord


data class FolderInfo(
    val rootUri: String,
    val displayName: String,
    val noMediaCreated: Boolean,
    val lastScanAt: Long,
    val mediaCount: Int,
    val videoCount: Int,
    val imageCount: Int,
    val totalBytes: Long
)

data class ProblemMedia(
    val uri: String,
    val name: String,
    val stage: String,
    val message: String,
    val updatedAt: Long
)

data class HashState(
    val quickHash: String,
    val fullHash: String,
    val hashSize: Long,
    val hashModifiedAt: Long
)

data class VisualHashState(
    val hashes: String,
    val version: Int,
    val size: Long,
    val modifiedAt: Long
)

data class UpsertOutcome(
    val id: Long,
    val isNew: Boolean,
    val contentChanged: Boolean,
    val metadataNeeded: Boolean
)

class MediaIndexDb(context: Context) : SQLiteOpenHelper(context, "local_feed.db", null, 7) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE media (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uri TEXT NOT NULL UNIQUE,
                root_uri TEXT NOT NULL,
                relative_path TEXT NOT NULL DEFAULT '',
                name TEXT NOT NULL,
                mime TEXT NOT NULL,
                kind TEXT NOT NULL,
                size INTEGER NOT NULL DEFAULT 0,
                modified_at INTEGER NOT NULL DEFAULT 0,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                width INTEGER NOT NULL DEFAULT 0,
                height INTEGER NOT NULL DEFAULT 0,
                rotation INTEGER NOT NULL DEFAULT 0,
                liked INTEGER NOT NULL DEFAULT 0,
                favorited INTEGER NOT NULL DEFAULT 0,
                last_shown_at INTEGER NOT NULL DEFAULT 0,
                show_count INTEGER NOT NULL DEFAULT 0,
                playback_position_ms INTEGER NOT NULL DEFAULT 0,
                fit_mode INTEGER NOT NULL DEFAULT 0,
                hidden INTEGER NOT NULL DEFAULT 0,
                last_seen_token INTEGER NOT NULL DEFAULT 0,
                added_at INTEGER NOT NULL,
                trashed_at INTEGER NOT NULL DEFAULT 0,
                original_uri TEXT NOT NULL DEFAULT '',
                original_relative_path TEXT NOT NULL DEFAULT '',
                quick_hash TEXT NOT NULL DEFAULT '',
                full_hash TEXT NOT NULL DEFAULT '',
                hash_size INTEGER NOT NULL DEFAULT 0,
                hash_modified_at INTEGER NOT NULL DEFAULT 0,
                visual_hashes TEXT NOT NULL DEFAULT '',
                visual_hash_version INTEGER NOT NULL DEFAULT 0,
                visual_hash_size INTEGER NOT NULL DEFAULT 0,
                visual_hash_modified_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX media_visible_idx ON media(hidden, trashed_at, added_at DESC)")
        db.execSQL("CREATE INDEX media_root_idx ON media(root_uri)")
        db.execSQL("CREATE INDEX media_kind_idx ON media(kind, hidden, trashed_at)")
        db.execSQL("CREATE INDEX media_duration_idx ON media(kind, duration_ms, hidden, trashed_at)")
        db.execSQL("CREATE INDEX media_modified_idx ON media(modified_at DESC)")
        db.execSQL("CREATE INDEX media_size_idx ON media(size DESC)")
        db.execSQL("CREATE INDEX media_last_shown_idx ON media(last_shown_at DESC)")
        db.execSQL(
            """
            CREATE TABLE folders (
                root_uri TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                nomedia_created INTEGER NOT NULL DEFAULT 0,
                last_scan_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE media_errors (
                uri TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                stage TEXT NOT NULL,
                message TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE similar_decisions (
                a_id INTEGER NOT NULL,
                b_id INTEGER NOT NULL,
                decision TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(a_id, b_id)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE media ADD COLUMN last_seen_token INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE media ADD COLUMN relative_path TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE media ADD COLUMN modified_at INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE media ADD COLUMN trashed_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE media ADD COLUMN original_uri TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE media ADD COLUMN original_relative_path TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE media ADD COLUMN quick_hash TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE media ADD COLUMN full_hash TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE media ADD COLUMN hash_size INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE media ADD COLUMN hash_modified_at INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 5) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS media_errors (
                    uri TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    stage TEXT NOT NULL,
                    message TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS media_kind_idx ON media(kind, hidden, trashed_at)")
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE media ADD COLUMN visual_hashes TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE media ADD COLUMN visual_hash_version INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE media ADD COLUMN visual_hash_size INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE media ADD COLUMN visual_hash_modified_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS similar_decisions (
                    a_id INTEGER NOT NULL,
                    b_id INTEGER NOT NULL,
                    decision TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(a_id, b_id)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS media_duration_idx ON media(kind, duration_ms, hidden, trashed_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS media_modified_idx ON media(modified_at DESC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS media_size_idx ON media(size DESC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS media_last_shown_idx ON media(last_shown_at DESC)")
        }
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE media ADD COLUMN playback_position_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE media ADD COLUMN fit_mode INTEGER NOT NULL DEFAULT 0")
            db.execSQL("DELETE FROM media_errors WHERE stage='相似扫描'")
        }
    }

    fun addFolder(rootUri: String, displayName: String, noMediaCreated: Boolean) {
        val oldLastScan = readableDatabase.rawQuery(
            "SELECT last_scan_at FROM folders WHERE root_uri=?", arrayOf(rootUri)
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        val cv = ContentValues().apply {
            put("root_uri", rootUri)
            put("display_name", displayName)
            put("nomedia_created", if (noMediaCreated) 1 else 0)
            put("last_scan_at", oldLastScan)
        }
        writableDatabase.insertWithOnConflict("folders", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun setFolderNoMedia(rootUri: String, created: Boolean) {
        val cv = ContentValues().apply { put("nomedia_created", if (created) 1 else 0) }
        writableDatabase.update("folders", cv, "root_uri=?", arrayOf(rootUri))
    }

    fun removeFolder(rootUri: String) {
        writableDatabase.beginTransaction()
        try {
            val uris = readableDatabase.rawQuery("SELECT uri FROM media WHERE root_uri=?", arrayOf(rootUri)).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            if (uris.isNotEmpty()) {
                uris.chunked(500).forEach { chunk ->
                    val marks = chunk.joinToString(",") { "?" }
                    writableDatabase.delete("media_errors", "uri IN ($marks)", chunk.toTypedArray())
                }
            }
            writableDatabase.delete("media", "root_uri=?", arrayOf(rootUri))
            writableDatabase.delete("folders", "root_uri=?", arrayOf(rootUri))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun folderUris(): List<String> = readableDatabase.rawQuery(
        "SELECT root_uri FROM folders ORDER BY rowid", null
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    fun folderInfos(): List<FolderInfo> = readableDatabase.rawQuery(
        """
        SELECT f.root_uri, f.display_name, f.nomedia_created, f.last_scan_at,
               COUNT(CASE WHEN m.hidden=0 AND m.trashed_at=0 THEN 1 END),
               COUNT(CASE WHEN m.hidden=0 AND m.trashed_at=0 AND m.kind='VIDEO' THEN 1 END),
               COUNT(CASE WHEN m.hidden=0 AND m.trashed_at=0 AND m.kind='IMAGE' THEN 1 END),
               COALESCE(SUM(CASE WHEN m.hidden=0 AND m.trashed_at=0 THEN m.size ELSE 0 END), 0)
        FROM folders f
        LEFT JOIN media m ON m.root_uri=f.root_uri
        GROUP BY f.root_uri, f.display_name, f.nomedia_created, f.last_scan_at, f.rowid
        ORDER BY f.rowid
        """.trimIndent(), null
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(FolderInfo(
                    rootUri = c.getString(0),
                    displayName = c.getString(1),
                    noMediaCreated = c.getInt(2) != 0,
                    lastScanAt = c.getLong(3),
                    mediaCount = c.getInt(4),
                    videoCount = c.getInt(5),
                    imageCount = c.getInt(6),
                    totalBytes = c.getLong(7)
                ))
            }
        }
    }

    fun updateFolderScan(rootUri: String) {
        val cv = ContentValues().apply { put("last_scan_at", System.currentTimeMillis()) }
        writableDatabase.update("folders", cv, "root_uri=?", arrayOf(rootUri))
    }

    fun upsertBasic(record: MediaRecord, scanToken: Long): UpsertOutcome {
        val old = readableDatabase.rawQuery(
            "SELECT id,size,modified_at,width,height FROM media WHERE uri=?", arrayOf(record.uri)
        ).use { c ->
            if (c.moveToFirst()) longArrayOf(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4)) else null
        }
        val changedContent = old != null && (old[1] != record.size || old[2] != record.modifiedAt)
        val update = ContentValues().apply {
            put("root_uri", record.rootUri)
            put("relative_path", record.relativePath)
            put("name", record.name)
            put("mime", record.mime)
            put("kind", record.kind.name)
            put("size", record.size)
            put("modified_at", record.modifiedAt)
            put("last_seen_token", scanToken)
            put("trashed_at", 0)
            if (changedContent) {
                put("quick_hash", "")
                put("full_hash", "")
                put("hash_size", 0)
                put("hash_modified_at", 0)
                put("visual_hashes", "")
                put("visual_hash_version", 0)
                put("visual_hash_size", 0)
                put("visual_hash_modified_at", 0)
            }
        }
        val changed = writableDatabase.update("media", update, "uri=?", arrayOf(record.uri))
        if (changed == 0) {
            val insert = ContentValues(update).apply {
                put("uri", record.uri)
                put("added_at", record.addedAt)
            }
            writableDatabase.insert("media", null, insert)
        }
        val id = old?.get(0) ?: readableDatabase.rawQuery("SELECT id FROM media WHERE uri=?", arrayOf(record.uri)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else -1L
        }
        return UpsertOutcome(
            id = id,
            isNew = old == null,
            contentChanged = changedContent,
            metadataNeeded = old == null || changedContent || old[3] <= 0L || old[4] <= 0L
        )
    }

    fun pruneRootNotSeen(rootUri: String, scanToken: Long): Int {
        return writableDatabase.delete(
            "media",
            "root_uri=? AND trashed_at=0 AND last_seen_token<>?",
            arrayOf(rootUri, scanToken.toString())
        )
    }

    fun updateMetadata(uri: String, durationMs: Long, width: Int, height: Int, rotation: Int) {
        val cv = ContentValues().apply {
            put("duration_ms", durationMs)
            put("width", width)
            put("height", height)
            put("rotation", rotation)
        }
        writableDatabase.update("media", cv, "uri=?", arrayOf(uri))
    }

    fun allVisible(): List<MediaRecord> = readableDatabase.rawQuery(
        "SELECT * FROM media WHERE hidden=0 AND trashed_at=0 ORDER BY added_at DESC, id DESC", null
    ).use(::readAll)

    fun allTrashed(): List<MediaRecord> = readableDatabase.rawQuery(
        "SELECT * FROM media WHERE trashed_at>0 ORDER BY trashed_at DESC", null
    ).use(::readAll)

    fun recordById(id: Long): MediaRecord? = readableDatabase.rawQuery(
        "SELECT * FROM media WHERE id=?", arrayOf(id.toString())
    ).use { c -> readAll(c).firstOrNull() }

    fun setLiked(id: Long, liked: Boolean) {
        val cv = ContentValues().apply { put("liked", if (liked) 1 else 0) }
        writableDatabase.update("media", cv, "id=?", arrayOf(id.toString()))
    }

    fun setLikedMany(ids: Collection<Long>, liked: Boolean) = updateMany(ids, "liked", if (liked) 1 else 0)

    fun setFavorited(id: Long, favorited: Boolean) {
        val cv = ContentValues().apply { put("favorited", if (favorited) 1 else 0) }
        writableDatabase.update("media", cv, "id=?", arrayOf(id.toString()))
    }

    fun setFavoritedMany(ids: Collection<Long>, favorited: Boolean) = updateMany(ids, "favorited", if (favorited) 1 else 0)

    fun setPlaybackPosition(id: Long, positionMs: Long) {
        val cv = ContentValues().apply { put("playback_position_ms", positionMs.coerceAtLeast(0L)) }
        writableDatabase.update("media", cv, "id=?", arrayOf(id.toString()))
    }

    fun setFitMode(id: Long, mode: Int) {
        val cv = ContentValues().apply { put("fit_mode", mode.coerceIn(0, 2)) }
        writableDatabase.update("media", cv, "id=?", arrayOf(id.toString()))
    }

    fun mergeDuplicateState(keepId: Long, removedIds: Collection<Long>) {
        if (removedIds.isEmpty()) return
        val allIds = (removedIds + keepId).distinct()
        val marks = allIds.joinToString(",") { "?" }
        val args = allIds.map { it.toString() }.toTypedArray()
        readableDatabase.rawQuery(
            "SELECT MAX(liked),MAX(favorited),MAX(last_shown_at),SUM(show_count),MAX(playback_position_ms) FROM media WHERE id IN ($marks)",
            args
        ).use { c ->
            if (!c.moveToFirst()) return
            val cv = ContentValues().apply {
                put("liked", c.getInt(0)); put("favorited", c.getInt(1)); put("last_shown_at", c.getLong(2))
                put("show_count", c.getInt(3)); put("playback_position_ms", c.getLong(4))
            }
            writableDatabase.update("media", cv, "id=?", arrayOf(keepId.toString()))
        }
    }

    fun markShown(id: Long) {
        writableDatabase.execSQL(
            "UPDATE media SET last_shown_at=?, show_count=show_count+1 WHERE id=?",
            arrayOf(System.currentTimeMillis(), id)
        )
    }

    fun hide(id: Long) {
        val cv = ContentValues().apply { put("hidden", 1) }
        writableDatabase.update("media", cv, "id=?", arrayOf(id.toString()))
    }

    fun hideMany(ids: Collection<Long>) = updateMany(ids, "hidden", 1)

    fun markTrashed(id: Long, trashUri: String, originalUri: String, originalRelativePath: String, trashRelativePath: String) {
        val cv = ContentValues().apply {
            put("uri", trashUri)
            put("relative_path", trashRelativePath)
            put("trashed_at", System.currentTimeMillis())
            put("original_uri", originalUri)
            put("original_relative_path", originalRelativePath)
        }
        writableDatabase.update("media", cv, "id=?", arrayOf(id.toString()))
    }

    fun restoreTrashed(id: Long, newUri: String, newRelativePath: String) {
        val cv = ContentValues().apply {
            put("uri", newUri)
            put("relative_path", newRelativePath)
            put("trashed_at", 0)
            put("original_uri", "")
            put("original_relative_path", "")
            put("last_seen_token", 0)
        }
        writableDatabase.update("media", cv, "id=?", arrayOf(id.toString()))
    }

    fun originalRelativePath(id: Long): String = readableDatabase.rawQuery(
        "SELECT original_relative_path FROM media WHERE id=?", arrayOf(id.toString())
    ).use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" }

    fun deleteRecord(id: Long) {
        val uri = readableDatabase.rawQuery("SELECT uri FROM media WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        writableDatabase.delete("media", "id=?", arrayOf(id.toString()))
        writableDatabase.delete("similar_decisions", "a_id=? OR b_id=?", arrayOf(id.toString(), id.toString()))
        if (uri != null) writableDatabase.delete("media_errors", "uri=?", arrayOf(uri))
    }

    fun hashState(id: Long): HashState? = readableDatabase.rawQuery(
        "SELECT quick_hash,full_hash,hash_size,hash_modified_at FROM media WHERE id=?", arrayOf(id.toString())
    ).use { c ->
        if (!c.moveToFirst()) null else HashState(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3))
    }

    fun updateQuickHash(id: Long, hash: String, size: Long, modifiedAt: Long) {
        val cv = ContentValues().apply {
            put("quick_hash", hash); put("hash_size", size); put("hash_modified_at", modifiedAt)
        }
        writableDatabase.update("media", cv, "id=?", arrayOf(id.toString()))
    }

    fun updateFullHash(id: Long, hash: String, size: Long, modifiedAt: Long) {
        val cv = ContentValues().apply {
            put("full_hash", hash); put("hash_size", size); put("hash_modified_at", modifiedAt)
        }
        writableDatabase.update("media", cv, "id=?", arrayOf(id.toString()))
    }

    fun visualHashState(id: Long): VisualHashState? = readableDatabase.rawQuery(
        "SELECT visual_hashes,visual_hash_version,visual_hash_size,visual_hash_modified_at FROM media WHERE id=?",
        arrayOf(id.toString())
    ).use { c ->
        if (!c.moveToFirst()) null else VisualHashState(c.getString(0), c.getInt(1), c.getLong(2), c.getLong(3))
    }

    fun updateVisualHashes(id: Long, hashes: String, version: Int, size: Long, modifiedAt: Long) {
        val cv = ContentValues().apply {
            put("visual_hashes", hashes)
            put("visual_hash_version", version)
            put("visual_hash_size", size)
            put("visual_hash_modified_at", modifiedAt)
        }
        writableDatabase.update("media", cv, "id=?", arrayOf(id.toString()))
    }

    fun notDuplicatePairs(): Set<Pair<Long, Long>> = readableDatabase.rawQuery(
        "SELECT a_id,b_id FROM similar_decisions WHERE decision='NOT_DUPLICATE'", null
    ).use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0) to c.getLong(1)) } }

    fun similarDecision(firstId: Long, secondId: Long): String? {
        val a = minOf(firstId, secondId)
        val b = maxOf(firstId, secondId)
        return readableDatabase.rawQuery(
            "SELECT decision FROM similar_decisions WHERE a_id=? AND b_id=?",
            arrayOf(a.toString(), b.toString())
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    fun setSimilarDecision(firstId: Long, secondId: Long, decision: String) {
        val a = minOf(firstId, secondId)
        val b = maxOf(firstId, secondId)
        val cv = ContentValues().apply {
            put("a_id", a); put("b_id", b); put("decision", decision); put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("similar_decisions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun recordError(uri: String, name: String, stage: String, message: String) {
        val cv = ContentValues().apply {
            put("uri", uri); put("name", name); put("stage", stage); put("message", message.take(500)); put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("media_errors", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearError(uri: String) {
        writableDatabase.delete("media_errors", "uri=?", arrayOf(uri))
    }

    fun clearError(uri: String, stage: String) {
        writableDatabase.delete("media_errors", "uri=? AND stage=?", arrayOf(uri, stage))
    }

    fun recordByUri(uri: String): MediaRecord? = readableDatabase.rawQuery(
        "SELECT * FROM media WHERE uri=?", arrayOf(uri)
    ).use { c -> readAll(c).firstOrNull() }

    fun problems(): List<ProblemMedia> = readableDatabase.rawQuery(
        "SELECT uri,name,stage,message,updated_at FROM media_errors ORDER BY updated_at DESC", null
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(ProblemMedia(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4)))
        }
    }

    private fun updateMany(ids: Collection<Long>, column: String, value: Int) {
        if (ids.isEmpty()) return
        ids.chunked(500).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            val args = chunk.map { it.toString() }.toTypedArray()
            val cv = ContentValues().apply { put(column, value) }
            writableDatabase.update("media", cv, "id IN ($marks)", args)
        }
    }

    private fun readAll(c: Cursor): List<MediaRecord> = buildList {
        val idx = (0 until c.columnCount).associateBy { c.getColumnName(it) }
        while (c.moveToNext()) {
            add(MediaRecord(
                id = c.getLong(idx.getValue("id")),
                uri = c.getString(idx.getValue("uri")),
                rootUri = c.getString(idx.getValue("root_uri")),
                relativePath = c.getString(idx.getValue("relative_path")) ?: "",
                name = c.getString(idx.getValue("name")),
                mime = c.getString(idx.getValue("mime")),
                kind = MediaKind.valueOf(c.getString(idx.getValue("kind"))),
                size = c.getLong(idx.getValue("size")),
                modifiedAt = c.getLong(idx.getValue("modified_at")),
                durationMs = c.getLong(idx.getValue("duration_ms")),
                width = c.getInt(idx.getValue("width")),
                height = c.getInt(idx.getValue("height")),
                rotation = c.getInt(idx.getValue("rotation")),
                liked = c.getInt(idx.getValue("liked")) != 0,
                favorited = c.getInt(idx.getValue("favorited")) != 0,
                lastShownAt = c.getLong(idx.getValue("last_shown_at")),
                showCount = c.getInt(idx.getValue("show_count")),
                playbackPositionMs = c.getLong(idx.getValue("playback_position_ms")),
                fitMode = c.getInt(idx.getValue("fit_mode")),
                hidden = c.getInt(idx.getValue("hidden")) != 0,
                addedAt = c.getLong(idx.getValue("added_at")),
                trashedAt = c.getLong(idx.getValue("trashed_at"))
            ))
        }
    }
}
