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
import java.text.DateFormat

class TaskCenterAdapter : RecyclerView.Adapter<TaskCenterAdapter.Holder>() {
    private var items = emptyList<MediaTask>()
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
    class Holder(root: LinearLayout,val thumb:ImageView,val title:TextView,val detail:TextView,val progress:ProgressBar,val status:TextView):RecyclerView.ViewHolder(root){
        fun bind(t:MediaTask){ title.text=t.title; detail.text=t.detail
            thumb.setImageDrawable(null)
            if(t.thumbnailUri.isNotBlank()){ thumb.visibility=android.view.View.VISIBLE; runCatching { thumb.setImageURI(android.net.Uri.parse(t.thumbnailUri)) } } else thumb.visibility=android.view.View.GONE
            progress.visibility=if(t.state==TaskState.RUNNING) android.view.View.VISIBLE else android.view.View.GONE
            progress.isIndeterminate=t.total<=0; if(t.total>0) progress.progress=(t.progress*1000/t.total.coerceAtLeast(1))
            status.text="${when(t.state){TaskState.QUEUED->"排队";TaskState.RUNNING->"进行中";TaskState.DONE->"已完成";TaskState.FAILED->"失败"}} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(t.updatedAt)}"
            status.setTextColor(if(t.state==TaskState.FAILED) Color.rgb(255,110,105) else Color.rgb(145,145,153)) }
    }
}
