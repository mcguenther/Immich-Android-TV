package nl.giejay.mediaslider.map

import kotlin.math.abs

/**
 * Opacity of the map's land over the distance from the location dot (measured horizontally; the map is an
 * ellipse, so vertical distances count 3/4 of that). Pure math, tuned with the mockup.
 *
 * Dense around the dot so the map reads on any photo, then flat and see-through, then fully transparent:
 * [CORE_ALPHA] up to [CORE_FULL_UNTIL_DP], easing to [RIM_ALPHA] at [RIM_REACHED_AT_DP]; independently the
 * opacity eases to 0 between [FADE_START_DP] and [FADE_END_DP]. The product is smoothed once more over
 * [SMOOTH_DP] so the curve has no visible kinks.
 */
object MapOpacityProfile {
    const val CORE_ALPHA = 0.85f
    const val RIM_ALPHA = 0.30f
    const val CORE_FULL_UNTIL_DP = 25f
    const val RIM_REACHED_AT_DP = 60f
    const val FADE_START_DP = 44f
    const val FADE_END_DP = 82f
    const val SMOOTH_DP = 12f

    /** Distance (dp, horizontal) at which the smoothed profile has reached 0. */
    const val REACH_DP = FADE_END_DP + SMOOTH_DP / 2

    private const val SMOOTH_SAMPLES = 13

    /** Opacity before the final smoothing. */
    fun raw(distanceDp: Float): Float {
        val core = 1f - smootherStep(CORE_FULL_UNTIL_DP, RIM_REACHED_AT_DP, distanceDp)
        val fade = 1f - smootherStep(FADE_START_DP, FADE_END_DP, distanceDp)
        return (RIM_ALPHA + (CORE_ALPHA - RIM_ALPHA) * core) * fade
    }

    /** Smoothed opacity (0..1) at [distanceDp]: [raw] averaged over a window of [SMOOTH_DP]. */
    fun alphaAt(distanceDp: Float): Float {
        var sum = 0f
        for (i in 0 until SMOOTH_SAMPLES) {
            val offset = (i / (SMOOTH_SAMPLES - 1f) - 0.5f) * SMOOTH_DP
            sum += raw(abs(distanceDp + offset)) // mirrored at the dot
        }
        return sum / SMOOTH_SAMPLES
    }

    /** Gradient stops: positions 0..1 of [REACH_DP] and the matching opacity (0..1). */
    fun stops(count: Int): Pair<FloatArray, FloatArray> {
        val positions = FloatArray(count) { it / (count - 1f) }
        val alphas = FloatArray(count) { alphaAt(positions[it] * REACH_DP) }
        alphas[count - 1] = 0f
        return positions to alphas
    }

    /** Quintic ease (zero slope and curvature at both ends), 0 below [from], 1 above [to]. */
    internal fun smootherStep(from: Float, to: Float, x: Float): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * t * (t * (t * 6f - 15f) + 10f)
    }
}
