package android.media
import android.content.Context
import android.net.Uri
class MediaMetadataRetriever {
    companion object {
        const val METADATA_KEY_DURATION=1
        const val METADATA_KEY_VIDEO_WIDTH=2
        const val METADATA_KEY_VIDEO_HEIGHT=3
        const val METADATA_KEY_VIDEO_ROTATION=4
    }
    fun setDataSource(context:Context,uri:Uri) {}
    fun extractMetadata(key:Int):String="0"
    fun release() {}
}
