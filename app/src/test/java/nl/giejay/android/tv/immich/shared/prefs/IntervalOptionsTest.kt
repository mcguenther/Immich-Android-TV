package nl.giejay.android.tv.immich.shared.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntervalOptionsTest {
    private val values = IntervalOptions.seconds

    @Test
    fun oneSecondStepsBelowTenSeconds() {
        assertEquals((1..9).toList(), values.filter { it < 10 })
    }

    @Test
    fun twoSecondStepsFromTenSecondsToOneMinute() {
        assertEquals((10..60 step 2).toList(), values.filter { it in 10..60 })
    }

    @Test
    fun tenSecondStepsAboveOneMinute() {
        assertEquals((70..600 step 10).toList(), values.filter { it > 60 })
    }

    @Test
    fun sortedWithoutDuplicates() {
        assertEquals(values.distinct().sorted(), values)
    }

    @Test
    fun keepsEveryValueOfTheOldFixedList() {
        val old = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 20, 30, 60, 180, 300, 600)
        assertTrue(values.containsAll(old))
    }

    @Test
    fun defaultsAreSelectable() {
        assertTrue(6 in values)
        assertTrue(10 in values)
    }
}
