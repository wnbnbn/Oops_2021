package android.graphics
import java.io.InputStream
object BitmapFactory {
    class Options { var inJustDecodeBounds=false; var outWidth=0; var outHeight=0 }
    fun decodeStream(input:InputStream,padding:Any?,options:Options):Any? {
        options.outWidth=400; options.outHeight=600; return null
    }
}
