package nl.giejay.mediaslider.animation

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionTimingTest {
    @Test fun fullDurationCoversOwnFadeOut() = assertEquals(10_000L + 2 * 1500L, MotionTiming.fullDurationMs(10, 1500))

    @Test fun noTransitionIsJustTheInterval() = assertEquals(8_000L, MotionTiming.fullDurationMs(8, 0))

    @Test fun resumeEndsWhenNextTransitionEnds() = assertEquals(10_000L + 1500L, MotionTiming.resumeDurationMs(10, 1500))
}
