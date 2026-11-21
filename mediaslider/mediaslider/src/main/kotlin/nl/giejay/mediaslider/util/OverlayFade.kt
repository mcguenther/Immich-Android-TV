package nl.giejay.mediaslider.util

import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import nl.giejay.mediaslider.config.TransitionOptions
import nl.giejay.mediaslider.config.TransitionType

/**
 * Timing rules shared by the page transition and the label (overlay) cross-fade, so texts fade in
 * lockstep with the photo. Pure functions except [interpolator].
 */
object OverlayFade {
    /** Shortest fade used when a value arrives after the page transition already ran. */
    const val LATE_FADE_MS = 250L

    /** Fade length in ms: the page transition's duration; 0 (swap instantly) for NONE or a zero duration. */
    fun fadeMs(transition: TransitionOptions): Long =
        transition.effectiveMs.toLong()

    /** Easing of the page transition (the scroller uses it), so labels follow the same curve. */
    fun interpolator(transition: TransitionOptions): Interpolator =
        if (transition.type == TransitionType.CROSSFADE) AccelerateDecelerateInterpolator() else DecelerateInterpolator(0.75F)

    /** Alpha of the outgoing texts [progress] (0..1, eased) into the fade, starting at [startAlpha]. */
    fun outgoingAlpha(startAlpha: Float, progress: Float): Float = (startAlpha * (1f - progress)).coerceIn(0f, 1f)

    /**
     * Alpha of the incoming texts. Their values may arrive after the fade started: until then they stay
     * invisible ([readyForMs] < 0), afterwards they may rise no faster than [rampMs] allows, so a late
     * value fades in instead of popping to the already advanced [progress].
     */
    fun incomingAlpha(progress: Float, readyForMs: Long, rampMs: Long): Float {
        if (readyForMs < 0) return 0f
        val ramp = if (rampMs <= 0) 1f else (readyForMs.toFloat() / rampMs).coerceIn(0f, 1f)
        return minOf(progress, ramp).coerceIn(0f, 1f)
    }

    /** Ramp length for values that became ready [lateByMs] after the fade started (0 = not late, no extra limit). */
    fun rampMs(lateByMs: Long, fadeMs: Long): Long =
        if (lateByMs <= 32L) 0L else minOf(LATE_FADE_MS, fadeMs.coerceAtLeast(1L))

    /**
     * Duration for an incoming fade started at [now] although the page transition began at [startedAt]
     * and lasts [fadeMs]: what is left of the transition, but never below [minMs] so a late value still
     * fades in briefly.
     */
    fun remainingFadeMs(fadeMs: Long, startedAt: Long, now: Long, minMs: Long = LATE_FADE_MS): Long =
        (fadeMs - (now - startedAt)).coerceAtLeast(minMs)
}

/**
 * Which of two stacked overlay layers is in front. [begin] starts a transition: the layer behind
 * becomes the incoming one and, as soon as it is called, the front. Overlapping transitions simply
 * flip again, so the layer that was fading in becomes the outgoing one.
 */
class OverlaySwitcher(initialFront: Int = 0) {
    /** Index (0/1) of the layer that shows (or fades in) the current slide. */
    var front: Int = initialFront
        private set
    val back: Int get() = 1 - front

    /** Flips the layers and returns the index of the new front (incoming) layer. */
    fun begin(): Int {
        front = back
        return front
    }
}
