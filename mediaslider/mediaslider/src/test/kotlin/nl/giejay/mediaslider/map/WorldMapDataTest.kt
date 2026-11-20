package nl.giejay.mediaslider.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPOutputStream

class WorldMapDataTest {

    private fun bundledAsset(): WorldMapData =
        File("src/main/assets/${WorldMapData.ASSET_PATH}").inputStream().use { WorldMapData.read(it) }

    @Test
    fun `bundled Natural Earth asset decodes with sane coordinates`() {
        val data = bundledAsset()

        assertEquals(20, data.scaleBits)
        assertTrue("land rings: ${data.land.size}", data.land.size > 1000)
        assertTrue("border lines: ${data.borders.size}", data.borders.size > 300)
        (data.land + data.borders).forEach { shape ->
            assertTrue(shape.size % 2 == 0)
            shape.forEach { assertTrue(it in 0 until data.worldUnits) }
        }
        assertEquals(data.land.size * 4, data.landBounds.size)
        assertEquals(data.borders.size * 4, data.borderBounds.size)
    }

    @Test
    fun `bundled asset contains land at Mallorca and water west of Portugal`() {
        val data = bundledAsset()
        val size = data.worldUnits.toDouble()

        fun isLand(lat: Double, lon: Double): Boolean {
            val x = MapViewport.projectX(lon, size)
            val y = MapViewport.projectY(lat, size)
            // even-odd point-in-polygon over all land rings
            var inside = false
            data.land.forEach { ring ->
                var j = ring.size - 2
                var i = 0
                while (i < ring.size) {
                    val xi = ring[i].toDouble()
                    val yi = ring[i + 1].toDouble()
                    val xj = ring[j].toDouble()
                    val yj = ring[j + 1].toDouble()
                    if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                    j = i
                    i += 2
                }
            }
            return inside
        }

        assertTrue(isLand(39.61, 3.28)) // Sant Llorenç des Cardassar, Mallorca
        assertTrue(isLand(47.56, 13.65)) // Hallstatt
        assertTrue(!isLand(39.0, -12.0)) // Atlantic
        assertTrue(!isLand(39.0, 5.0)) // Mediterranean
    }

    @Test
    fun `decodes delta encoded shapes`() {
        // magic, scaleBits=8, one ring (3 points: (1,2) (3,2) (3,5)), one line (2 points: (10,10) (9,12))
        val raw = byteArrayOf(
            'N'.code.toByte(), 'E'.code.toByte(), 'M'.code.toByte(), '1'.code.toByte(), 8,
            1, 3, 2, 4, 4, 0, 0, 6,
            1, 2, 20, 20, 1, 4
        )
        val data = WorldMapData.read(ByteArrayInputStream(gzip(raw)))

        assertEquals(listOf(1, 2, 3, 2, 3, 5), data.land.single().toList())
        assertEquals(listOf(10, 10, 9, 12), data.borders.single().toList())
        assertEquals(listOf(1, 2, 3, 5), data.landBounds.toList())
    }

    @Test(expected = IOException::class)
    fun `rejects files without the magic header`() {
        WorldMapData.read(ByteArrayInputStream(gzip(byteArrayOf(1, 2, 3, 4, 5))))
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
}
