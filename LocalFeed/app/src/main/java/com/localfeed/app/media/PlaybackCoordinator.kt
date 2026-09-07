package com.localfeed.app.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.localfeed.app.core.MediaKind
import com.localfeed.app.core.MediaRecord

@androidx.media3.common.util.UnstableApi
class PlaybackCoordinator(context: Context) {

    interface Listener {
        fun onProgress(mediaId: Long, positionMs: Long, durationMs: Long)
        fun onPlayingChanged(mediaId: Long, isPlaying: Boolean)
        fun onFirstFrame(mediaId: Long)
        fun onPlaybackError(mediaId: Long, message: String)
        fun onPlaybackEnded(mediaId: Long)
    }

    // Direct MediaItems follow the stable v0.1/v0.4 path. Preloaded SAF sources could outlive a
    // background deletion and be released while the active page still referenced them.
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
        playWhenReady = true
    }

    var listener: Listener? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentView: PlayerView? = null
    private var currentRecordId: Long = -1L
    private var awaitingFirstFrameId: Long = -1L
    private var currentFeedIndex = 0
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

            override fun onRenderedFirstFrame() {
                val id = currentRecordId
                // Some codecs render their first frame before STATE_READY, while others report
                // READY first. Waiting for that ordering left the video playing behind its poster
                // until the user sought. The requested media id is the stable ownership check.
                if (id >= 0 && awaitingFirstFrameId == id && player.currentMediaItem?.mediaId == id.toString()) {
                    awaitingFirstFrameId = -1L
                    listener?.onFirstFrame(id)
                }
            }
        })
        mainHandler.post(progressTick)
    }

    fun updateQueue(queue: List<MediaRecord>) {
        val validIds = queue.asSequence().filter { it.kind == MediaKind.VIDEO }.map { it.id }.toHashSet()
        mapped.keys.retainAll(validIds)
        queue.forEach { record ->
            if (record.kind == MediaKind.VIDEO && record.id !in mapped) mapped[record.id] = mediaItem(record)
        }
    }

    /**
     * One player is reused for all pages. The target view is switched immediately and no poster
     * layer is inserted between pages, matching the simpler v0.1 playback path.
     */
    fun play(record: MediaRecord, feedIndex: Int, view: PlayerView, resumePositionMs: Long = 0L) {
        if (record.kind != MediaKind.VIDEO) {
            pauseAndDetach()
            return
        }

        val changingMedia = currentRecordId != record.id || player.currentMediaItem?.mediaId != record.id.toString()
        val changingView = currentView !== view
        if (changingMedia) cancelTemporaryBoost()
        if (changingMedia) {
            currentView?.player = null
            currentView = null
            player.clearVideoSurface()
        }
        currentRecordId = record.id
        currentFeedIndex = feedIndex
        if (changingMedia || changingView) awaitingFirstFrameId = record.id
        attachTo(view)
        if (changingMedia) {
            val item = mapped[record.id] ?: mediaItem(record)
            player.setMediaItem(item)
            player.prepare()
            if (resumePositionMs > 0L && (record.durationMs <= 0L || resumePositionMs < record.durationMs - 2_000L)) {
                player.seekTo(resumePositionMs)
            }
            applyEffectiveSpeed()
        } else if (!changingView && player.playbackState == Player.STATE_READY) {
            listener?.onFirstFrame(record.id)
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
    }

    fun pauseOnly() {
        cancelTemporaryBoost()
        player.pause()
    }

    fun release() {
        if (released) return
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        currentView?.player = null
        currentView = null
        player.release()
    }

    private fun applyEffectiveSpeed() {
        val speed = if (temporary2x || locked2x) 2f else userSpeed
        // Explicit unit pitch keeps voices intelligible when speed changes.
        player.playbackParameters = PlaybackParameters(speed, 1f)
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
