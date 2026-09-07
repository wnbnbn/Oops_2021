package com.localfeed.app.ui

import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.localfeed.app.core.MediaKind
import com.localfeed.app.core.MediaRecord
import com.localfeed.app.databinding.ItemAlbumBinding
import com.localfeed.app.databinding.ItemAlbumHeaderBinding
import com.localfeed.app.media.ThumbnailLoader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlbumAdapter(
    private val loader: ThumbnailLoader,
    private val onOpen: (MediaRecord) -> Unit,
    private val onSelectionChanged: (Set<Long>) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Header(val key: String, val title: String) : Row()
        data class Media(val record: MediaRecord) : Row()
    }

    companion object {
        private const val TYPE_MEDIA = 1
        private const val TYPE_HEADER = 2
        private const val PAYLOAD_SELECTION = "selection"
        private const val PAYLOAD_STATE = "state"
    }

    private val rows = mutableListOf<Row>()
    private val selected = linkedSetOf<Long>()
    private val dayFormat = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)
    private var cellSizePx: Int = 0
    private var highlightedMediaId: Long? = null
    private var problemIds: Set<Long> = emptySet()

    init { setHasStableIds(true) }

    fun setCellSize(px: Int) {
        if (px <= 0 || px == cellSizePx) return
        cellSizePx = px
        notifyItemRangeChanged(0, itemCount)
    }

    fun submit(list: List<MediaRecord>, grouping: TimeGrouping, onCommitted: (() -> Unit)? = null) {
        val newRows = buildRows(list, grouping)
        val existingIds = list.asSequence().map { it.id }.toSet()
        if (selected.retainAll(existingIds)) onSelectionChanged(selected.toSet())
        // Category/filter changes commonly replace most of a multi-thousand item gallery. Running
        // move detection for that case is slower than rebinding the handful of visible cells.
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
        onCommitted?.invoke()
    }

    fun updateRecord(record: MediaRecord) {
        val pos = rows.indexOfFirst { it is Row.Media && it.record.id == record.id }
        if (pos < 0) return
        rows[pos] = Row.Media(record)
        notifyItemChanged(pos, PAYLOAD_STATE)
    }

    fun highlightMedia(id: Long?) {
        if (highlightedMediaId == id) return
        val old = highlightedMediaId
        highlightedMediaId = id
        old?.let { adapterPositionForMediaId(it).takeIf { p -> p >= 0 }?.let { p -> notifyItemChanged(p, PAYLOAD_STATE) } }
        id?.let { adapterPositionForMediaId(it).takeIf { p -> p >= 0 }?.let { p -> notifyItemChanged(p, PAYLOAD_STATE) } }
    }

    fun setProblemIds(ids: Set<Long>) {
        if (problemIds == ids) return
        problemIds = ids
        notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE)
    }

    fun isSelectionMode(): Boolean = selected.isNotEmpty()
    fun selectedIds(): Set<Long> = selected.toSet()

    fun setSelectedIds(ids: Collection<Long>) {
        val visible = rows.mapNotNull { (it as? Row.Media)?.record?.id }.toSet()
        selected.clear()
        selected.addAll(ids.filter { it in visible })
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
        onSelectionChanged(selected.toSet())
    }

    fun clearSelection() {
        if (selected.isEmpty()) return
        val old = selected.toSet()
        selected.clear()
        rows.forEachIndexed { index, row -> if (row is Row.Media && row.record.id in old) notifyItemChanged(index, PAYLOAD_SELECTION) }
        onSelectionChanged(emptySet())
    }

    fun selectAllVisible() {
        rows.forEach { if (it is Row.Media) selected += it.record.id }
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
        onSelectionChanged(selected.toSet())
    }

    fun invertSelection() {
        val all = rows.mapNotNull { (it as? Row.Media)?.record?.id }.toSet()
        val next = all - selected
        selected.clear()
        selected.addAll(next)
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
        onSelectionChanged(selected.toSet())
    }

    fun startSelectionAt(adapterPosition: Int): Boolean {
        val media = mediaAtAdapterPosition(adapterPosition) ?: return false
        if (media.id !in selected) selected += media.id
        notifyItemChanged(adapterPosition, PAYLOAD_SELECTION)
        onSelectionChanged(selected.toSet())
        return true
    }

    fun applySelectionRange(startAdapterPosition: Int, endAdapterPosition: Int, baseSelection: Set<Long>) {
        val low = minOf(startAdapterPosition, endAdapterPosition).coerceAtLeast(0)
        val high = maxOf(startAdapterPosition, endAdapterPosition).coerceAtMost(rows.lastIndex)
        val rangeIds = buildSet {
            for (i in low..high) (rows[i] as? Row.Media)?.record?.id?.let(::add)
        }
        val next = LinkedHashSet<Long>().apply {
            addAll(baseSelection)
            addAll(rangeIds)
        }
        if (next == selected) return
        val changed = (selected union next) - (selected intersect next)
        selected.clear()
        selected.addAll(next)
        rows.forEachIndexed { index, row ->
            if (row is Row.Media && row.record.id in changed) notifyItemChanged(index, PAYLOAD_SELECTION)
        }
        onSelectionChanged(selected.toSet())
    }

    fun mediaAtAdapterPosition(position: Int): MediaRecord? = (rows.getOrNull(position) as? Row.Media)?.record

    fun mediaById(id: Long): MediaRecord? = rows.asSequence().mapNotNull { (it as? Row.Media)?.record }.firstOrNull { it.id == id }

    fun adapterPositionForMediaId(id: Long): Int = rows.indexOfFirst { it is Row.Media && it.record.id == id }

    fun allVisibleMedia(): List<MediaRecord> = rows.mapNotNull { (it as? Row.Media)?.record }

    fun isHeader(position: Int): Boolean = rows.getOrNull(position) is Row.Header

    override fun getItemId(position: Int): Long = when (val row = rows[position]) {
        is Row.Media -> row.record.id
        is Row.Header -> Long.MIN_VALUE + row.key.hashCode().toLong()
    }

    override fun getItemViewType(position: Int): Int = if (rows[position] is Row.Header) TYPE_HEADER else TYPE_MEDIA
    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemAlbumHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            MediaHolder(ItemAlbumBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderHolder -> holder.bind((rows[position] as Row.Header).title)
            is MediaHolder -> holder.bind((rows[position] as Row.Media).record)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) return super.onBindViewHolder(holder, position, payloads)
        val mediaHolder = holder as? MediaHolder ?: return
        val record = (rows.getOrNull(position) as? Row.Media)?.record ?: return
        payloads.forEach {
            when (it) {
                PAYLOAD_SELECTION -> mediaHolder.bindSelection(record.id in selected)
                PAYLOAD_STATE -> mediaHolder.bindState(record)
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        (holder as? MediaHolder)?.recycle()
        super.onViewRecycled(holder)
    }

    inner class MediaHolder(private val b: ItemAlbumBinding) : RecyclerView.ViewHolder(b.root) {
        private var specialAnimator: ObjectAnimator? = null
        init {
            b.root.setOnClickListener {
                val p = bindingAdapterPosition
                val record = mediaAtAdapterPosition(p) ?: return@setOnClickListener
                if (isSelectionMode()) toggleSelection(p, record) else onOpen(record)
            }
        }

        fun bind(item: MediaRecord) {
            if (cellSizePx > 0 && b.root.layoutParams.height != cellSizePx) {
                b.root.layoutParams = b.root.layoutParams.apply { height = cellSizePx }
            }
            loader.load(item, b.thumb, 560)
            b.duration.visibility = if (item.kind == MediaKind.VIDEO) View.VISIBLE else View.GONE
            b.duration.text = formatDuration(item.durationMs)
            bindState(item)
            b.currentBadge.visibility = if (item.id == highlightedMediaId) View.VISIBLE else View.GONE
            bindSelection(item.id in selected)
        }

        fun bindState(item: MediaRecord) {
            b.stateBadge.text = buildString {
                if (item.liked) append("♥")
                if (item.favorited) append("★")
            }
            b.stateBadge.visibility = if (b.stateBadge.text.isNullOrBlank()) View.GONE else View.VISIBLE
            b.currentBadge.visibility = if (item.id == highlightedMediaId) View.VISIBLE else View.GONE
            val tier = CardTier.forCount(item.likeCount)
            b.cardFrame.background = if (item.id == highlightedMediaId) {
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.TRANSPARENT)
                    cornerRadius = 5f * b.root.resources.displayMetrics.density
                    setStroke((3f * b.root.resources.displayMetrics.density).toInt(), 0xFFFF725E.toInt())
                }
            } else CardTier.frame(item.likeCount, b.root.resources.displayMetrics.density)
            b.tierBadge.text = tier.badge
            b.tierBadge.setTextColor(tier.color)
            b.tierBadge.visibility = if (tier.badge.isBlank()) View.GONE else View.VISIBLE
            b.problemBadge.visibility = if (item.id in problemIds) View.VISIBLE else View.GONE
            b.specialBadge.visibility = if (item.specialMark) View.VISIBLE else View.GONE
            specialAnimator?.cancel()
            specialAnimator = null
            b.specialBadge.rotation = 0f
            if (item.specialMark) {
                specialAnimator = ObjectAnimator.ofFloat(b.specialBadge, View.ROTATION, 0f, 360f).apply {
                    duration = 4200L
                    repeatCount = ObjectAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    start()
                }
            }
        }

        fun recycle() {
            specialAnimator?.cancel()
            specialAnimator = null
            b.specialBadge.rotation = 0f
            loader.clear(b.thumb)
        }

        fun bindSelection(value: Boolean) {
            b.selectionOverlay.visibility = if (value) View.VISIBLE else View.GONE
            b.selectionBadge.visibility = if (value) View.VISIBLE else View.GONE
        }
    }

    class HeaderHolder(private val b: ItemAlbumHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(title: String) { b.headerText.text = title }
    }

    private fun toggleSelection(position: Int, record: MediaRecord) {
        if (!selected.add(record.id)) selected.remove(record.id)
        notifyItemChanged(position, PAYLOAD_SELECTION)
        onSelectionChanged(selected.toSet())
    }

    private fun buildRows(list: List<MediaRecord>, grouping: TimeGrouping): List<Row> {
        if (grouping == TimeGrouping.NONE) return list.map { Row.Media(it) }
        val out = ArrayList<Row>(list.size + 32)
        var lastKey: String? = null
        list.forEach { record ->
            val time = when (grouping) {
                TimeGrouping.FILE_DAY -> record.modifiedAt.takeIf { it > 0 } ?: record.addedAt
                TimeGrouping.ADDED_DAY -> record.addedAt
                TimeGrouping.NONE -> 0L
            }
            val key = dayKey(time)
            if (key != lastKey) {
                out += Row.Header(key, dayTitle(time))
                lastKey = key
            }
            out += Row.Media(record)
        }
        return out
    }

    private fun dayKey(time: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(time))
    private fun dayTitle(time: Long): String {
        val now = System.currentTimeMillis()
        val today = dayKey(now)
        val yesterday = dayKey(now - 24L * 60 * 60 * 1000)
        return when (dayKey(time)) {
            today -> "今天"
            yesterday -> "昨天"
            else -> dayFormat.format(Date(time))
        }
    }

    private fun formatDuration(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val hour = total / 3600
        val min = (total % 3600) / 60
        val sec = total % 60
        return if (hour > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hour, min, sec)
        else String.format(Locale.ROOT, "%d:%02d", min, sec)
    }
}
