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

    @Test
    fun snapPicksTheNearestOptionAndPrefersTheLargerOnATie() {
        assertEquals(70, IntervalOptions.snap(66))
        assertEquals(70, IntervalOptions.snap(65))
        assertEquals(60, IntervalOptions.snap(61))
        assertEquals(1, IntervalOptions.snap(0))
        assertEquals(600, IntervalOptions.snap(650))
    }

    @Test
    fun steppingMinutesKeepsTheSecondsWhereThatIsPossible() {
        assertEquals(70, IntervalOptions.stepMinutes(6, 1))
        assertEquals(10, IntervalOptions.stepMinutes(70, -1))
        assertEquals(600, IntervalOptions.stepMinutes(590, 1))
        assertEquals(600, IntervalOptions.stepMinutes(600, 1))
        assertEquals(1, IntervalOptions.stepMinutes(1, -1))
    }

    @Test
    fun steppingSecondsCarriesOverIntoTheNextMinute() {
        assertEquals(7, IntervalOptions.stepSeconds(6, 1))
        assertEquals(12, IntervalOptions.stepSeconds(10, 1))
        assertEquals(60, IntervalOptions.stepSeconds(58, 1))
        assertEquals(58, IntervalOptions.stepSeconds(60, -1))
        assertEquals(80, IntervalOptions.stepSeconds(70, 1))
        assertEquals(1, IntervalOptions.stepSeconds(1, -1))
        assertEquals(600, IntervalOptions.stepSeconds(600, 1))
    }

    @Test
    fun theTwoColumnsReachNinetySecondsInFiveKeyPresses() {
        // right/left only switch the focused column, so these are the three value changes of that path
        var v = 6
        v = IntervalOptions.stepMinutes(v, 1)
        v = IntervalOptions.stepSeconds(v, 1)
        v = IntervalOptions.stepSeconds(v, 1)
        assertEquals(90, v)
    }
}
