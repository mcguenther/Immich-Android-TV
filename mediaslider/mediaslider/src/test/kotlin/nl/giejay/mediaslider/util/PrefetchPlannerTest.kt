package nl.giejay.mediaslider.util

import nl.giejay.mediaslider.adapter.SliderImageRequests
import org.junit.Assert.assertEquals
import org.junit.Test

class PrefetchPlannerTest {
    @Test fun nextSlidesInMiddle() = assertEquals(listOf(3, 4, 5), PrefetchPlanner.indicesToPrefetch(2, 3, 10))

    @Test fun wrapsAroundAtEnd() = assertEquals(listOf(9, 0, 1), PrefetchPlanner.indicesToPrefetch(8, 3, 10))

    @Test fun zeroCountIsEmpty() = assertEquals(emptyList<Int>(), PrefetchPlanner.indicesToPrefetch(0, 0, 10))

    @Test fun countLargerThanListSkipsCurrentAndDuplicates() =
        assertEquals(listOf(2, 0), PrefetchPlanner.indicesToPrefetch(1, 5, 3))

    @Test fun singleOrEmptyListIsEmpty() {
        assertEquals(emptyList<Int>(), PrefetchPlanner.indicesToPrefetch(0, 3, 1))
        assertEquals(emptyList<Int>(), PrefetchPlanner.indicesToPrefetch(0, 3, 0))
    }

    @Test fun invalidCurrentIsEmpty() = assertEquals(emptyList<Int>(), PrefetchPlanner.indicesToPrefetch(7, 3, 5))

    @Test fun lowRamCapsToOne() {
        assertEquals(1, PrefetchPlanner.effectiveCount(3, true, 512))
        assertEquals(1, PrefetchPlanner.effectiveCount(3, false, 128))
        assertEquals(3, PrefetchPlanner.effectiveCount(3, false, 256))
        assertEquals(0, PrefetchPlanner.effectiveCount(0, true, 128))
        assertEquals(0, PrefetchPlanner.effectiveCount(-2, false, 512))
    }

    @Test fun staleKeysAreThoseOutsideTheWindow() =
        assertEquals(listOf("a", "c"), PrefetchPlanner.staleKeys(listOf("a", "b", "c"), setOf("b", "d")))

    @Test fun capLongEdgeKeepsAspect() {
        assertEquals(2880 to 1620, SliderImageRequests.capLongEdge(4400, 2475, 2880))
        assertEquals(1620 to 2880, SliderImageRequests.capLongEdge(2475, 4400, 2880))
        assertEquals(1920 to 1080, SliderImageRequests.capLongEdge(1920, 1080, 2880))
    }
}
