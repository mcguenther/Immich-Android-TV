package nl.giejay.mediaslider.util

import nl.giejay.mediaslider.config.ClipPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ClipPlannerTest {
    private val min = 60_000L

    @Test
    fun autoLengthIsTwiceTheInterval() {
        assertEquals(20_000L, ClipPlanner.screenTimeMs(-1, 10))
        assertEquals(6_000L, ClipPlanner.screenTimeMs(-1, 3))
        assertEquals(12_000L, ClipPlanner.screenTimeMs(12, 10))
        assertNull(ClipPlanner.screenTimeMs(0, 10))
    }

    @Test
    fun wholeVideoNeedsNoWindowWithoutTrim() {
        assertNull(ClipPlanner.excerpt(min, 0, 10, 1f, 0.0, 0))
    }

    @Test
    fun wholeVideoEndsAtTrimmedEnd() {
        assertEquals(ClipWindow(0, 58_000, false), ClipPlanner.excerpt(min, 0, 10, 1f, 0.0, 2))
    }

    @Test
    fun shorterVideoPlaysCompletely() {
        assertNull(ClipPlanner.excerpt(15_000, -1, 10, 1f, 0.5, 0))
        assertNull(ClipPlanner.excerpt(20_000, -1, 10, 1f, 0.5, 0))
        // with trim the window covers the trimmed video and is not clip-limited
        assertEquals(ClipWindow(0, 14_000, false), ClipPlanner.excerpt(15_000, -1, 10, 1f, 0.5, 1))
    }

    @Test
    fun startMiddleAndEnd() {
        assertEquals(ClipWindow(0, 20_000, true), ClipPlanner.excerpt(min, -1, 10, 1f, 0.0, 0))
        assertEquals(ClipWindow(20_000, 40_000, true), ClipPlanner.excerpt(min, -1, 10, 1f, 0.5, 0))
        assertEquals(ClipWindow(40_000, 60_000, true), ClipPlanner.excerpt(min, -1, 10, 1f, 1.0, 0))
    }

    @Test
    fun speedShortensTheMediaExcerpt() {
        val w = ClipPlanner.excerpt(min, 10, 10, 0.8f, 0.0, 0)!!
        assertEquals(8_000L, w.endMs - w.startMs)
        assertEquals(800L, ClipPlanner.excerpt(min, 2, 10, 0.4f, 0.0, 0)!!.endMs)
    }

    @Test
    fun trimReducesFreeRoom() {
        for (trim in 0..2) {
            val eff = 60_000L - trim * 1000
            assertEquals(eff, ClipPlanner.excerpt(60_000, 10, 10, 1f, 1.0, trim)!!.endMs)
            assertEquals((eff - 10_000) / 2, ClipPlanner.excerpt(60_000, 10, 10, 1f, 0.5, trim)!!.startMs)
        }
    }

    @Test
    fun veryShortVideosAreNotTrimmed() {
        assertEquals(1_500L, ClipPlanner.effectiveDurationMs(1_500, 1))
        assertEquals(2_000L, ClipPlanner.effectiveDurationMs(2_000, 1))
        assertEquals(1_001L, ClipPlanner.effectiveDurationMs(2_001, 1))
        assertEquals(2_500L, ClipPlanner.effectiveDurationMs(2_500, 2))
        assertEquals(10_000L, ClipPlanner.effectiveDurationMs(10_000, 0))
        assertNull(ClipPlanner.excerpt(1_800, 0, 10, 1f, 0.0, 1))
    }

    @Test
    fun unknownDurationGivesNoWindow() {
        assertNull(ClipPlanner.excerpt(0, -1, 10, 1f, 0.0, 1))
    }

    @Test
    fun randomStaysInBoundsAndIsStablePerItem() {
        val planner = ClipPlanner(Random(7))
        val seen = HashSet<Long>()
        for (i in 0 until 50) {
            val w = planner.excerpt("a$i", min, -1, 10, 1f, ClipPosition.RANDOM, 2)!!
            assertTrue(w.startMs >= 0 && w.endMs <= 58_000)
            assertEquals(20_000L, w.endMs - w.startMs)
            assertEquals(w, planner.excerpt("a$i", min, -1, 10, 1f, ClipPosition.RANDOM, 2))
            seen.add(w.startMs)
        }
        assertTrue(seen.size > 10)
        val first = planner.fraction("x", ClipPosition.RANDOM)
        planner.forget("x")
        assertNotEquals(first, planner.fraction("x", ClipPosition.RANDOM), 0.0)
        assertEquals(20_000L, planner.excerpt("m", min, -1, 10, 1f, ClipPosition.MIDDLE)!!.startMs)
    }
}
