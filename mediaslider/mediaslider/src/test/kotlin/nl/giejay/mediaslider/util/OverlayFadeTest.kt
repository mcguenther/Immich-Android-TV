package nl.giejay.mediaslider.util

import nl.giejay.mediaslider.config.TransitionOptions
import nl.giejay.mediaslider.config.TransitionType
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayFadeTest {
    @Test
    fun fadeDurationFollowsTransition() {
        assertEquals(800L, OverlayFade.fadeMs(TransitionOptions(TransitionType.CROSSFADE, 800)))
        assertEquals(800L, OverlayFade.fadeMs(TransitionOptions(TransitionType.SLIDE, 800)))
        assertEquals(0L, OverlayFade.fadeMs(TransitionOptions(TransitionType.NONE, 800)))
        assertEquals(0L, OverlayFade.fadeMs(TransitionOptions(TransitionType.CROSSFADE, -5)))
    }

    @Test
    fun outgoingFadesFromItsStartAlpha() {
        assertEquals(1f, OverlayFade.outgoingAlpha(1f, 0f), 0f)
        assertEquals(0.3f, OverlayFade.outgoingAlpha(0.6f, 0.5f), 1e-6f)
        assertEquals(0f, OverlayFade.outgoingAlpha(0.6f, 1f), 0f)
    }

    @Test
    fun incomingStaysInvisibleUntilReady() {
        assertEquals(0f, OverlayFade.incomingAlpha(0.7f, -1, 0), 0f)
    }

    @Test
    fun incomingFollowsProgressWhenReadyEarly() {
        assertEquals(0.4f, OverlayFade.incomingAlpha(0.4f, 100, OverlayFade.rampMs(0, 1000)), 1e-6f)
    }

    @Test
    fun lateValueRampsInInsteadOfPopping() {
        val ramp = OverlayFade.rampMs(lateByMs = 600, fadeMs = 1000)
        assertEquals(OverlayFade.LATE_FADE_MS, ramp)
        // progress is already 0.6 but the value arrived just now
        assertEquals(0f, OverlayFade.incomingAlpha(0.6f, 0, ramp), 0f)
        assertEquals(0.5f, OverlayFade.incomingAlpha(0.6f, ramp / 2, ramp), 1e-6f)
        assertEquals(0.6f, OverlayFade.incomingAlpha(0.6f, ramp, ramp), 1e-6f)
    }

    @Test
    fun rampNeverExceedsShortFade() {
        assertEquals(100L, OverlayFade.rampMs(500, 100))
    }

    @Test
    fun switcherFlipsOnEveryTransition() {
        val s = OverlaySwitcher()
        assertEquals(0, s.front)
        assertEquals(1, s.begin())
        assertEquals(0, s.back)
        assertEquals(0, s.begin()) // second quick press: the fading-in layer becomes outgoing
        assertEquals(1, s.back)
    }

    @Test
    fun switcherCanStartOnTheOtherLayer() {
        val s = OverlaySwitcher(initialFront = 1)
        assertEquals(0, s.begin()) // first use lands on layer 0
        assertEquals(1, s.begin())
    }

    @Test
    fun remainingFadeIsWhatIsLeftOfTheTransition() {
        assertEquals(700L, OverlayFade.remainingFadeMs(1000, startedAt = 100, now = 400))
    }

    @Test
    fun lateFadeNeverBelowMinimum() {
        assertEquals(OverlayFade.LATE_FADE_MS, OverlayFade.remainingFadeMs(1000, 0, 5000))
        assertEquals(OverlayFade.LATE_FADE_MS, OverlayFade.remainingFadeMs(0, 0, 10))
        // Date overlay: minimum capped at the fade length itself.
        assertEquals(100L, OverlayFade.remainingFadeMs(100, 0, 5000, minMs = minOf(OverlayFade.LATE_FADE_MS, 100)))
    }
}
