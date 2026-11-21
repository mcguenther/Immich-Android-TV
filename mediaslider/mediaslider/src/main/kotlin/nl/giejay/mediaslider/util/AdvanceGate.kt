package nl.giejay.mediaslider.util

/**
 * When the slideshow timer fires, the next page may not be there yet: its photos are still loading, or (right
 * after start from cached pages) the pages that follow have not arrived. Advancing then would show a half
 * empty page that pops in piece by piece. Pure rule, so it is unit tested.
 */
object AdvanceGate {
    /** Longest wait for the photos of the next page; after that it is shown with whatever it has. */
    const val MAX_READY_WAIT_MS = 5_000L

    /** Interval of the re-check while waiting. */
    const val POLL_MS = 100L

    /**
     * True when the automatic advance must wait a little longer.
     *
     * @param atLastPage the current page is the last one (the next one would be a wrap-around)
     * @param holdOnLastPage more pages are on their way, see `MediaSliderConfiguration.holdOnLastPageUntilLoaded`
     * @param targetReady the photos of the page to advance to are delivered
     * @param waitedMs how long the advance has waited for [targetReady] already
     */
    fun shouldWait(atLastPage: Boolean, holdOnLastPage: Boolean, targetReady: Boolean, waitedMs: Long): Boolean =
        (atLastPage && holdOnLastPage) || (!targetReady && waitedMs < MAX_READY_WAIT_MS)
}
