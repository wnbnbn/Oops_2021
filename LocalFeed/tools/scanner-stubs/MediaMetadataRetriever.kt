package android.media
import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
class MediaMetadataRetriever {
    fun setDataSource(context: Context, uri: Uri) { extractionAttempts++; error("No decoder in JVM smoke test") }
    fun extractMetadata(key: Int): String? = null
    fun getFrameAtTime(time: Long, option: Int): Bitmap? = null
    fun getScaledFrameAtTime(time: Long, option: Int, width: Int, height: Int): Bitmap? = null
    fun release() {}
    companion object { const val METADATA_KEY_DURATION = 9; const val OPTION_CLOSEST_SYNC = 2; var extractionAttempts = 0 }
}
