package android.provider
import android.net.Uri
object DocumentsContract {
    fun getTreeDocumentId(uri:Uri)=uri.toString().substringAfter("/tree/").substringBefore("/document/")
    fun getDocumentId(uri:Uri)=uri.toString().let { if(it.contains("/document/")) it.substringAfter("/document/") else it.substringAfter("/tree/") }
    fun buildChildDocumentsUriUsingTree(uri:Uri,id:String)=Uri.parse(uri.toString()+"/children")
    fun buildDocumentUriUsingTree(uri:Uri,id:String)=Uri.parse(uri.toString().substringBefore("/document/")+"/document/"+id)
    object Document {
        const val COLUMN_DOCUMENT_ID="document_id"
        const val COLUMN_DISPLAY_NAME="_display_name"
        const val COLUMN_MIME_TYPE="mime_type"
        const val COLUMN_SIZE="_size"
        const val COLUMN_LAST_MODIFIED="last_modified"
        const val MIME_TYPE_DIR="vnd.android.document/directory"
    }
}
