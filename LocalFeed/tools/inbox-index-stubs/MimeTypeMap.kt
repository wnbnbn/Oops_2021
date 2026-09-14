package android.webkit
class MimeTypeMap {
    companion object { fun getSingleton()=MimeTypeMap() }
    fun getMimeTypeFromExtension(ext:String):String?=when(ext) {
        "jpg","jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> null
    }
}
