package nl.giejay.mediaslider.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapOpacityProfileTest {
    @Test
    fun `dense in the middle, flat plateau, then transparent`() {
        assertEquals(MapOpacityProfile.CORE_ALPHA, MapOpacityProfile.alphaAt(0f), 0.02f)
        assertEquals(0f, MapOpacityProfile.alphaAt(MapOpacityProfile.REACH_DP), 1e-4f)
        assertEquals(0f, MapOpacityProfile.alphaAt(MapOpacityProfile.REACH_DP + 50f), 1e-4f)
    }

    @Test
    fun `never rises with distance`() {
        var previous = MapOpacityProfile.alphaAt(0f)
        var d = 1f
        while (d <= MapOpacityProfile.REACH_DP + 10f) {
            val a = MapOpacityProfile.alphaAt(d)
            assertTrue("alpha rises at $d dp: $previous -> $a", a <= previous + 1e-4f)
            previous = a
            d += 1f
        }
    }

    @Test
    fun `stops start dense and end transparent`() {
        val (positions, alphas) = MapOpacityProfile.stops(32)
        assertEquals(0f, positions.first(), 0f)
        assertEquals(1f, positions.last(), 0f)
        assertTrue(alphas.first() > 0.8f)
        assertEquals(0f, alphas.last(), 0f)
    }
}
