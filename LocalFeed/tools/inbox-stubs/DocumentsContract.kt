package android.provider
import android.net.Uri
object DocumentsContract {
    fun getTreeDocumentId(uri:Uri)=uri.toString().substringAfter("/tree/").substringBefore("/document/")
    fun getDocumentId(uri:Uri)=uri.toString().let { if(it.contains("/document/")) it.substringAfter("/document/") else it.substringAfter("/tree/") }
    fun buildChildDocumentsUriUsingTree(uri:Uri,id:String)=Uri.parse(uri.toString()+"/children")
    object Document { const val COLUMN_DOCUMENT_ID="document_id" }
}
