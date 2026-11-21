package nl.giejay.mediaslider.util

import nl.giejay.mediaslider.config.ClipPosition
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * The part of a video that is shown, in media time. [clipLimited] is true when the window is shorter than
 * the (trimmed) video because of the clip length, false when it only stops early to cut off the video end.
 */
data class ClipWindow(val startMs: Long, val endMs: Long, val clipLimited: Boolean)

/**
 * Decides which excerpt of a long video the slideshow shows. The position inside the video is stored per
 * item id as a fraction (0 = beginning, 0.5 = middle, random for RANDOM), so the pre-caching of upcoming
 * videos and the player agree on the very same start. Not thread safe: use on the main thread.
 */
class ClipPlanner(private val random: Random = Random.Default) {
    private val fractions = object : LinkedHashMap<String, Double>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Double>?) = size > MAX_ENTRIES
    }

    /** Position of the excerpt as a fraction of the free room (0..1) for [itemId]; stable per id. */
    fun fraction(itemId: String, position: ClipPosition): Double = when (position) {
        ClipPosition.START -> 0.0
        ClipPosition.MIDDLE -> 0.5
        ClipPosition.RANDOM -> fractions.getOrPut(itemId) { random.nextDouble() }
    }

    /** See [excerpt]; the position is looked up (and for RANDOM remembered) per [itemId]. */
    fun excerpt(
        itemId: String,
        durationMs: Long,
        clipSeconds: Int,
        intervalSeconds: Int,
        speed: Float,
        position: ClipPosition,
        trimEndSeconds: Int = 0
    ): ClipWindow? = excerpt(durationMs, clipSeconds, intervalSeconds, speed, fraction(itemId, position), trimEndSeconds)

    companion object {
        private const val MAX_ENTRIES = 256

        /**
         * Screen time of a clip in milliseconds: [clipSeconds] (-1 = twice [intervalSeconds]); null when
         * the whole video is meant (0).
         */
        fun screenTimeMs(clipSeconds: Int, intervalSeconds: Int): Long? = when {
            clipSeconds == 0 -> null
            clipSeconds < 0 -> 2L * intervalSeconds.coerceAtLeast(1) * 1000L
            else -> clipSeconds * 1000L
        }

        /** Videos not longer than the trim plus this are never trimmed. */
        const val MIN_TRIM_REMAINDER_MS = 1000L

        /** Duration without the last [trimEndSeconds]; unchanged for very short videos. */
        fun effectiveDurationMs(durationMs: Long, trimEndSeconds: Int): Long {
            val trimMs = trimEndSeconds.coerceAtLeast(0) * 1000L
            return if (trimMs > 0 && durationMs > trimMs + MIN_TRIM_REMAINDER_MS) durationMs - trimMs else durationMs
        }

        /**
         * Window of a video of [durationMs] to show while the slideshow plays. The last [trimEndSeconds] are
         * cut off first (see [effectiveDurationMs]); the clip then fills its screen time at playback [speed]
         * (the media excerpt is `screenTime * speed` long) and [fraction] places it in the free room: 0 starts
         * at the beginning, 0.5 centres it, 1 ends with the (trimmed) video. A video not longer than the
         * excerpt, or whole-video mode, gives a window over the whole trimmed video. Null when nothing
         * changes: unknown duration, or the whole video is to be played untrimmed.
         */
        fun excerpt(
            durationMs: Long,
            clipSeconds: Int,
            intervalSeconds: Int,
            speed: Float,
            fraction: Double,
            trimEndSeconds: Int = 0
        ): ClipWindow? {
            if (durationMs <= 0) return null
            val effective = effectiveDurationMs(durationMs, trimEndSeconds)
            val screenMs = screenTimeMs(clipSeconds, intervalSeconds)
            val excerptMs = screenMs?.let { (it * speed.coerceIn(0.1f, 1f)).roundToLong().coerceAtLeast(1L) }
            if (excerptMs == null || effective <= excerptMs) {
                return if (effective < durationMs) ClipWindow(0, effective, false) else null
            }
            val start = ((effective - excerptMs) * fraction.coerceIn(0.0, 1.0)).roundToLong()
            return ClipWindow(start, start + excerptMs, true)
        }
    }
}
