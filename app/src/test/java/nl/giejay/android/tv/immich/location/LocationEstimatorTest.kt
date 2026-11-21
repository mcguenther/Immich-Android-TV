package nl.giejay.android.tv.immich.location

import nl.giejay.mediaslider.model.GeoLocation
import nl.giejay.mediaslider.model.LocationPrecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Rules of [LocationEstimator], checked on the example weekend of the concept page: Munich, Zugspitze,
 * Innsbruck, over the Brenner to Lake Garda, Monday flight to Lisbon. Clock times are local (camera on German time).
 */
class LocationEstimatorTest {

    private fun t(day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(2025, 6, day, hour, minute).toEpochSecond(ZoneOffset.UTC) * 1000

    private fun anchor(id: String, time: Long, lat: Double, lon: Double, city: String, country: String = "Deutschland", owner: String = "me") =
        LocationAnchor(id, owner, time, lat, lon, city, country)

    private val munich = anchor("P1", t(14, 7, 52), 48.1374, 11.5755, "München")
    private val garmisch = anchor("P2", t(14, 10, 48), 47.4917, 11.0955, "Garmisch-Partenkirchen")
    private val eibsee = anchor("P2b", t(14, 13, 52), 47.4566, 10.9726, "Grainau")
    private val innsbruck = anchor("P3", t(14, 16, 55), 47.2692, 11.4041, "Innsbruck", "Österreich")
    private val bozen = anchor("P4", t(15, 15, 20), 46.4983, 11.3548, "Bozen", "Italien")
    private val riva = anchor("P5", t(15, 18, 45), 45.8858, 10.841, "Riva del Garda", "Italien")
    private val verona = anchor("P6", t(16, 9, 5), 45.3958, 10.8885, "Verona", "Italien")
    private val lisbon = anchor("P7", t(16, 18, 40), 38.7223, -9.1393, "Lissabon", "Portugal")
    private val weekend = listOf(munich, garmisch, eibsee, innsbruck, bozen, riva, verona, lisbon).shuffled()

    private fun estimate(time: Long) = LocationEstimator.estimate(time, weekend)
    private fun errorKm(e: LocationEstimate, lat: Double, lon: Double) =
        LocationEstimator.distanceKm(GeoLocation(e.latitude, e.longitude), GeoLocation(lat, lon))

    @Test
    fun `photo between two phone photos on the same day lies on the corridor`() {
        val e = estimate(t(14, 15, 10))!! // Seefeld in Tirol
        assertEquals(LocationPrecision.CORRIDOR, e.precision)
        assertEquals(eibsee, e.before)
        assertEquals(innsbruck, e.after)
        assertTrue("error ${errorKm(e, 47.329, 11.188)} km", errorKm(e, 47.329, 11.188) < 15)
        val map = e.toMapLocation()
        assertEquals(LocationPrecision.CORRIDOR, map.precision)
        assertEquals(eibsee.location, map.corridorStart)
        assertEquals(innsbruck.location, map.corridorEnd)
    }

    @Test
    fun `estimate is never more precise than ten kilometres`() {
        val e = estimate(t(14, 13, 45))!! // seven minutes before the Eibsee photo
        assertEquals(LocationPrecision.NEAR, e.precision)
        assertTrue(e.radiusKm >= LocationEstimator.MIN_RADIUS_KM)
        // Even right on a phone photo the radius stays at the floor.
        assertTrue(estimate(eibsee.wallTimeMs)!!.radiusKm >= LocationEstimator.MIN_RADIUS_KM)
    }

    @Test
    fun `uncertainty covers a camera clock that is an hour off`() {
        // Munich to Garmisch: 80 km in 3 hours. One hour of driving is about 27 km.
        val e = estimate(t(14, 9, 20))!!
        assertTrue("radius ${e.radiusKm}", e.radiusKm >= 25)
    }

    @Test
    fun `gap over night is only a rough estimate`() {
        val e = estimate(t(15, 9, 15))!! // Sunday breakfast in Innsbruck, next phone photo in Bozen
        assertEquals(LocationPrecision.ROUGH, e.precision)
        assertEquals(LocationPrecision.ROUGH, e.toMapLocation().precision)
        assertNull(e.toMapLocation().corridorStart)
    }

    @Test
    fun `no interpolation across a flight, but close to landing the photo is placed at the destination`() {
        assertNull(estimate(t(16, 13, 30))) // somewhere in the air
        val landed = estimate(t(16, 18, 5))!! // 35 minutes before the phone photo in Lisbon
        assertEquals(LocationPrecision.ROUGH, landed.precision)
        assertEquals(lisbon, landed.nearest)
        assertTrue(errorKm(landed, 38.7223, -9.1393) < 1)
    }

    @Test
    fun `known position on one side only gives a rough estimate for a few hours, then none`() {
        val e = estimate(t(16, 20, 30))!! // evening in Lisbon, no later phone photo
        assertEquals(LocationPrecision.ROUGH, e.precision)
        assertEquals(lisbon, e.before)
        assertNull(e.after)
        assertNull(LocationEstimator.estimate(t(16, 20, 30), listOf(munich))) // two days earlier
    }

    @Test
    fun `long drive over night is discarded unless the photo's own day has a phone photo`() {
        val hamburg = anchor("H", t(20, 18, 0), 53.55, 9.99, "Hamburg")
        val rome = anchor("R", t(21, 16, 0), 41.9, 12.5, "Rom", "Italien")
        // Next morning, 1,300 km apart over night: the same-day photo in Rome is 8 hours later -> rough at Rome.
        val sameDay = LocationEstimator.estimate(t(21, 8, 0), listOf(hamburg, rome))!!
        assertEquals(rome, sameDay.nearest)
        assertEquals(LocationPrecision.ROUGH, sameDay.precision)
        // Same evening: the photo in Hamburg five hours earlier counts.
        assertEquals(hamburg, LocationEstimator.estimate(t(20, 23, 0), listOf(hamburg, rome))!!.nearest)
        // In the middle of the night: Rome is 14 hours away, Hamburg on the day before -> nothing.
        assertNull(LocationEstimator.estimate(t(21, 2, 0), listOf(hamburg, rome)))
    }

    @Test
    fun `nothing within 36 hours gives no estimate`() {
        assertNull(LocationEstimator.estimate(t(20, 12, 0), weekend))
        assertNull(LocationEstimator.estimate(t(20, 12, 0), emptyList()))
    }

    @Test
    fun `place labels follow the precision`() {
        val near = { p: String -> "ca. $p" }
        val between = { a: String, b: String -> "zwischen $a und $b" }
        val area = { p: String -> "Raum $p" }
        assertEquals("zwischen Grainau und Innsbruck", estimate(t(14, 15, 10))!!.placeLabel(near, between, area))
        assertEquals("ca. Grainau", estimate(t(14, 13, 45))!!.placeLabel(near, between, area))
        assertEquals("Raum Lissabon", estimate(t(16, 20, 30))!!.placeLabel(near, between, area))
    }

    @Test
    fun `country only when unambiguous`() {
        assertNull(estimate(t(14, 15, 10))!!.country) // Germany to Austria
        assertEquals("Portugal", estimate(t(16, 20, 30))!!.country)
    }

    @Test
    fun `night overlap counts only the hours between 22 and 7`() {
        assertEquals(0.0, LocationEstimator.nightOverlapHours(t(14, 8, 0), t(14, 20, 0)), 1e-9)
        assertEquals(9.0, LocationEstimator.nightOverlapHours(t(14, 20, 0), t(15, 8, 0)), 1e-9)
        assertEquals(1.0, LocationEstimator.nightOverlapHours(t(15, 6, 0), t(15, 12, 0)), 1e-9)
    }

    @Test
    fun `all example photos with a known position nearby get an estimate within its radius`() {
        val cameraPhotos = listOf(
            Triple(t(14, 9, 30), 47.687, 11.198),
            Triple(t(14, 12, 5), 47.4211, 10.9853),
            Triple(t(14, 13, 40), 47.4585, 10.981),
            Triple(t(14, 15, 10), 47.329, 11.188),
            Triple(t(15, 16, 30), 46.395, 11.262),
            Triple(t(16, 18, 5), 38.7742, -9.1342),
            Triple(t(16, 20, 30), 38.711, -9.14)
        )
        for ((time, lat, lon) in cameraPhotos) {
            val e = estimate(time)
            assertNotNull("estimate at $time", e)
            assertTrue("error ${errorKm(e!!, lat, lon)} km > radius ${e.radiusKm} at $time", errorKm(e, lat, lon) <= e.radiusKm)
        }
    }
}
