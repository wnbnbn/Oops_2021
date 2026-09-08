package com.localfeed.app.feed

import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.os.SystemClock
import android.graphics.Rect
import androidx.core.view.ViewCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import com.localfeed.app.core.MediaKind
import com.localfeed.app.core.MediaRecord
import com.localfeed.app.databinding.ItemFeedBinding
import com.localfeed.app.media.ThumbnailLoader
import com.localfeed.app.ui.FeedProgressView
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class FeedAdapter(
    private val thumbnailLoader: ThumbnailLoader,
    private val callbacks: Callbacks
) : RecyclerView.Adapter<FeedAdapter.Holder>() {

    interface Callbacks {
        fun onToggleLike(position: Int)
        fun onResetLike(position: Int)
        fun onLikeFromGesture(position: Int)
        fun onToggleFavorite(position: Int)
        fun onToggleFitMode(position: Int)
        fun onMore(position: Int)
        fun onFullscreen(position: Int)
        fun onSingleTap(position: Int)
        fun onLongPressStart(position: Int)
        fun onLongPressEnd(position: Int)
        fun onLongPressLock(position: Int)
        fun onLongPressUnlock(position: Int)
        fun onLockedSpeedCancel(position: Int)
        fun isLockedSpeed(position: Int): Boolean
        fun onSeekStart(position: Int, fraction: Float)
        fun onSeekMove(position: Int, fraction: Float)
        fun onSeekStop(position: Int, fraction: Float, canceled: Boolean)
        fun onVideoPageBound(position: Int, mediaId: Long, view: PlayerView)
        fun onVideoPageDetached(mediaId: Long, view: PlayerView)
    }

    companion object {
        private const val PAYLOAD_LIKE = "like"
        private const val PAYLOAD_FAVORITE = "favorite"
        private const val PAYLOAD_FIT = "fit"
    }

    private val items = mutableListOf<MediaRecord>()
    private val bound = ConcurrentHashMap<Long, WeakReference<Holder>>()
    private val playerViews = IdentityHashMap<PlayerView, WeakReference<Holder>>()

    var landscapeFeed: Boolean = false
        private set
    private var chromeVisible: Boolean = true
    private var bottomSafeInsetPx: Int = 0
    private var globalFillMode: Boolean = true
    private var actionRailBottomPx: Int = 0
    private var actionOpacity = 0.82f

    fun setActionRailBottom(value: Int) {
        actionRailBottomPx = value.coerceAtLeast(0)
        bound.values.forEach { it.get()?.applyActionRailPosition() }
    }

    fun setActionOpacity(value: Float) {
        actionOpacity = value.coerceIn(0.3f, 1f)
        bound.values.forEach { it.get()?.binding?.rightActions?.alpha = actionOpacity }
    }

    fun setGlobalFillMode(value: Boolean) {
        if (globalFillMode == value) return
        globalFillMode = value
        refreshBoundLayouts()
    }

    fun setBottomSafeInset(value: Int) {
        if (bottomSafeInsetPx == value) return
        bottomSafeInsetPx = value.coerceAtLeast(0)
        bound.values.forEach { it.get()?.applySafeInsets() }
    }

    fun setChromeVisible(value: Boolean) {
        if (chromeVisible == value) return
        chromeVisible = value
        bound.values.forEach { ref -> ref.get()?.applyChromeVisibility() }
    }

    fun isChromeVisible(): Boolean = chromeVisible

    fun setLandscapeFeed(value: Boolean) {
        if (landscapeFeed == value) return
        landscapeFeed = value
        refreshBoundLayouts()
    }

    fun submit(list: List<MediaRecord>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** Replace queue state without rebinding the active PlayerView when ids/order are unchanged. */
    fun syncQueue(list: List<MediaRecord>) {
        val sameShape = items.size == list.size && items.indices.all { items[it].id == list[it].id }
        if (!sameShape) {
            submit(list)
            return
        }
        items.clear()
        items.addAll(list)
        refreshBoundLayouts()
        bound.values.forEach { ref ->
            ref.get()?.let { holder ->
                val p = holder.bindingAdapterPosition
                if (p in items.indices) holder.updateStateOnly(items[p])
            }
        }
    }

    fun itemAt(position: Int): MediaRecord = items[position]

    fun append(list: List<MediaRecord>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    fun updateLike(position: Int, record: MediaRecord) {
        if (position !in items.indices) return
        items[position] = record
        notifyItemChanged(position, PAYLOAD_LIKE)
    }

    fun updateFavorite(position: Int, record: MediaRecord) {
        if (position !in items.indices) return
        items[position] = record
        notifyItemChanged(position, PAYLOAD_FAVORITE)
    }

    fun updateFitMode(position: Int, record: MediaRecord) {
        if (position !in items.indices) return
        items[position] = record
        notifyItemChanged(position, PAYLOAD_FIT)
    }

    fun removeAt(position: Int) {
        if (position !in items.indices) return
        bound.remove(items[position].id)
        items.removeAt(position)
        notifyItemRemoved(position)
    }

    fun updatePlaybackProgress(mediaId: Long, positionMs: Long, durationMs: Long) {
        val holder = bound[mediaId]?.get() ?: return
        holder.updateProgress(positionMs, durationMs)
    }

    fun updatePlayingState(mediaId: Long, isPlaying: Boolean) {
        val holder = bound[mediaId]?.get() ?: return
        holder.binding.pauseBadge.visibility = if (isPlaying) View.GONE else View.VISIBLE
    }

    fun showFirstFrame(mediaId: Long, view: PlayerView) {
        val holder = playerViews[view]?.get()?.takeIf { it.boundId == mediaId } ?: return
        holder.binding.let { binding ->
            binding.playerView.animate().cancel()
            binding.playerView.alpha = 1f
            binding.imageView.animate().cancel()
            binding.imageView.alpha = 1f
            if (holder.boundId == mediaId) binding.imageView.visibility = View.GONE
        }
    }

    fun showPlaybackError(mediaId: Long, message: String) {
        bound[mediaId]?.get()?.binding?.let { binding ->
            binding.playerView.alpha = 0f
            binding.imageView.visibility = View.VISIBLE
            binding.imageView.alpha = 1f
            binding.playbackErrorBadge.apply {
                text = "这个视频无法正常播放\n$message\n\n文件保留在当前页，可用右侧“更多”查看信息、外部打开或删除"
                visibility = View.VISIBLE
            }
        }
    }

    fun cancelTransientGestures() {
        bound.values.forEach { it.get()?.cancelTransientGesture(clearLockedIndicator = true) }
    }

    fun refreshBoundLayouts() {
        bound.values.forEach { ref ->
            val holder = ref.get() ?: return@forEach
            val p = holder.bindingAdapterPosition
            if (p in items.indices) holder.applyMediaLayout(items[p])
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(ItemFeedBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
        val item = items[position]
        if (item.kind == MediaKind.VIDEO) callbacks.onVideoPageBound(position, item.id, holder.binding.playerView)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val item = items[position]
        payloads.forEach { payload ->
            when (payload) {
                PAYLOAD_LIKE -> holder.updateLike(item)
                PAYLOAD_FAVORITE -> holder.updateFavorite(item)
                PAYLOAD_FIT -> {
                    holder.updateFitMode(item)
                    holder.applyMediaLayout(item)
                }
            }
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.cancelTransientGesture()
        thumbnailLoader.clear(holder.binding.imageView)
        playerViews.remove(holder.binding.playerView)
        holder.boundId?.let { id ->
            if (bound[id]?.get() === holder) bound.remove(id)
        }
        super.onViewRecycled(holder)
    }

    override fun onViewAttachedToWindow(holder: Holder) {
        super.onViewAttachedToWindow(holder)
        val position = holder.bindingAdapterPosition
        if (position in items.indices) {
            val item = items[position]
            if (item.kind == MediaKind.VIDEO) callbacks.onVideoPageBound(position, item.id, holder.binding.playerView)
        }
    }

    override fun onViewDetachedFromWindow(holder: Holder) {
        holder.boundId?.let { callbacks.onVideoPageDetached(it, holder.binding.playerView) }
        super.onViewDetachedFromWindow(holder)
    }

    inner class Holder(val binding: ItemFeedBinding) : RecyclerView.ViewHolder(binding.root) {
        var boundId: Long? = null
            private set
        private var longPressed = false
        private var durationMs: Long = 0L
        private var lastPositionMs: Long = 0L
        private var scrubbing = false
        private var longPressStartY = 0f
        private var longPressLocked = false
        private var lockedIndicatorVisible = false
        private var cancelLockedGesture = false
        private var lockCanceledThisGesture = false
        private var suppressSingleTapUntil = 0L

        private val detector = GestureDetector(binding.root.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (SystemClock.uptimeMillis() < suppressSingleTapUntil) return true
                val deadZone = 84f * binding.root.resources.displayMetrics.density
                if (e.y >= binding.pageRoot.height - deadZone) return true
                safePosition()?.let(callbacks::onSingleTap)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val p = safePosition() ?: return true
                suppressSingleTapUntil = SystemClock.uptimeMillis() + 520L
                animateLikeBurst()
                callbacks.onLikeFromGesture(p)
                return true
            }

            override fun onDoubleTapEvent(e: MotionEvent): Boolean {
                suppressSingleTapUntil = SystemClock.uptimeMillis() + 520L
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val p = safePosition() ?: return
                if (itemAt(p).kind == MediaKind.VIDEO && !scrubbing && !longPressed) {
                    longPressed = true
                    cancelLockedGesture = callbacks.isLockedSpeed(p)
                    lockCanceledThisGesture = false
                    longPressLocked = false
                    longPressStartY = e.y
                    binding.pageRoot.parent?.requestDisallowInterceptTouchEvent(true)
                    binding.speedBadge.text = if (cancelLockedGesture) "2.0× 已锁定 · 下滑取消" else "2.0× 快进中 · 下滑可锁定"
                    binding.speedBadge.visibility = View.VISIBLE
                    if (!cancelLockedGesture) callbacks.onLongPressStart(p)
                }
            }
        })

        init {
            binding.likeButton.setOnClickListener { safePosition()?.let(callbacks::onToggleLike) }
            binding.likeButton.setOnLongClickListener { safePosition()?.let(callbacks::onResetLike); true }
            binding.favoriteButton.setOnClickListener { safePosition()?.let(callbacks::onToggleFavorite) }
            binding.fitModeButton.setOnClickListener { safePosition()?.let(callbacks::onToggleFitMode) }
            binding.moreButton.setOnClickListener { safePosition()?.let(callbacks::onMore) }
            binding.fullscreenButton.setOnClickListener { safePosition()?.let(callbacks::onFullscreen) }
            binding.speedBadge.setOnClickListener {
                if (!lockedIndicatorVisible) return@setOnClickListener
                lockedIndicatorVisible = false
                binding.speedBadge.visibility = View.GONE
                safePosition()?.let(callbacks::onLockedSpeedCancel)
            }

            binding.progress.listener = object : FeedProgressView.Listener {
                override fun onScrubStart(fraction: Float) {
                    val p = safePosition() ?: return
                    cancelLongPressIfNeeded()
                    scrubbing = true
                    showTimePreview(fraction)
                    callbacks.onSeekStart(p, fraction)
                }

                override fun onScrubMove(fraction: Float) {
                    val p = safePosition() ?: return
                    showTimePreview(fraction)
                    callbacks.onSeekMove(p, fraction)
                }

                override fun onScrubStop(fraction: Float, canceled: Boolean) {
                    val p = safePosition() ?: return
                    showTimePreview(fraction)
                    callbacks.onSeekStop(p, fraction, canceled)
                    scrubbing = false
                    binding.timePreview.postDelayed({
                        if (!scrubbing) binding.timePreview.visibility = View.GONE
                    }, 500L)
                }
            }

            binding.pageRoot.setOnTouchListener { _, event ->
                // Returning true is important: this page owns tap/hold until ViewPager2 deliberately
                // intercepts a vertical swipe. If it intercepts, ACTION_CANCEL restores temporary 2x.
                detector.onTouchEvent(event)
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        val dy = event.y - longPressStartY
                        val density = binding.root.resources.displayMetrics.density
                        if (longPressed && cancelLockedGesture && dy > density * 88f) {
                            val p = safePosition()
                            if (p != null) {
                                cancelLockedGesture = false
                                lockCanceledThisGesture = true
                                lockedIndicatorVisible = false
                                binding.speedBadge.text = "2.0× 已取消"
                                binding.speedBadge.postDelayed({ if (!lockedIndicatorVisible) binding.speedBadge.visibility = View.GONE }, 500L)
                                // Keep the pager locked until this finger is lifted. Releasing here
                                // leaks the remaining downward motion into ViewPager2.
                                callbacks.onLockedSpeedCancel(p)
                            }
                        } else if (longPressed && !lockCanceledThisGesture && !longPressLocked && dy > density * 88f) {
                            val p = safePosition()
                            if (p != null) {
                                longPressLocked = true
                                binding.speedBadge.text = "2.0× 已锁定 · 上滑取消"
                                callbacks.onLongPressLock(p)
                            }
                        } else if (longPressed && longPressLocked && dy < density * 44f) {
                            val p = safePosition()
                            if (p != null) {
                                longPressLocked = false
                                binding.speedBadge.text = "2.0× 快进中 · 下滑可锁定"
                                callbacks.onLongPressUnlock(p)
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelLongPressIfNeeded()
                }
                true
            }
        }

        private fun safePosition(): Int? = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }

        fun bind(item: MediaRecord) {
            cancelTransientGesture()
            binding.imageView.animate().cancel()
            binding.imageView.alpha = 1f
            boundId?.let { old -> if (bound[old]?.get() === this) bound.remove(old) }
            boundId = item.id
            bound[item.id] = WeakReference(this)
            playerViews[binding.playerView] = WeakReference(this)
            durationMs = item.durationMs
            lastPositionMs = 0L
            scrubbing = false

            updateStateOnly(item)
            binding.progress.progressFraction = 0f
            binding.progress.visibility = if (item.kind == MediaKind.VIDEO) View.VISIBLE else View.GONE
            binding.speedBadge.visibility = View.GONE
            binding.pauseBadge.visibility = View.GONE
            binding.playbackErrorBadge.visibility = View.GONE
            binding.timePreview.visibility = View.GONE
            binding.likeBurst.visibility = View.GONE

            if (item.kind == MediaKind.IMAGE) {
                binding.imageView.resetZoom()
                binding.playerView.visibility = View.GONE
                binding.playerView.alpha = 0f
                binding.imageView.visibility = View.VISIBLE
                binding.imageView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                binding.imageView.layoutParams = fullFrameParams()
                thumbnailLoader.load(item, binding.imageView, 1440)
                binding.fullscreenButton.visibility = View.GONE
                applyChromeVisibility()
                return
            }

            // Adjacent ViewPager pages show a real frame while following the finger. The poster is
            // underneath the transparent PlayerView and disappears only after the first decoded
            // frame, avoiding the black gap that appeared during slow drags.
            binding.imageView.resetZoom()
            binding.imageView.visibility = View.VISIBLE
            thumbnailLoader.load(item, binding.imageView, 1080)
            binding.playerView.visibility = View.VISIBLE
            binding.playerView.alpha = 1f
            applySafeInsets()
            applyMediaLayout(item)
            applyChromeVisibility()
            applyActionRailPosition()
        }

        fun updateStateOnly(item: MediaRecord) {
            updateLike(item)
            updateFavorite(item)
            updateFitMode(item)
        }

        fun updateLike(item: MediaRecord) {
            binding.likeIcon.setImageResource(if (item.liked) com.localfeed.app.R.drawable.ic_heart_filled else com.localfeed.app.R.drawable.ic_heart_outline)
            binding.likeCount.text = compactCount(item.likeCount)
            binding.likeCount.visibility = if (item.likeCount > 0) View.VISIBLE else View.GONE
        }

        private fun compactCount(count: Int): String = when {
            count < 1000 -> count.toString()
            count < 10_000 -> String.format(java.util.Locale.US, "%.1fk", count / 1000f).replace(".0k", "k")
            else -> "9999+"
        }

        fun updateFavorite(item: MediaRecord) {
            binding.favoriteIcon.setImageResource(if (item.favorited) com.localfeed.app.R.drawable.ic_star_filled else com.localfeed.app.R.drawable.ic_star_outline)
        }

        fun updateFitMode(item: MediaRecord) {
            val landscape = item.isLandscape()
            val fill = if (!landscapeFeed && landscape) false else preferredFill(item)
            binding.fitModeIcon.setImageResource(
                if (fill) com.localfeed.app.R.drawable.ic_fit_fill else com.localfeed.app.R.drawable.ic_fit_complete
            )
            binding.fitModeButton.contentDescription = if (fill) "当前铺满，点击完整显示" else "当前完整显示，点击铺满"
            // A landscape clip shown inside the portrait feed is always fitted. Its fullscreen
            // button is the meaningful way to change presentation, so hide a no-op fit toggle.
            binding.fitModeButton.visibility = if (item.kind == MediaKind.VIDEO && (landscapeFeed || !landscape)) View.VISIBLE else View.GONE
        }

        private fun preferredFill(item: MediaRecord): Boolean = when (item.fitMode) {
            1 -> true
            2 -> false
            else -> globalFillMode
        }

        fun applyMediaLayout(item: MediaRecord) {
            if (item.kind != MediaKind.VIDEO) return
            updateFitMode(item)
            val landscape = item.isLandscape()
            val fill = if (!landscapeFeed && landscape) false else preferredFill(item)
            binding.fullscreenButton.visibility = if (!landscapeFeed && landscape) View.VISIBLE else View.GONE

            if (!landscapeFeed && landscape && !fill && item.aspectRatio() > 0f) {
                binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                binding.imageView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                val screenW = binding.root.resources.displayMetrics.widthPixels
                val screenH = binding.root.resources.displayMetrics.heightPixels
                val h = (screenW / item.aspectRatio()).roundToInt().coerceAtLeast(1)
                val top = ((screenH - h) * 0.38f).roundToInt().coerceAtLeast(0)
                binding.playerView.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { topMargin = top }
                binding.imageView.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { topMargin = top }
                binding.fullscreenButton.translationY = (top + h + 10).toFloat()
            } else {
                // Landscape fullscreen is still the same mixed video feed. Portrait clips are fitted
                // with side bars rather than being removed from the queue.
                binding.playerView.resizeMode = if (!fill) {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
                binding.playerView.layoutParams = fullFrameParams()
                binding.imageView.layoutParams = fullFrameParams()
                binding.imageView.scaleType = if (!fill) android.widget.ImageView.ScaleType.FIT_CENTER else android.widget.ImageView.ScaleType.CENTER_CROP
                binding.fullscreenButton.translationY = 0f
            }
        }

        fun applySafeInsets() {
            (binding.progress.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.bottomMargin != bottomSafeInsetPx) {
                    lp.bottomMargin = bottomSafeInsetPx
                    binding.progress.layoutParams = lp
                }
            }
            binding.progress.post {
                ViewCompat.setSystemGestureExclusionRects(
                    binding.progress,
                    listOf(Rect(0, 0, binding.progress.width, binding.progress.height))
                )
            }
        }

        fun applyActionRailPosition() {
            binding.rightActions.alpha = actionOpacity
            if (actionRailBottomPx <= 0) return
            (binding.rightActions.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                if (lp.bottomMargin != actionRailBottomPx) {
                    lp.bottomMargin = actionRailBottomPx
                    binding.rightActions.layoutParams = lp
                }
            }
        }

        fun applyChromeVisibility() {
            binding.rightActions.visibility = if (chromeVisible && !landscapeFeed) View.VISIBLE else View.GONE
            binding.progress.visibility = if (chromeVisible && safePosition()?.let { itemAt(it).kind == MediaKind.VIDEO } == true) View.VISIBLE else View.GONE
            if (!chromeVisible) binding.fullscreenButton.visibility = View.GONE
            else safePosition()?.let { p -> applyMediaLayout(itemAt(p)) }
        }

        fun updateProgress(positionMs: Long, actualDurationMs: Long) {
            lastPositionMs = positionMs
            if (actualDurationMs > 0) durationMs = actualDurationMs
            if (!scrubbing && durationMs > 0) {
                binding.progress.progressFraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            }
        }

        fun cancelTransientGesture(clearLockedIndicator: Boolean = false) {
            cancelLongPressIfNeeded()
            if (clearLockedIndicator) {
                lockedIndicatorVisible = false
                binding.speedBadge.visibility = View.GONE
            }
            if (scrubbing) {
                scrubbing = false
                binding.timePreview.visibility = View.GONE
            }
        }

        private fun cancelLongPressIfNeeded() {
            if (!longPressed) return
            longPressed = false
            binding.pageRoot.parent?.requestDisallowInterceptTouchEvent(false)
            if (cancelLockedGesture) {
                cancelLockedGesture = false
                if (callbacks.isLockedSpeed(safePosition() ?: -1)) {
                    lockedIndicatorVisible = true
                    binding.speedBadge.text = "2.0× 已锁定 · 点击或长按下滑取消"
                    binding.speedBadge.visibility = View.VISIBLE
                } else {
                    lockedIndicatorVisible = false
                    binding.speedBadge.visibility = View.GONE
                }
                return
            }
            if (lockCanceledThisGesture) {
                lockCanceledThisGesture = false
                lockedIndicatorVisible = false
                binding.speedBadge.visibility = View.GONE
                return
            }
            if (!longPressLocked) {
                binding.speedBadge.visibility = View.GONE
                callbacks.onLongPressEnd(safePosition() ?: -1)
            } else {
                lockedIndicatorVisible = true
                binding.speedBadge.text = "2.0× 已锁定 · 点击取消"
                binding.speedBadge.visibility = View.VISIBLE
            }
            longPressLocked = false
        }

        private fun showTimePreview(fraction: Float) {
            val total = durationMs.coerceAtLeast(0L)
            val at = if (total > 0) (total * fraction).toLong() else lastPositionMs
            binding.timePreview.text = "${formatTime(at)} / ${formatTime(total)}"
            binding.timePreview.visibility = View.VISIBLE
        }

        private fun animateLikeBurst() {
            binding.likeBurst.apply {
                visibility = View.VISIBLE
                alpha = 0f
                scaleX = 0.55f
                scaleY = 0.55f
                animate().cancel()
                animate().alpha(1f).scaleX(1.12f).scaleY(1.12f).setDuration(110L).withEndAction {
                    animate().alpha(0f).scaleX(1.35f).scaleY(1.35f).setDuration(180L).withEndAction {
                        visibility = View.GONE
                        alpha = 1f
                        scaleX = 1f
                        scaleY = 1f
                    }.start()
                }.start()
            }
        }

        private fun fullFrameParams() = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        private fun formatTime(ms: Long): String {
            val totalSeconds = ms.coerceAtLeast(0L) / 1000L
            val h = totalSeconds / 3600L
            val m = (totalSeconds % 3600L) / 60L
            val s = totalSeconds % 60L
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        }
    }
}
