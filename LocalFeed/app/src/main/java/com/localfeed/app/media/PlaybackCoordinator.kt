package com.localfeed.app.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.ui.PlayerView
import com.localfeed.app.core.MediaKind
import com.localfeed.app.core.MediaRecord
import kotlin.math.abs

@androidx.media3.common.util.UnstableApi
class PlaybackCoordinator(context: Context) {

    interface Listener {
        fun onProgress(mediaId: Long, positionMs: Long, durationMs: Long)
        fun onPlayingChanged(mediaId: Long, isPlaying: Boolean)
        fun onPlaybackError(mediaId: Long, message: String)
        fun onPlaybackEnded(mediaId: Long)
    }

    private class TargetControl : TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {
        var current = 0
        override fun getTargetPreloadStatus(index: Int): DefaultPreloadManager.PreloadStatus {
            val distance = abs(index - current)
            return when {
                distance == 1 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(3_000L)
                distance == 2 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
                distance <= 4 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED
                else -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
            }
        }
    }

    private val target = TargetControl()
    private val builder = DefaultPreloadManager.Builder(context, target)
    private val preloadManager = builder.build()
    val player: ExoPlayer = builder.buildExoPlayer().apply {
        repeatMode = Player.REPEAT_MODE_ONE
        playWhenReady = true
    }

    var listener: Listener? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentView: PlayerView? = null
    private var currentPoster: View? = null
    private var currentRecordId: Long = -1L
    private var currentFeedIndex = 0
    private var awaitingFirstFrameMediaId: Long? = null
    private val mapped = HashMap<Long, MediaItem>()
    private var released = false

    /** User-selected persistent speed. Temporary hold-to-2x never mutates this value. */
    private var userSpeed = 1f
    private var temporary2x = false
    private var locked2x = false

    private val progressTick = object : Runnable {
        override fun run() {
            if (released) return
            if (currentRecordId >= 0 && player.playbackState != Player.STATE_IDLE) {
                val duration = player.duration.takeIf { it > 0 } ?: 0L
                val position = player.currentPosition.coerceAtLeast(0L)
                listener?.onProgress(currentRecordId, position, duration)
            }
            mainHandler.postDelayed(this, if (player.isPlaying) 100L else 250L)
        }
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                // switchTargetView() can emit a first-frame callback for the *previous* media while
                // the new page is already waiting behind its poster. Hiding that new poster on the
                // stale callback creates a very visible black/old-frame flash on page changes.
                val expectedId = awaitingFirstFrameMediaId ?: return
                if (player.currentMediaItem?.mediaId != expectedId.toString()) return
                val poster = currentPoster ?: return
                awaitingFirstFrameMediaId = null
                poster.animate().cancel()
                // SurfaceView + alpha cross-fade is visibly unstable on some ColorOS devices.
                // The poster is an exact transition shield; drop it only after the correct frame.
                poster.alpha = 1f
                poster.visibility = View.GONE
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (currentRecordId >= 0) listener?.onPlayingChanged(currentRecordId, isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && currentRecordId >= 0) listener?.onPlaybackEnded(currentRecordId)
            }

