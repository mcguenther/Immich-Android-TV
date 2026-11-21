package nl.giejay.mediaslider.animation

/**
 * Durations of the photo motion. The motion starts when the incoming transition starts (t = 0), the
 * next transition starts one interval after the page settled (t = T + interval) and ends at
 * t = 2T + interval, so the photo keeps moving during its own fade-out.
 */
object MotionTiming {
    /** Full motion duration in ms for a page that starts moving together with its incoming transition. */
    fun fullDurationMs(intervalSeconds: Int, transitionMs: Int): Long =
        intervalSeconds * 1000L + 2L * transitionMs.coerceAtLeast(0)

    /**
     * Remaining motion in ms after a resume. Resuming restarts the next-slide timer with a full
     * interval, so the motion must last until that transition has ended: interval + transition.
     */
    fun resumeDurationMs(intervalSeconds: Int, transitionMs: Int): Long =
        intervalSeconds * 1000L + transitionMs.coerceAtLeast(0)

    /**
     * Progress (0..1) of a motion segment that started at [startProgress] and reaches the end (1) after
     * [durationMs]; [elapsedMs] is the time since the segment started.
     */
    fun progress(startProgress: Float, elapsedMs: Long, durationMs: Long): Float {
        val start = startProgress.coerceIn(0f, 1f)
        if (durationMs <= 0L) return 1f
        val t = (elapsedMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
        return start + (1f - start) * t
    }
}
