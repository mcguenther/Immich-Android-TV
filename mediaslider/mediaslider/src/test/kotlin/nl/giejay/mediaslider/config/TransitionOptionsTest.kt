package nl.giejay.mediaslider.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionOptionsTest {
    @Test
    fun animatedTransitionsReportTheirDuration() {
        val crossfade = TransitionOptions(TransitionType.CROSSFADE, 800)
        assertEquals(800, crossfade.effectiveMs)
        assertTrue(crossfade.isAnimated)
        assertEquals(500, TransitionOptions(TransitionType.SLIDE, 500).effectiveMs)
    }

    @Test
    fun noneIsNeverAnimated() {
        val none = TransitionOptions(TransitionType.NONE, 800)
        assertEquals(0, none.effectiveMs)
        assertFalse(none.isAnimated)
    }

    @Test
    fun nonPositiveDurationIsNotAnimated() {
        assertEquals(0, TransitionOptions(TransitionType.CROSSFADE, 0).effectiveMs)
        assertEquals(0, TransitionOptions(TransitionType.SLIDE, -5).effectiveMs)
        assertFalse(TransitionOptions(TransitionType.SLIDE, -5).isAnimated)
    }
}
