package nl.giejay.mediaslider.map

import nl.giejay.mediaslider.model.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapViewportTest {

    @Test
    fun `projects like a standard web map`() {
        val world = 512.0
        assertEquals(256.0, MapViewport.projectX(0.0, world), 1e-9)
        assertEquals(256.0, MapViewport.projectY(0.0, world), 1e-9)
        assertEquals(0.0, MapViewport.projectX(-180.0, world), 1e-9)
        assertEquals(512.0, MapViewport.projectX(180.0, world), 1e-9)
        // tile y of Berlin at zoom 10 (256 px tiles) is 335
        assertEquals(335, (MapViewport.projectY(52.52, 256.0 * 1024) / 256).toInt())
        // poles are clamped instead of going to infinity
        assertEquals(0.0, MapViewport.projectY(90.0, world), 1e-6)
        assertEquals(world, MapViewport.projectY(-90.0, world), 1e-6)
    }

    @Test
    fun `anchors the location at the requested fraction of the window`() {
        val vp = MapViewport.anchoredAt(GeoLocation(0.0, 0.0), 1000.0, 200, 100, 0.7f, 0.45f)
        assertEquals(500.0 - 140.0, vp.left, 1e-4)
        assertEquals(500.0 - 45.0, vp.top, 1e-4)
    }

    @Test
    fun `culls shapes outside the window and projects visible ones into window pixels`() {
        val units = 1000
        val inside = intArrayOf(500, 500, 520, 500, 520, 520)
        val outside = intArrayOf(10, 10, 20, 10, 20, 20)
        val shapes = listOf(inside, outside)
        val bounds = intArrayOf(500, 500, 520, 520, 10, 10, 20, 20)
        // world of 1000 px => scale 1 px per unit; window 100x100 around (500, 500)
        val vp = MapViewport(units.toDouble(), 450.0, 450.0, 100, 100)

        val result = vp.visibleShapes(shapes, bounds, 1.0, 0.0, 0f)

        assertEquals(1, result.size)
        assertEquals(listOf(50f, 50f, 70f, 50f, 70f, 70f), result.single().toList())
    }

    @Test
    fun `draws shapes across the antimeridian from the neighbouring world copy`() {
        // a shape at the far west edge of the world, window at the far east edge
        val shape = intArrayOf(0, 500, 30, 500, 30, 530)
        val vp = MapViewport(1000.0, 950.0, 450.0, 100, 100)

        val result = vp.visibleShapes(listOf(shape), intArrayOf(0, 500, 30, 530), 1.0, 0.0, 0f)

        assertEquals(1, result.size)
        assertEquals(50f, result.single()[0], 1e-4f) // 1000 + 0 - 950
    }

    @Test
    fun `drops points closer than the minimum step but keeps the last point`() {
        val shape = intArrayOf(0, 0, 1, 0, 2, 0, 10, 0, 11, 0)
        val vp = MapViewport(1000.0, 0.0, 0.0, 100, 100)

        val result = vp.visibleShapes(listOf(shape), intArrayOf(0, 0, 11, 0), 1.0, 0.0, 5f).single()

        assertEquals(listOf(0f, 0f, 10f, 0f, 11f, 0f), result.toList())
        assertTrue(result.size < shape.size)
    }
}
