package com.localfeed.app.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.localfeed.app.core.MediaRecord
import com.localfeed.app.media.ThumbnailLoader
import java.text.DateFormat

class TaskCenterAdapter(
    private val thumbnails: ThumbnailLoader,
    private val resolve: (String) -> MediaRecord?
) : RecyclerView.Adapter<TaskCenterAdapter.Holder>() {
    private var items = emptyList<MediaTask>()
    private val recordCache = HashMap<String, MediaRecord>()
    fun submit(value: List<MediaTask>) { items = value; notifyDataSetChanged() }
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val d = parent.resources.displayMetrics.density
        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding((12*d).toInt(), (10*d).toInt(), (14*d).toInt(), (10*d).toInt())
            background = GradientDrawable().apply { setColor(Color.rgb(28,28,30)); cornerRadius = 12*d }
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins((10*d).toInt(), (5*d).toInt(), (10*d).toInt(), (5*d).toInt()) }
        }
        val thumb = ImageView(parent.context).apply { scaleType=ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(47,47,52)); layoutParams=LinearLayout.LayoutParams((56*d).toInt(),(56*d).toInt()).apply { marginEnd=(12*d).toInt() } }
        val content = LinearLayout(parent.context).apply { orientation=LinearLayout.VERTICAL; layoutParams=LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f) }
        val title = TextView(parent.context).apply { setTextColor(Color.WHITE); textSize=15f; setTypeface(typeface, 1) }
        val detail = TextView(parent.context).apply { setTextColor(Color.rgb(190,190,196)); textSize=12f; setPadding(0,(5*d).toInt(),0,0) }
        val progress = ProgressBar(parent.context, null, android.R.attr.progressBarStyleHorizontal).apply { max=1000; layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,(4*d).toInt()).apply { topMargin=(8*d).toInt() } }
        val status = TextView(parent.context).apply { setTextColor(Color.rgb(145,145,153)); textSize=11f; gravity=Gravity.END; setPadding(0,(5*d).toInt(),0,0) }
        content.addView(title); content.addView(detail); content.addView(progress); content.addView(status)
        root.addView(thumb); root.addView(content)
        return Holder(root,thumb,title,detail,progress,status)
    }
    override fun onBindViewHolder(h: Holder, position: Int) = h.bind(items[position])
    inner class Holder(root: LinearLayout,val thumb:ImageView,val title:TextView,val detail:TextView,val progress:ProgressBar,val status:TextView):RecyclerView.ViewHolder(root){
        fun bind(t:MediaTask){ title.text=t.title; detail.text=t.detail
            detail.maxLines=3
            detail.ellipsize=android.text.TextUtils.TruncateAt.END
            itemView.setOnClickListener {
                val context=itemView.context
                val body=TextView(context).apply {
                    text=t.detail.ifBlank { "这条旧记录没有保存详细原因，需重新执行任务以产生诊断记录。" }
                    textSize=14f; setTextColor(Color.rgb(225,225,232)); setTextIsSelectable(true)
                    val pad=(20*resources.displayMetrics.density).toInt(); setPadding(pad,pad,pad,pad)
                    setBackgroundColor(Color.rgb(28,28,30))
                }
                val scroll=android.widget.ScrollView(context).apply { addView(body) }
                android.app.AlertDialog.Builder(context).setTitle(t.title).setView(scroll)
                    .setPositiveButton("关闭",null).setNeutralButton("复制详情") { _, _ ->
                        val clipboard=context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("任务详情",body.text))
                    }.show()
            }
            thumb.setImageDrawable(null)
            val record = t.thumbnailUri.takeIf { it.isNotBlank() }?.let { uri ->
                resolve(uri)?.also { recordCache[uri] = it } ?: recordCache[uri]
            }
            if(record != null){ thumb.visibility=android.view.View.VISIBLE; thumbnails.load(record, thumb, 240) } else { thumb.tag = null; thumb.visibility=android.view.View.GONE }
            progress.visibility=if(t.state==TaskState.RUNNING) android.view.View.VISIBLE else android.view.View.GONE
            progress.isIndeterminate=t.total<=0; if(t.total>0) progress.progress=(t.progress*1000/t.total.coerceAtLeast(1))
            val stateLabel=when {
                t.state==TaskState.FAILED && t.detail.contains("副本状态：已校验归档") && t.detail.contains("来源状态：未删除") -> "已归档 · 来源待删除"
                t.state==TaskState.QUEUED && t.title.startsWith("收件箱") -> "等待检查"
                else -> when(t.state){TaskState.QUEUED->"排队";TaskState.RUNNING->"进行中";TaskState.DONE->"已完成";TaskState.FAILED->"失败"}
            }
            status.text=stateLabel+" · "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(t.updatedAt)
            status.setTextColor(if(t.state==TaskState.FAILED) Color.rgb(255,110,105) else Color.rgb(145,145,153)) }
    }
}
