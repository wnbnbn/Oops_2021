package com.localfeed.app.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.PlayerPool
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.ui.PlayerView
import com.localfeed.app.core.MediaKind
import com.localfeed.app.core.MediaRecord
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Sliding-window playback for the vertical feed.
 *
 * A PlayerView keeps its own prepared player while it is attached. The selected page therefore
 * starts by calling play() on an already prepared decoder instead of moving one Surface and cold
 * preparing a single player after every swipe.
 */
@androidx.media3.common.util.UnstableApi
class PlaybackCoordinator(context: Context) {

    interface Listener {
        fun onProgress(mediaId: Long, positionMs: Long, durationMs: Long)
        fun onPlayingChanged(mediaId: Long, isPlaying: Boolean)
        fun onFirstFrame(mediaId: Long, view: PlayerView)
        fun onPlaybackError(mediaId: Long, message: String)
        fun onPlaybackEnded(mediaId: Long)
    }

    private class TargetControl : TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {
        var current = C.INDEX_UNSET

        override fun getTargetPreloadStatus(index: Int): DefaultPreloadManager.PreloadStatus {
            return when (abs(index - current)) {
                0, 1 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(1_500L)
                2 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
                3 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED
                else -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
            }
        }
    }

    private data class Slot(
        val mediaId: Long,
        var feedIndex: Int,
        val view: PlayerView,
        val player: ExoPlayer,
        val restartOnReentry: Boolean,
        var renderedFirstFrame: Boolean = false
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val target = TargetControl()
    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 3_000,
            /* maxBufferMs = */ 15_000,
            /* bufferForPlaybackMs = */ 250,
            /* bufferForPlaybackAfterRebufferMs = */ 500
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
    private val preloadBuilder = DefaultPreloadManager.Builder(appContext, target)
        .setLoadControl(loadControl)
    private val preloadManager = preloadBuilder.build()
    private val playerPool = PlayerPool(poolCapacity = 3) { createPlayer() }

    private val slots = IdentityHashMap<PlayerView, Slot>()
    private val pending = IdentityHashMap<PlayerView, Job>()
    private val requestVersions = IdentityHashMap<PlayerView, Int>()
    private var queue: List<MediaRecord> = emptyList()
    // Queue positions are part of the key because the random feed may contain the same file more
    // than once. Two attached pages must never share one PreloadMediaSource instance.
    private val mediaItems = HashMap<Int, MediaItem>()
    private val managedItems = HashMap<Int, MediaItem>()
    private var activeSlot: Slot? = null
    private var desiredMediaId = -1L
    private var desiredFeedIndex = 0
    private var desiredView: PlayerView? = null
    private var released = false
    private var singleLoop = true

    var listener: Listener? = null

    /** User-selected persistent speed. Temporary hold-to-2x never mutates this value. */
    private var userSpeed = 1f
    private var temporary2x = false
    private var locked2x = false

    private val progressTick = object : Runnable {
        override fun run() {
            if (released) return
            activeSlot?.let { slot ->
                val duration = slot.player.duration.takeIf { it > 0 } ?: 0L
                val position = slot.player.currentPosition.coerceAtLeast(0L)
                listener?.onProgress(slot.mediaId, position, duration)
            }
            mainHandler.postDelayed(this, if (activeSlot?.player?.isPlaying == true) 100L else 250L)
        }
    }

    init {
        mainHandler.post(progressTick)
    }

    fun updateQueue(newQueue: List<MediaRecord>) {
        queue = newQueue.filter { it.kind == MediaKind.VIDEO }
        val validIndexes = queue.indices.toSet()
        val removed = managedItems.keys.filter { index ->
            index !in validIndexes || managedItems[index]?.mediaId != pageMediaId(queue[index], index)
        }
        if (removed.isNotEmpty()) {
            preloadManager.removeMediaItems(removed.mapNotNull(managedItems::remove))
        }
        mediaItems.keys.retainAll(validIndexes)
        queue.forEachIndexed { index, record ->
            if (mediaItems[index]?.mediaId != pageMediaId(record, index)) mediaItems[index] = mediaItem(record, index)
        }
        refreshManagedWindow(desiredFeedIndex)
    }

    /** Prepare an attached current/adjacent page without starting its audio or playback. */
    fun preparePage(
        record: MediaRecord,
        feedIndex: Int,
        view: PlayerView,
        resumePositionMs: Long = 0L,
        restartOnReentry: Boolean = true
    ) {
        if (released || record.kind != MediaKind.VIDEO) return
        slots[view]?.let { slot ->
            if (slot.mediaId == record.id) {
                slot.feedIndex = feedIndex
                if (slot.renderedFirstFrame) listener?.onFirstFrame(record.id, view)
                return
            }
            releaseSlot(slot)
        }

        val version = (requestVersions[view] ?: 0) + 1
        requestVersions[view] = version
        pending.remove(view)?.cancel()
        ensureManaged(record, feedIndex)
        pending[view] = scope.launch {
            val player = playerPool.acquire()
            if (released || requestVersions[view] != version) {
                playerPool.yield(player)
                return@launch
            }

            val item = mediaItems[feedIndex] ?: mediaItem(record, feedIndex).also { mediaItems[feedIndex] = it }
            val source = preloadManager.getMediaSource(item)
            val slot = Slot(record.id, feedIndex, view, player, restartOnReentry)
            slots[view] = slot
            pending.remove(view)

            player.playWhenReady = false
            player.volume = 0f
            player.repeatMode = if (singleLoop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            player.playbackParameters = PlaybackParameters(1f, 1f)
            if (source != null) player.setMediaSource(source) else player.setMediaItem(item)
            if (resumePositionMs > 0L && (record.durationMs <= 0L || resumePositionMs < record.durationMs - 2_000L)) {
                player.seekTo(resumePositionMs)
            }
            view.player = player
            player.prepare()

            if (desiredMediaId == record.id && desiredView === view) activate(slot)
        }
    }

    /** Select a page. If its attached player is ready, this performs no prepare or Surface move. */
    fun play(
        record: MediaRecord,
        feedIndex: Int,
        view: PlayerView,
        resumePositionMs: Long = 0L,
        restartOnReentry: Boolean = true
    ) {
        if (record.kind != MediaKind.VIDEO) {
            pauseAndDetach()
            return
        }
        desiredMediaId = record.id
        desiredFeedIndex = feedIndex
        desiredView = view
        target.current = feedIndex
        preloadManager.setCurrentPlayingIndex(feedIndex)
        refreshManagedWindow(feedIndex)

        cancelTemporaryBoost()
        slots[view]?.takeIf { it.mediaId == record.id }?.let(::activate)
            ?: preparePage(record, feedIndex, view, resumePositionMs, restartOnReentry)
    }

    fun releasePage(mediaId: Long, view: PlayerView) {
        requestVersions[view] = (requestVersions[view] ?: 0) + 1
        pending.remove(view)?.cancel()
        slots[view]?.takeIf { it.mediaId == mediaId }?.let(::releaseSlot)
    }

    fun isCurrent(mediaId: Long): Boolean = mediaId == desiredMediaId
    fun isPlaying(): Boolean = activeSlot?.player?.isPlaying == true
    fun resume() { activeSlot?.player?.play() }

    fun togglePause() {
        activeSlot?.player?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun setUserSpeed(value: Float) {
        userSpeed = value.coerceIn(0.25f, 4f)
        locked2x = false
        if (!temporary2x) applyEffectiveSpeed()
    }

    fun userSpeed(): Float = userSpeed

    fun beginTemporary2x() {
        if (activeSlot == null) return
        temporary2x = true
        applyEffectiveSpeed()
    }

    fun endTemporary2x() {
        if (!temporary2x) return
        temporary2x = false
        applyEffectiveSpeed()
    }

    fun cancelTemporaryBoost() {
        if (!temporary2x) return
        temporary2x = false
        applyEffectiveSpeed()
    }

    fun lock2x() {
        temporary2x = false
        locked2x = true
        applyEffectiveSpeed()
    }

    fun unlock2xToTemporary() {
        if (!locked2x) return
        locked2x = false
        temporary2x = true
        applyEffectiveSpeed()
    }

    fun clearLocked2x() {
        if (!locked2x) return
        locked2x = false
        applyEffectiveSpeed()
    }

    fun isTemporary2x(): Boolean = temporary2x
    fun isLocked2x(): Boolean = locked2x

    fun setSingleLoop(enabled: Boolean) {
        singleLoop = enabled
        playerPool.executeForAll {
            repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }
    }

    fun seekToFraction(fraction: Float) {
        val player = activeSlot?.player ?: return
        val duration = player.duration
        if (duration > 0) player.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun seekTo(positionMs: Long) {
        val player = activeSlot?.player ?: return
        val duration = player.duration
        if (duration > 0) player.seekTo(positionMs.coerceIn(0L, duration))
    }

    fun currentPosition(): Long = activeSlot?.player?.currentPosition?.coerceAtLeast(0L) ?: 0L
    fun duration(): Long = activeSlot?.player?.duration?.takeIf { it > 0 } ?: 0L

    fun pauseAndDetach() {
        cancelTemporaryBoost()
        clearLocked2x()
        desiredMediaId = -1L
        desiredView = null
        activeSlot = null
        pending.values.forEach(Job::cancel)
        pending.clear()
        slots.values.toList().forEach(::releaseSlot)
    }

    fun pauseOnly() {
        cancelTemporaryBoost()
        activeSlot?.player?.pause()
    }

    fun release() {
        if (released) return
        pauseAndDetach()
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        preloadManager.release()
        playerPool.release()
    }

    private fun createPlayer(): ExoPlayer {
        val player = preloadBuilder.buildExoPlayer().apply {
            repeatMode = if (singleLoop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            playWhenReady = false
            volume = 0f
            setForegroundMode(true)
        }
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                val slot = slots.values.firstOrNull { it.player === player } ?: return
                slot.renderedFirstFrame = true
                listener?.onFirstFrame(slot.mediaId, slot.view)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val slot = slots.values.firstOrNull { it.player === player } ?: return
                if (activeSlot === slot) listener?.onPlayingChanged(slot.mediaId, isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val slot = slots.values.firstOrNull { it.player === player } ?: return
                if (activeSlot === slot && playbackState == Player.STATE_ENDED) {
                    listener?.onPlaybackEnded(slot.mediaId)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val slot = slots.values.firstOrNull { it.player === player } ?: return
                if (activeSlot === slot) listener?.onPlaybackError(slot.mediaId, error.errorCodeName)
            }
        })
        return player
    }

    private fun activate(slot: Slot) {
        if (desiredMediaId != slot.mediaId || desiredView !== slot.view) return
        activeSlot?.takeIf { it !== slot }?.player?.apply {
            pause()
            volume = 0f
            playbackParameters = PlaybackParameters(1f, 1f)
            if (activeSlot?.restartOnReentry == true) seekTo(0L)
        }
        activeSlot = slot
        slot.player.volume = 1f
        applyEffectiveSpeed()
        if (slot.renderedFirstFrame) listener?.onFirstFrame(slot.mediaId, slot.view)
        slot.player.play()
    }

    private fun releaseSlot(slot: Slot) {
        if (slots[slot.view] !== slot) return
        slots.remove(slot.view)
        if (activeSlot === slot) activeSlot = null
        slot.view.player = null
        playerPool.yield(slot.player)
    }

    private fun applyEffectiveSpeed() {
        val speed = if (temporary2x || locked2x) 2f else userSpeed
        activeSlot?.player?.playbackParameters = PlaybackParameters(speed, 1f)
    }

    private fun refreshManagedWindow(center: Int) {
        if (queue.isEmpty()) return
        val from = (center - 3).coerceAtLeast(0)
        val to = (center + 3).coerceAtMost(queue.lastIndex)
        val desiredIndexes = if (from <= to) (from..to).toSet() else emptySet()
        val removed = managedItems.keys.filter { it !in desiredIndexes }
        if (removed.isNotEmpty()) preloadManager.removeMediaItems(removed.mapNotNull(managedItems::remove))

        val addIndexes = desiredIndexes.filter { it !in managedItems }.sorted()
        if (addIndexes.isNotEmpty()) {
            val items = addIndexes.map { index ->
                mediaItems[index] ?: mediaItem(queue[index], index).also { item -> mediaItems[index] = item }
            }
            val rankings = addIndexes
            preloadManager.addMediaItems(items, rankings)
            addIndexes.forEachIndexed { itemIndex, queueIndex -> managedItems[queueIndex] = items[itemIndex] }
        }
    }

    private fun ensureManaged(record: MediaRecord, feedIndex: Int) {
        if (managedItems[feedIndex]?.mediaId == pageMediaId(record, feedIndex)) return
        managedItems.remove(feedIndex)?.let { preloadManager.removeMediaItems(listOf(it)) }
        val item = mediaItems[feedIndex] ?: mediaItem(record, feedIndex).also { mediaItems[feedIndex] = it }
        preloadManager.add(item, feedIndex)
        managedItems[feedIndex] = item
    }

    private fun pageMediaId(record: MediaRecord, feedIndex: Int): String = "${record.id}@$feedIndex"

    private fun mediaItem(record: MediaRecord, feedIndex: Int): MediaItem = MediaItem.Builder()
        .setMediaId(pageMediaId(record, feedIndex))
        .setUri(Uri.parse(record.uri))
        .build()
}
