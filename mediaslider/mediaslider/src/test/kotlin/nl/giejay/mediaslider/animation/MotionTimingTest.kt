package nl.giejay.mediaslider.animation

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionTimingTest {
    @Test fun fullDurationCoversOwnFadeOut() = assertEquals(10_000L + 2 * 1500L, MotionTiming.fullDurationMs(10, 1500))

    @Test fun noTransitionIsJustTheInterval() = assertEquals(8_000L, MotionTiming.fullDurationMs(8, 0))

    @Test fun resumeEndsWhenNextTransitionEnds() = assertEquals(10_000L + 1500L, MotionTiming.resumeDurationMs(10, 1500))

    @Test fun progressStartsAtSegmentStart() = assertEquals(0.4f, MotionTiming.progress(0.4f, 0L, 5_000L), 1e-6f)

    @Test fun progressIsLinearToTheEnd() = assertEquals(0.7f, MotionTiming.progress(0.4f, 2_500L, 5_000L), 1e-6f)

    @Test fun progressStopsAtTheEnd() = assertEquals(1f, MotionTiming.progress(0f, 9_000L, 5_000L), 1e-6f)

    @Test fun zeroDurationIsDone() = assertEquals(1f, MotionTiming.progress(0.2f, 0L, 0L), 1e-6f)
}
