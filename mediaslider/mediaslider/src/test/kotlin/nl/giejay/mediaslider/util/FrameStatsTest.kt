package nl.giejay.mediaslider.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameStatsTest {
    private val vsync = 16.67

    @Test
    fun smoothRunHasNoSlowFrames() {
        val s = FrameStats.summarize(doubleArrayOf(16.7, 16.6, 16.7, 16.7), 4, vsync)
        assertEquals(4, s.frames)
        assertEquals(0, s.slowFrames)
        assertEquals(0, s.droppedVsyncs)
        assertEquals(16.7, s.worstMs, 1e-9)
    }

    @Test
    fun lateFramesAreCountedAndDropsEstimated() {
        // 50ms = 3 vsyncs (2 dropped), 26ms ~ 1.56 vsyncs (1 dropped), 33.4ms = 2 vsyncs (1 dropped)
        val s = FrameStats.summarize(doubleArrayOf(16.7, 50.0, 26.0, 33.4), 4, vsync)
        assertEquals(3, s.slowFrames)
        assertEquals(4, s.droppedVsyncs)
        assertEquals(50.0, s.worstMs, 1e-9)
    }

    @Test
    fun onlyCountedEntriesAreUsed() {
        val s = FrameStats.summarize(doubleArrayOf(16.7, 500.0, 500.0), 1, vsync)
        assertEquals(1, s.frames)
        assertEquals(16.7, s.worstMs, 1e-9)
    }

    @Test
    fun emptyIsZero() {
        assertEquals(FrameSummary(0, 0.0, 0.0, 0, 0), FrameStats.summarize(DoubleArray(0), 0, vsync))
    }
}
