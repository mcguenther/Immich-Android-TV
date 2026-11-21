package nl.giejay.mediaslider.util

/**
 * When to start prefetching after a slide change. Prefetching decodes large JPEGs and starts video I/O,
 * which competes with the render thread on a weak TV SoC, so it must not start while a transition is
 * animating: it waits until the transition has settled plus [SETTLE_DELAY_MS], and is postponed again
 * when another transition starts.
 */
object PrefetchDeferral {
    /** Quiet time after the transition settled before prefetching starts. */
    const val SETTLE_DELAY_MS = 400L

    /** Extra time on top of the transition length after which prefetching starts even if no "settled" signal came. */
    const val SAFETY_MARGIN_MS = 1000L

    /** Fallback delay measured from the transition start, for the case that no settled signal arrives. */
    fun fallbackDelayMs(transitionMs: Int): Long = transitionMs.coerceAtLeast(0) + SETTLE_DELAY_MS + SAFETY_MARGIN_MS
}
