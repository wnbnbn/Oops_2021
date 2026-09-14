package android.provider
import android.net.Uri
object DocumentsContract { fun getTreeDocumentId(uri:Uri)=uri.toString().substringAfter("/tree/") }
