package nl.giejay.mediaslider.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvanceGateTest {
    @Test fun advancesWhenTargetIsReady() =
        assertFalse(AdvanceGate.shouldWait(atLastPage = false, holdOnLastPage = false, targetReady = true, waitedMs = 0))

    @Test fun waitsForPhotosOfTheTarget() =
        assertTrue(AdvanceGate.shouldWait(atLastPage = false, holdOnLastPage = false, targetReady = false, waitedMs = 0))

    @Test fun givesUpWaitingForPhotosAfterTheLimit() {
        assertTrue(AdvanceGate.shouldWait(false, false, false, AdvanceGate.MAX_READY_WAIT_MS - 1))
        assertFalse(AdvanceGate.shouldWait(false, false, false, AdvanceGate.MAX_READY_WAIT_MS))
    }

    @Test fun staysOnLastPageWhileMorePagesAreLoading() {
        // even with a ready wrap-around target and after the photo wait limit
        assertTrue(AdvanceGate.shouldWait(atLastPage = true, holdOnLastPage = true, targetReady = true, waitedMs = 0))
        assertTrue(AdvanceGate.shouldWait(true, true, true, AdvanceGate.MAX_READY_WAIT_MS * 10))
    }

    @Test fun wrapsAroundWhenNothingIsLoading() =
        assertFalse(AdvanceGate.shouldWait(atLastPage = true, holdOnLastPage = false, targetReady = true, waitedMs = 0))

    @Test fun holdOnlyAppliesToTheLastPage() =
        assertFalse(AdvanceGate.shouldWait(atLastPage = false, holdOnLastPage = true, targetReady = true, waitedMs = 0))
}
