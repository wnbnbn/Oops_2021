package com.localfeed.app.data
import android.content.Context
import com.localfeed.app.core.MediaRecord
class MediaIndexDb(context:Context) {
    data class UpsertOutcome(val id:Long,val isNew:Boolean,val contentChanged:Boolean,val metadataNeeded:Boolean)
    val rows=linkedMapOf<Long,MediaRecord>()
    var pruneCalls=0
    fun upsertBasic(record:MediaRecord,token:Long):UpsertOutcome {
        val old=rows.values.firstOrNull { it.uri==record.uri }
        val id=old?.id ?: ((rows.keys.maxOrNull() ?: 0L)+1)
        rows[id]=record.copy(id=id,likeCount=old?.likeCount ?: 0,favorited=old?.favorited ?: false,
            width=old?.width ?: 0,height=old?.height ?: 0,hidden=old?.hidden ?: false,trashedAt=old?.trashedAt ?: 0)
        return UpsertOutcome(id,old==null,false,old==null || old.width==0)
    }
    fun recordById(id:Long)=rows[id]
    fun recordByUri(uri:String)=rows.values.firstOrNull { it.uri==uri }
    fun updateMetadata(uri:String,duration:Long,width:Int,height:Int,rotation:Int) {
        recordByUri(uri)?.let { rows[it.id]=it.copy(durationMs=duration,width=width,height=height,rotation=rotation) }
    }
    fun pruneRootNotSeen(uri:String,token:Long) { pruneCalls++ }
    fun updateFolderScan(uri:String) {}
    fun recordError(uri:String,name:String,stage:String,message:String) {}
    fun clearError(uri:String) {}
    fun clearError(uri:String,stage:String) {}
}
