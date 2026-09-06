package com.localfeed.app.ui

import android.graphics.Color
import android.view.ViewGroup
import android.widget.ImageView
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
        val image = ImageView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            contentDescription = "连续阅读图片"
        }
        return Holder(image)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        loader.load(items[position], holder.image, 1800)
    }

    class Holder(val image: ImageView) : RecyclerView.ViewHolder(image)
}
