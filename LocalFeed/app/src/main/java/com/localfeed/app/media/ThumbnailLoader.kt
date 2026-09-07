package com.localfeed.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.util.LruCache
import android.widget.ImageView
import com.localfeed.app.core.MediaKind
import com.localfeed.app.core.MediaRecord
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max

/**
 * Two-level thumbnail/poster cache.
 * Memory avoids rebinding cost while swiping; disk avoids repeatedly decoding video frame 0
 * after holders are recycled or the app is reopened.
 */
class ThumbnailLoader(private val context: Context) {
    private val executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "thumb-worker") }
    private val cacheDir = File(context.cacheDir, "thumbs_v2").apply { mkdirs() }
    private val jobs = Collections.synchronizedMap(WeakHashMap<ImageView, Future<*>>())
    private val memory = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    init {
        executor.execute { pruneDiskCache() }
    }

    fun load(record: MediaRecord, view: ImageView, targetPx: Int = 720, onResult: ((Boolean) -> Unit)? = null) {
        val key = "${record.uri}|${record.size}|$targetPx"
        jobs.remove(view)?.cancel(false)
        if (record.kind == MediaKind.IMAGE && targetPx >= 1200 && Build.VERSION.SDK_INT >= 28) {
            loadFullImageDrawable(record, view, targetPx, key, onResult)
            return
        }
        memory.get(key)?.let {
            view.tag = key
            view.setImageBitmap(it)
            onResult?.invoke(true)
            return
        }

        view.tag = key
        view.setImageDrawable(null)
        val future = executor.submit {
            if (Thread.currentThread().isInterrupted) return@submit
            val disk = diskFile(key)
            val bitmap = decodeDisk(disk) ?: runCatching {
                if (record.kind == MediaKind.IMAGE) decodeImage(Uri.parse(record.uri), targetPx)
                else decodeVideo(Uri.parse(record.uri), targetPx)
            }.getOrNull()?.also { saveDisk(disk, it) }
            if (bitmap == null) {
                view.post { if (view.tag == key) onResult?.invoke(false) }
                jobs.remove(view)
                return@submit
            }

            memory.put(key, bitmap)
            view.post {
                if (view.tag == key) {
                    view.setImageBitmap(bitmap)
                    onResult?.invoke(true)
                }
                jobs.remove(view)
            }
        }
        jobs[view] = future
    }

    private fun loadFullImageDrawable(record: MediaRecord, view: ImageView, targetPx: Int, key: String, onResult: ((Boolean) -> Unit)?) {
        view.tag = key
        view.setImageDrawable(null)
        val future = executor.submit {
            val drawable = runCatching {
                val source = ImageDecoder.createSource(context.contentResolver, Uri.parse(record.uri))
                ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                    val largest = max(info.size.width, info.size.height).coerceAtLeast(1)
                    if (largest > targetPx * 2) decoder.setTargetSampleSize((largest / (targetPx * 2)).coerceAtLeast(1))
                }
            }.getOrNull()
            view.post {
                if (view.tag == key) {
                    view.setImageDrawable(drawable)
                    (drawable as? AnimatedImageDrawable)?.apply { repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; start() }
                    onResult?.invoke(drawable != null)
                }
                jobs.remove(view)
            }
        }
        jobs[view] = future
    }

    fun release() {
        jobs.values.forEach { it.cancel(false) }
        jobs.clear()
        executor.shutdownNow()
    }

    private fun decodeImage(uri: Uri, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val largest = max(bounds.outWidth, bounds.outHeight)
        while (largest / sample > target * 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun decodeVideo(uri: Uri, target: Int): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val frame = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val maxSide = max(frame.width, frame.height)
            if (maxSide <= target) frame else {
                val scale = target.toFloat() / maxSide
                Bitmap.createScaledBitmap(
                    frame,
                    (frame.width * scale).toInt().coerceAtLeast(1),
                    (frame.height * scale).toInt().coerceAtLeast(1),
                    true
                ).also { if (it !== frame) frame.recycle() }
            }
        } finally {
            mmr.release()
        }
    }

    private fun diskFile(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$name.jpg")
    }

    private fun decodeDisk(file: File): Bitmap? {
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    private fun saveDisk(file: File, bitmap: Bitmap) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().buffered().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 86, out) }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun pruneDiskCache() {
        val files = cacheDir.listFiles()?.filter { it.isFile && it.extension == "jpg" } ?: return
        val total = files.sumOf { it.length() }
        val high = 384L * 1024L * 1024L
        val low = 256L * 1024L * 1024L
        if (total <= high) return
        var remaining = total
        for (file in files.sortedBy { it.lastModified() }) {
            if (remaining <= low) break
            val length = file.length()
            if (file.delete()) remaining -= length
        }
    }
}