            override fun onPlayerError(error: PlaybackException) {
                val id = currentRecordId
                if (id >= 0) listener?.onPlaybackError(id, error.errorCodeName)
            }
        })
        mainHandler.post(progressTick)
    }

    fun updateQueue(queue: List<MediaRecord>) {
        preloadManager.reset()
        mapped.clear()
        val items = mutableListOf<MediaItem>()
        val ranking = mutableListOf<Int>()
        queue.forEachIndexed { index, record ->
            if (record.kind == MediaKind.VIDEO) {
                val item = mediaItem(record)
                mapped[record.id] = item
                items += item
                ranking += index
            }
        }
        if (items.isNotEmpty()) preloadManager.addMediaItems(items, ranking)
        preloadManager.setCurrentPlayingIndex(currentFeedIndex)
        preloadManager.invalidate()
    }

    /**
     * One player is reused for all pages. Portrait/landscape layout changes keep the same player,
     * MediaItem and position. A new item keeps its poster until onRenderedFirstFrame().
     */
    fun play(record: MediaRecord, feedIndex: Int, view: PlayerView, poster: View?) {
        if (record.kind != MediaKind.VIDEO) {
            pauseAndDetach()
            return
        }

        val changingMedia = currentRecordId != record.id || player.currentMediaItem?.mediaId != record.id.toString()
        val switchingView = currentView !== view
        if (changingMedia) cancelTemporaryBoost()

        // Arm the poster before switching Surface targets. This is important because the target
        // switch itself may render one last frame from the old MediaItem.
        currentPoster = poster
        if (changingMedia || switchingView) {
            awaitingFirstFrameMediaId = record.id
            poster?.apply {
                animate().cancel()
                alpha = 1f
                visibility = View.VISIBLE
            }
        }
        attachTo(view)
        currentFeedIndex = feedIndex
        target.current = feedIndex
        preloadManager.setCurrentPlayingIndex(feedIndex)
        preloadManager.invalidate()

        currentRecordId = record.id
        if (changingMedia) {
            val item = mapped[record.id] ?: mediaItem(record)
            val source = preloadManager.getMediaSource(item)
            if (source != null) player.setMediaSource(source) else player.setMediaItem(item)
            player.prepare()
            applyEffectiveSpeed()
        }
        player.play()
    }

    fun isCurrent(mediaId: Long): Boolean = mediaId == currentRecordId

    fun togglePause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun setUserSpeed(value: Float) {
        userSpeed = value.coerceIn(0.25f, 4f)
        locked2x = false
        if (!temporary2x) applyEffectiveSpeed()
    }

    fun userSpeed(): Float = userSpeed

    fun beginTemporary2x() {
        if (currentRecordId < 0) return
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

    fun clearLocked2x() {
        if (!locked2x) return
        locked2x = false
        applyEffectiveSpeed()
    }

    fun isTemporary2x(): Boolean = temporary2x
    fun isLocked2x(): Boolean = locked2x

    fun setSingleLoop(enabled: Boolean) {
        player.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun seekToFraction(fraction: Float) {
        val duration = player.duration
        if (duration > 0) player.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun seekTo(positionMs: Long) {
        val duration = player.duration
        if (duration > 0) player.seekTo(positionMs.coerceIn(0L, duration))
    }

    fun currentPosition(): Long = player.currentPosition.coerceAtLeast(0L)
    fun duration(): Long = player.duration.takeIf { it > 0 } ?: 0L

    fun pauseAndDetach() {
        cancelTemporaryBoost()
        clearLocked2x()
        player.pause()
        currentView?.player = null
        currentView = null
        currentPoster = null
        awaitingFirstFrameMediaId = null
    }

    fun pauseOnly() {
        cancelTemporaryBoost()
        player.pause()
    }

    fun detachIfCurrent(view: PlayerView) {
        if (currentView !== view) return
        view.player = null
        currentView = null
        currentPoster = null
        awaitingFirstFrameMediaId = null
    }

    fun release() {
        if (released) return
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        currentView?.player = null
        currentView = null
        player.release()
        preloadManager.release()
    }

    private fun applyEffectiveSpeed() {
        player.setPlaybackSpeed(if (temporary2x || locked2x) 2f else userSpeed)
    }

    private fun attachTo(view: PlayerView) {
        if (currentView === view) return
        val old = currentView
        if (old == null) view.player = player
        else PlayerView.switchTargetView(player, old, view)
        currentView = view
    }

    private fun mediaItem(record: MediaRecord): MediaItem = MediaItem.Builder()
        .setMediaId(record.id.toString())
        .setUri(Uri.parse(record.uri))
        .build()
}
