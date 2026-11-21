package nl.giejay.mediaslider.animation

import android.view.View
import android.view.animation.LinearInterpolator
import nl.giejay.mediaslider.config.MotionOptions
import nl.giejay.mediaslider.config.MotionStyle
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Start and end camera state of a slow Ken Burns motion. Translations are in pixels, scale is a factor. */
data class MotionPlan(
    val startScale: Float,
    val endScale: Float,
    val startTranslationX: Float,
    val startTranslationY: Float,
    val endTranslationX: Float,
    val endTranslationY: Float
) {
    /**
     * The rest of this motion from [progress] (0 = start, 1 = end): starts where the linear motion is
     * at that point and ends at the same end state. Used to continue a motion instead of restarting it.
     */
    fun from(progress: Float): MotionPlan {
        val p = progress.coerceIn(0f, 1f)
        fun lerp(a: Float, b: Float) = a + (b - a) * p
        return copy(
            startScale = lerp(startScale, endScale),
            startTranslationX = lerp(startTranslationX, endTranslationX),
            startTranslationY = lerp(startTranslationY, endTranslationY)
        )
    }
}

/**
 * Pure planning of a calm, cinematic camera move. Contains no Android types so it can be unit tested.
 *
 * The image is always scaled by at least `1 + pan` so panning never reveals an edge, and every
 * endpoint is clamped to the overflow of the image at its own scale. Scale and translation are
 * interpolated linearly together, so valid endpoints keep every frame in between valid too.
 */
object KenBurnsPlanner {
    private const val MAX_JITTER_DEGREES = 25.0

    /**
     * @param slotW width of the area (full screen or one half of a portrait pair) that clips the image
     * @param displayW width of the image view; larger than the slot when the image covers it
     * @param displayH height of the image view
     * @return null when no motion is wanted
     */
    fun plan(
        slotW: Float,
        slotH: Float,
        displayW: Float,
        displayH: Float,
        options: MotionOptions,
        random: Random
    ): MotionPlan? {
        if (!options.enabled || slotW <= 0f || slotH <= 0f || displayW <= 0f || displayH <= 0f) return null
        val z = options.zoomPercent.coerceAtLeast(0) / 100f
        val p = options.panPercent.coerceAtLeast(0) / 100f
        if (z == 0f && p == 0f) return null

        val kinds = buildList {
            if (options.style != MotionStyle.PAN_ONLY && z > 0f) {
                add(Kind.ZOOM_IN)
                add(Kind.ZOOM_OUT)
            }
            if (options.style != MotionStyle.ZOOM_ONLY && p > 0f) add(Kind.PAN)
        }
        if (kinds.isEmpty()) return null

        val base = 1f + p
        val kind = kinds[random.nextInt(kinds.size)]
        val s0: Float
        val s1: Float
        when (kind) {
            Kind.ZOOM_IN -> { s0 = base; s1 = base + z }
            Kind.ZOOM_OUT -> { s0 = base + z; s1 = base }
            Kind.PAN -> { s0 = base; s1 = base }
        }

        var vx = 0f
        var vy = 0f
        if (kind == Kind.PAN) {
            val horizontal = displayW / displayH > slotW / slotH
            val jitter = (random.nextDouble() * 2 - 1) * MAX_JITTER_DEGREES
            var angle = Math.toRadians((if (horizontal) 0.0 else 90.0) + jitter)
            if (random.nextBoolean()) angle += Math.PI
            val length = p * slotW
            vx = (length * cos(angle)).toFloat()
            vy = (length * sin(angle)).toFloat()
        }

        return MotionPlan(
            s0, s1,
            clamp(-vx / 2, displayW, slotW, s0), clamp(-vy / 2, displayH, slotH, s0),
            clamp(vx / 2, displayW, slotW, s1), clamp(vy / 2, displayH, slotH, s1)
        )
    }

    /**
     * Plan for a panorama that is fitted (it does not cover the slot): scale it until it covers the
     * slot on the short axis, then scroll linearly from one end to the other along the long axis,
     * in a random direction. Returns null when there is nothing to scroll (the scaled image does
     * not overflow the slot by at least a pixel), so callers can fall back to [plan].
     */
    fun planPanorama(
        slotW: Float,
        slotH: Float,
        displayW: Float,
        displayH: Float,
        random: Random
    ): MotionPlan? {
        if (slotW <= 0f || slotH <= 0f || displayW <= 0f || displayH <= 0f) return null
        val scale = max(1f, max(slotW / displayW, slotH / displayH))
        val mx = maxTranslation(displayW, slotW, scale)
        val my = maxTranslation(displayH, slotH, scale)
        val horizontal = mx >= my
        val m = if (horizontal) mx else my
        if (m < 1f) return null
        val dir = if (random.nextBoolean()) 1f else -1f
        return if (horizontal) {
            MotionPlan(scale, scale, -dir * m, 0f, dir * m, 0f)
        } else {
            MotionPlan(scale, scale, 0f, -dir * m, 0f, dir * m)
        }
    }

    /** Largest translation that still keeps the slot covered by an image of [display] size at [scale]. */
    fun maxTranslation(display: Float, slot: Float, scale: Float): Float = max(0f, (display * scale - slot) / 2f)

    private fun clamp(t: Float, display: Float, slot: Float, scale: Float): Float {
        val m = maxTranslation(display, slot, scale)
        return min(m, max(-m, t))
    }

    private enum class Kind { ZOOM_IN, ZOOM_OUT, PAN }
}

/**
 * Plays a [MotionPlan] on a view at constant speed.
 *
 * Callers should pass [MotionTiming.fullDurationMs]: the motion starts with the incoming transition
 * and keeps running while the slide fades/slides out, so it never stops visibly before the next slide.
 * There is deliberately no end or reset animation; call [cancel] when the page is recycled.
 *
 * No hardware layer is used on the animated view (no `withLayer()`): an ImageView's bitmap is already a GPU
 * texture, so scaling/translating it is a single textured quad per frame, while a layer would add a texture
 * allocation + render at the start of the motion (which coincides with the slide transition), one more
 * full-screen composite pass per frame, and a blurry result for strongly scaled photos (panoramas).
 */
object KenBurnsAnimator {
    /** Animates from the view's current state to the end of [plan] (continues a motion that was frozen). */
    fun resume(view: View, plan: MotionPlan, durationMs: Long) {
        view.animate().cancel()
        animateToEnd(view, plan, durationMs)
    }

    /** Stops the motion where it is, without resetting the transform. */
    fun freeze(view: View) {
        view.animate().cancel()
    }

    /** Applies the start state at once, then animates to the end state. */
    fun start(view: View, plan: MotionPlan, durationMs: Long) {
        view.animate().cancel()
        view.pivotX = view.width / 2f
        view.pivotY = view.height / 2f
        view.scaleX = plan.startScale
        view.scaleY = plan.startScale
        view.translationX = plan.startTranslationX
        view.translationY = plan.startTranslationY
        animateToEnd(view, plan, durationMs)
    }

    /** Stops the motion and restores the identity transform. */
    fun cancel(view: View) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
    }

    private fun animateToEnd(view: View, plan: MotionPlan, durationMs: Long) {
        view.animate()
            .scaleX(plan.endScale)
            .scaleY(plan.endScale)
            .translationX(plan.endTranslationX)
            .translationY(plan.endTranslationY)
            .setDuration(durationMs)
            .setInterpolator(LinearInterpolator())
            .start()
    }
}
