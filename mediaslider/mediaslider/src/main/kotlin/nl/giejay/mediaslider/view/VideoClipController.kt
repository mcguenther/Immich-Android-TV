package nl.giejay.mediaslider.view

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.SliderItem
import nl.giejay.mediaslider.util.ClipPlanner
import nl.giejay.mediaslider.util.ClipWindow

/**
 * Video clips while the slideshow plays: limits the screen time of a video to the configured clip length,
 * starts it at the planned excerpt, trims the end and fades the sound out before moving on. Kept out of
 * [MediaSliderController] (which forwards the player and slideshow events) so the upstream file stays small.
 * Must be used on the main thread.
 *
 * @param config current slider configuration (read lazily, it is set after construction)
 * @param currentPlayer the player of the video page in view
 * @param slideShowPlaying whether the slideshow currently plays
 * @param advance moves on to the next asset in the slideshow direction when a clip has ended
 */
class VideoClipController(
    private val mainHandler: Handler,
    private val config: () -> MediaSliderConfiguration,
    private val currentPlayer: () -> ExoPlayer?,
    private val slideShowPlaying: () -> Boolean,
    private val advance: () -> Unit
) {
    /**
     * Shared with the prefetcher so both agree on the start of a random excerpt. The view hands in its
     * planner before playback starts; until then a private one is used.
     */
    var planner: ClipPlanner = ClipPlanner()

    private class ActiveClip(val item: SliderItem, val player: ExoPlayer) {
        var window: ClipWindow? = null
        var awaitingDuration = false
        var running = false
        var finished = false
        /** Screen time left for a clip-limited window; null until the timer first runs. */
        var remainingMs: Long? = null
        var deadline = 0L
        var baseVolume = -1f
        var fading = false
    }

    private var activeClip: ActiveClip? = null
    private val clipTickRunnable = Runnable { clipTick() }

    private fun planClip(item: SliderItem, durationMs: Long, speed: Float): ClipWindow? {
        val cfg = config()
        return planner.excerptFor(item.id, durationMs, cfg.videoPlayback, cfg.interval, speed)
    }

    private fun screenTimeMs(): Long? {
        val cfg = config()
        return ClipPlanner.screenTimeMs(cfg.videoPlayback.clipLengthSeconds, cfg.interval)
    }

    /**
     * Prepares the clip of the video [item] about to play on [player] and returns the media position
     * (ms) to start at, so the caller can seek before playback begins. Only while the slideshow plays;
     * otherwise (manual playback) the video plays normally and 0 is returned. When the duration is not
     * known from the item it is resolved on the first STATE_READY.
     */
    fun begin(item: SliderItem, player: ExoPlayer): Long {
        cancel()
        if (!slideShowPlaying()) return 0L
        val clip = ActiveClip(item, player)
        val known = item.durationMs ?: player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        if (known != null) {
            clip.window = planClip(item, known, player.playbackParameters.speed) ?: return 0L
        } else {
            clip.awaitingDuration = true
        }
        activeClip = clip
        return clip.window?.startMs ?: 0L
    }

    /**
     * Where the clip of [item] would start if it began now (same rule as [begin]): 0 outside the slideshow
     * or when the duration is unknown or the video plays completely. Lets a player be warmed up at the
     * right position before its page is shown.
     */
    fun plannedStartMs(item: SliderItem): Long {
        if (!slideShowPlaying()) return 0L
        val duration = item.durationMs ?: return 0L
        return planClip(item, duration, 1f)?.startMs ?: 0L
    }

    /** Forwarded player state: resolves an unknown duration when ready, stops the timer when the video ended. */
    fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) resolveClipDuration()
        if (playbackState == Player.STATE_ENDED) {
            // The video ended by itself: the clip timer must not advance a second time.
            activeClip?.let { if (it.player === currentPlayer()) cancel() }
        }
    }

    private fun resolveClipDuration() {
        val clip = activeClip ?: return
        if (!clip.awaitingDuration || clip.player !== currentPlayer()) return
        val duration = clip.player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        clip.awaitingDuration = false
        val window = planClip(clip.item, duration, clip.player.playbackParameters.speed)
        if (window == null) {
            activeClip = null
            return
        }
        clip.window = window
        if (window.startMs > 0) clip.player.seekTo(window.startMs)
    }

    private fun clipRemainingMs(clip: ActiveClip, window: ClipWindow): Long {
        if (window.clipLimited) {
            return if (clip.running) clip.deadline - SystemClock.uptimeMillis() else clip.remainingMs ?: 0L
        }
        val speed = clip.player.playbackParameters.speed.coerceIn(0.1f, 1f)
        return ((window.endMs - clip.player.currentPosition) / speed).toLong()
    }

    /** Starts (or resumes) the clip timer of the current video; no-op outside the slideshow. */
    fun startTimer() {
        val clip = activeClip ?: return
        val window = clip.window ?: return
        if (!slideShowPlaying() || clip.running || clip.finished || clip.player !== currentPlayer()) return
        if (window.clipLimited) {
            val total = screenTimeMs() ?: return
            val left = clip.remainingMs ?: total
            clip.deadline = SystemClock.uptimeMillis() + left
        }
        if (clip.baseVolume < 0f) clip.baseVolume = clip.player.volume
        clip.running = true
        clipTick()
    }

    /** Pauses the clip timer, keeping the remaining screen time for [startTimer]. */
    fun pauseTimer() {
        val clip = activeClip ?: return
        if (!clip.running) return
        mainHandler.removeCallbacks(clipTickRunnable)
        val window = clip.window
        if (window != null && window.clipLimited) {
            clip.remainingMs = (clip.deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        }
        clip.running = false
    }

    private fun clipTick() {
        val clip = activeClip ?: return
        val window = clip.window ?: return
        if (!clip.running || clip.finished) return
        val remaining = clipRemainingMs(clip, window)
        if (remaining <= 0) {
            clip.finished = true
            clip.running = false
            advance()
            return
        }
        // Short audio fade-out at the end of the clip (only with sound on).
        val fade = config().isVideoSoundEnable && clip.baseVolume > 0f && remaining <= CLIP_FADE_MS
        if (fade) {
            clip.fading = true
            clip.player.volume = (clip.baseVolume * remaining / CLIP_FADE_MS).coerceAtLeast(clip.baseVolume * 0.02f)
        }
        val delay = when {
            fade -> CLIP_FADE_STEP_MS
            else -> (remaining - CLIP_FADE_MS).coerceAtLeast(CLIP_FADE_STEP_MS)
        }.let { if (window.clipLimited) it else it.coerceAtMost(CLIP_POLL_MS) }
        mainHandler.postDelayed(clipTickRunnable, delay)
    }

    /** Stops the clip timer of the current video and restores a faded volume. */
    fun cancel() {
        mainHandler.removeCallbacks(clipTickRunnable)
        val clip = activeClip ?: return
        if (clip.fading && clip.baseVolume > 0f) clip.player.volume = clip.baseVolume
        activeClip = null
    }

    /**
     * Progress (0..1) through the clip of the current video, or null when no clip is active; lets the
     * story progress bar show the clip instead of the whole video.
     */
    fun progress(): Float? {
        val clip = activeClip ?: return null
        val window = clip.window ?: return null
        if (clip.player !== currentPlayer()) return null
        val span = (window.endMs - window.startMs).coerceAtLeast(1L)
        return if (window.clipLimited) {
            val total = screenTimeMs() ?: return null
            val left = clipRemainingMs(clip, window).coerceIn(0L, total)
            1f - left.toFloat() / total
        } else {
            ((clip.player.currentPosition - window.startMs).toFloat() / span).coerceIn(0f, 1f)
        }
    }

    private companion object {
        const val CLIP_FADE_MS = 500L
        const val CLIP_FADE_STEP_MS = 50L
        const val CLIP_POLL_MS = 250L
    }
}
