package com.localfeed.app.ui

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.localfeed.app.core.MediaRecord
import com.localfeed.app.media.ThumbnailLoader

/** Recycled, width-fitted image strip for manga/comic-style continuous reading. */
class ComicReaderAdapter(private val loader: ThumbnailLoader) : RecyclerView.Adapter<ComicReaderAdapter.Holder>() {
    private var items = emptyList<MediaRecord>()

    init { setHasStableIds(true) }

    fun submit(value: List<MediaRecord>) {
        items = value
        notifyDataSetChanged()
    }

    fun positionOf(id: Long): Int = items.indexOfFirst { it.id == id }
    fun itemAt(position: Int): MediaRecord? = items.getOrNull(position)

    override fun getItemId(position: Int): Long = items[position].id
    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val density = parent.resources.displayMetrics.density
        val root = FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            minimumHeight = (240 * density).toInt()
            setBackgroundColor(Color.BLACK)
        }
        val image = ImageView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            contentDescription = "连续阅读图片"
        }
        val error = TextView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            setPadding((18 * density).toInt(), (18 * density).toInt(), (18 * density).toInt(), (18 * density).toInt())
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            text = "图片读取失败"
            visibility = View.GONE
        }
        root.addView(image)
        root.addView(error)
        return Holder(root, image, error)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val record = items[position]
        holder.error.visibility = View.GONE
        holder.image.visibility = View.VISIBLE
        loader.load(record, holder.image, 1800) { ok ->
            holder.error.text = if (ok) "" else "${record.name}\n读取失败"
            holder.error.visibility = if (ok) View.GONE else View.VISIBLE
            holder.image.visibility = if (ok) View.VISIBLE else View.INVISIBLE
        }
    }

    class Holder(root: View, val image: ImageView, val error: TextView) : RecyclerView.ViewHolder(root)
}
