package nl.giejay.mediaslider.player

import org.junit.Assert.assertEquals
import org.junit.Test

class CinemaFrameRateTest {
    private val eps = 0.0001f

    @Test fun thirtyFpsTo24() = assertEquals(0.8f, playbackSpeedFor(30f, 24f), eps)
    @Test fun thirtyFpsTo23976() = assertEquals(0.7992f, playbackSpeedFor(30f, 23.976f), eps)
    @Test fun twentyFiveFps() = assertEquals(0.96f, playbackSpeedFor(25f, 24f), eps)
    @Test fun twentyFourFpsUnchanged() = assertEquals(1f, playbackSpeedFor(24f, 24f), eps)
    @Test fun cinemaFpsUnchanged() = assertEquals(1f, playbackSpeedFor(23.976f, 24f), eps)
    @Test fun lowerSourceUnchanged() = assertEquals(1f, playbackSpeedFor(15f, 24f), eps)
    @Test fun sixtyFpsClamped() = assertEquals(0.4f, playbackSpeedFor(60f, 24f), eps)
    @Test fun slowMoClamped() {
        assertEquals(0.4f, playbackSpeedFor(120f, 24f), eps)
        assertEquals(0.4f, playbackSpeedFor(240f, 23.976f), eps)
    }
    @Test fun unknownRate() {
        assertEquals(1f, playbackSpeedFor(-1f, 24f), eps) // Format.NO_VALUE
        assertEquals(1f, playbackSpeedFor(0f, 24f), eps)
        assertEquals(1f, playbackSpeedFor(Float.NaN, 24f), eps)
    }
    @Test fun offIsNormalSpeed() = assertEquals(1f, playbackSpeedFor(30f, null), eps)
}
