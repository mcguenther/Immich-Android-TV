package nl.giejay.mediaslider.view

import nl.giejay.mediaslider.animation.MotionPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveMotionTest {
    private val plan = MotionPlan(1.1f, 1.3f, -40f, 10f, 60f, -30f)

    private fun motion(startAt: Long = 1_000L, startProgress: Float = 0f, durationMs: Long = 10_000L) =
        PageMotionController.ActiveMotion(3, plan, 42L).apply { beginSegment(startAt, startProgress, durationMs) }

    @Test
    fun progressFollowsTheSegment() {
        val m = motion()
        assertFalse(m.isFrozen)
        assertEquals(0f, m.progressAt(1_000L), 1e-6f)
        assertEquals(0.5f, m.progressAt(6_000L), 1e-6f)
        assertEquals(1f, m.progressAt(20_000L), 1e-6f)
        assertEquals(5_000L, m.remainingMs(6_000L))
        assertEquals(0L, m.remainingMs(20_000L))
    }

    @Test
    fun freezeKeepsTheProgressAndTheFirstFreezeWins() {
        val m = motion()
        m.freeze(3_500L)
        assertTrue(m.isFrozen)
        assertEquals(0.25f, m.frozenProgress, 1e-6f)
        assertEquals(0.25f, m.progressAt(9_000L), 1e-6f)
        m.freeze(8_000L)
        assertEquals(0.25f, m.frozenProgress, 1e-6f)
    }

    @Test
    fun newSegmentContinuesFromItsStartProgressAndUnfreezes() {
        val m = motion()
        m.freeze(3_500L)
        m.beginSegment(50_000L, m.frozenProgress, 3_000L)
        assertFalse(m.isFrozen)
        assertEquals(0.25f, m.progressAt(50_000L), 1e-6f)
        assertEquals(0.625f, m.progressAt(51_500L), 1e-6f)
        assertEquals(1_500L, m.remainingMs(51_500L))
    }
}
