package nl.giejay.mediaslider.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageJumpTest {
    @Test fun neighboursAnimate() {
        assertTrue(PageJump.smooth(3, 4, true))
        assertTrue(PageJump.smooth(3, 2, true))
    }

    @Test fun wrapAroundIsHardCut() {
        assertFalse(PageJump.smooth(9, 0, true))
        assertFalse(PageJump.smooth(0, 9, true))
    }

    @Test fun disabledNeverAnimates() = assertFalse(PageJump.smooth(3, 4, false))

    @Test fun reversePrefetchWindow() {
        assertTrue(PrefetchPlanner.indicesToPrefetch(1, 3, 10, reverse = true) == listOf(0, 9, 8))
    }
}
