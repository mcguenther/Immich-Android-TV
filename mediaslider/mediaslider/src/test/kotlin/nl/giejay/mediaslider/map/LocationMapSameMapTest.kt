package nl.giejay.mediaslider.map

import nl.giejay.mediaslider.model.GeoLocation
import nl.giejay.mediaslider.model.LocationPrecision
import nl.giejay.mediaslider.model.MapLocation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationMapSameMapTest {
    private val seefeld = GeoLocation(47.377, 11.156)

    @Test
    fun `identical and very close positions keep the map`() {
        assertTrue(LocationMapRenderer.isSameMap(MapLocation(seefeld), MapLocation(seefeld)))
        // ~1 km away: well under one dp at zoom 4
        assertTrue(LocationMapRenderer.isSameMap(MapLocation(seefeld), MapLocation(GeoLocation(47.385, 11.165))))
    }

    @Test
    fun `positions a few dp apart get a new map`() {
        // Seefeld to Innsbruck is ~40 km, several dp at zoom 4
        assertFalse(LocationMapRenderer.isSameMap(MapLocation(seefeld), MapLocation(GeoLocation(47.27, 11.40))))
    }

    @Test
    fun `a different kind of mark needs a new map`() {
        assertFalse(LocationMapRenderer.isSameMap(
            MapLocation(seefeld),
            MapLocation(seefeld, LocationPrecision.NEAR, 10.0)
        ))
    }

    @Test
    fun `rough estimates compare their cloud size`() {
        val a = MapLocation(seefeld, LocationPrecision.ROUGH, 60.0)
        assertTrue(LocationMapRenderer.isSameMap(a, MapLocation(seefeld, LocationPrecision.ROUGH, 62.0)))
        assertFalse(LocationMapRenderer.isSameMap(a, MapLocation(seefeld, LocationPrecision.ROUGH, 120.0)))
    }

    @Test
    fun `corridors compare both known positions`() {
        val munich = GeoLocation(48.137, 11.575)
        val verona = GeoLocation(45.438, 10.992)
        val middle = GeoLocation(46.79, 11.28)
        val a = MapLocation(middle, LocationPrecision.CORRIDOR, 40.0, munich, verona)
        assertTrue(LocationMapRenderer.isSameMap(a, MapLocation(middle, LocationPrecision.CORRIDOR, 40.0, munich, verona)))
        assertFalse(LocationMapRenderer.isSameMap(a, MapLocation(middle, LocationPrecision.CORRIDOR, 40.0, munich, GeoLocation(44.5, 11.3))))
    }
}
