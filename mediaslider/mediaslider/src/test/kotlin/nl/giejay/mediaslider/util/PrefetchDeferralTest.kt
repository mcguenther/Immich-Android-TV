package nl.giejay.mediaslider.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchDeferralTest {
    @Test
    fun fallbackIsAfterTransitionAndSettleDelay() {
        assertEquals(1000 + PrefetchDeferral.SETTLE_DELAY_MS + PrefetchDeferral.SAFETY_MARGIN_MS, PrefetchDeferral.fallbackDelayMs(1000))
        assertEquals(PrefetchDeferral.SETTLE_DELAY_MS + PrefetchDeferral.SAFETY_MARGIN_MS, PrefetchDeferral.fallbackDelayMs(-1))
    }

    @Test
    fun settleDelayIsShortButNotZero() {
        assertTrue(PrefetchDeferral.SETTLE_DELAY_MS in 300L..500L)
    }
}
