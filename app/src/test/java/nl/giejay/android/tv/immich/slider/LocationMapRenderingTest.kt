package nl.giejay.android.tv.immich.slider

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import nl.giejay.mediaslider.map.LocationMapRenderer
import nl.giejay.mediaslider.map.MapViewport
import nl.giejay.mediaslider.map.WorldMapData
import nl.giejay.mediaslider.model.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the slideshow location map with real Android graphics (Robolectric native mode)
 * and checks the silhouette style: white dot, half-transparent land, transparent water, soft edges.
 *
 * Set LOCATION_MAP_PREVIEW_DIR to also write the rendered maps as PNG files for a visual check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class LocationMapRenderingTest {

    private val density = 2f
    private val data: WorldMapData by lazy {
        File("../mediaslider/mediaslider/src/main/assets/${WorldMapData.ASSET_PATH}").inputStream().use { WorldMapData.read(it) }
    }

    private val mallorca = GeoLocation(39.611, 3.283)

    private fun render(location: GeoLocation, name: String): Bitmap =
        LocationMapRenderer.render(data, location, density).also { bitmap ->
            System.getenv("LOCATION_MAP_PREVIEW_DIR")?.let { dir ->
                File(dir).mkdirs()
                File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }

    /** Pixel position of [point] in a map centred (anchored) on [center]. */
    private fun pixelOf(center: GeoLocation, point: GeoLocation, bitmap: Bitmap): Pair<Int, Int> {
        val world = 256.0 * density * (1 shl LocationMapRenderer.ZOOM)
        val vp = MapViewport.anchoredAt(center, world, bitmap.width, bitmap.height, LocationMapRenderer.ANCHOR_X, LocationMapRenderer.ANCHOR_Y)
        val x = MapViewport.projectX(point.longitude, world) - vp.left
        val y = MapViewport.projectY(point.latitude, world) - vp.top
        return x.roundToInt() to y.roundToInt()
    }

    @Test
    fun `map has the expected size and an opaque white dot on the location`() {
        val bitmap = render(mallorca, "mallorca")

        assertEquals(648, bitmap.width)
        assertEquals(486, bitmap.height)
        val dot = bitmap.getPixel((0.7f * 648).roundToInt(), (0.45f * 486).roundToInt())
        assertEquals(Color.WHITE, dot)
    }

    @Test
    fun `land is a half transparent white silhouette and water stays transparent`() {
        val bitmap = render(mallorca, "mallorca-land-water")

        val (lx, ly) = pixelOf(mallorca, GeoLocation(39.2, -0.8), bitmap) // inland Valencia region
        val land = bitmap.getPixel(lx, ly)
        assertTrue("land alpha ${Color.alpha(land)}", Color.alpha(land) in 70..150)
        assertTrue("land is white", Color.red(land) > 240 && Color.green(land) > 240 && Color.blue(land) > 240)

        val (wx, wy) = pixelOf(mallorca, GeoLocation(39.0, 5.5), bitmap) // open sea east of Mallorca
        assertEquals("water alpha", 0, Color.alpha(bitmap.getPixel(wx, wy)))
    }

    @Test
    fun `edges fade out completely`() {
        val bitmap = render(GeoLocation(47.562, 13.649), "hallstatt") // inland: map is mostly land
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
        assertEquals(0, Color.alpha(bitmap.getPixel(2, bitmap.height - 3)))
        assertEquals(0, Color.alpha(bitmap.getPixel(bitmap.width - 3, 2)))
    }

    @Test
    fun `renders across the antimeridian without crashing`() {
        val bitmap = render(GeoLocation(-16.5, 179.9), "fiji")
        assertEquals(Color.WHITE, bitmap.getPixel((0.7f * 648).roundToInt(), (0.45f * 486).roundToInt()))
    }

    @Test
    fun `renders far north and far east`() {
        listOf("moskenes" to GeoLocation(67.933, 13.03), "shirahama" to GeoLocation(33.678, 135.348)).forEach { (name, place) ->
            val bitmap = render(place, name)
            assertEquals(name, Color.WHITE, bitmap.getPixel((0.7f * 648).roundToInt(), (0.45f * 486).roundToInt()))
        }
    }
}
