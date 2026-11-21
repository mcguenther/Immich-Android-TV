package nl.giejay.android.tv.immich.slider

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import nl.giejay.mediaslider.map.LocationMapRenderer
import nl.giejay.mediaslider.map.MapViewport
import nl.giejay.mediaslider.map.WorldMapData
import nl.giejay.mediaslider.model.GeoLocation
import nl.giejay.mediaslider.model.LocationPrecision
import nl.giejay.mediaslider.model.MapLocation
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

    private fun render(location: GeoLocation, name: String): Bitmap = render(MapLocation(location), name)

    private fun render(location: MapLocation, name: String): Bitmap =
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

    private val seefeld = GeoLocation(47.377, 11.156)
    private val centerX = (LocationMapRenderer.ANCHOR_X * LocationMapRenderer.widthPx(density)).roundToInt()
    private val centerY = (LocationMapRenderer.ANCHOR_Y * LocationMapRenderer.heightPx(density)).roundToInt()

    @Test
    fun `near estimate is a hollow ring, not a dot`() {
        val bitmap = render(MapLocation(seefeld, LocationPrecision.NEAR, 10.0), "estimate-near")
        assertTrue("centre is not solid white", bitmap.getPixel(centerX, centerY) != Color.WHITE)
        // ring radius 6 dp = 12 px at density 2
        assertEquals(Color.WHITE, bitmap.getPixel(centerX + 12, centerY))
    }

    @Test
    fun `corridor estimate marks both known positions and the ring`() {
        // Munich to Verona: long enough that the end points are not hidden under the ring at zoom 4.
        val munich = GeoLocation(48.137, 11.575)
        val verona = GeoLocation(45.438, 10.992)
        val middle = GeoLocation(46.79, 11.28)
        val bitmap = render(MapLocation(middle, LocationPrecision.CORRIDOR, 40.0, munich, verona), "estimate-corridor")
        for (end in listOf(munich, verona)) {
            val (x, y) = pixelOf(middle, end, bitmap)
            assertEquals("end point $end", Color.WHITE, bitmap.getPixel(x, y))
        }
        assertEquals(Color.WHITE, bitmap.getPixel(centerX + 12, centerY))
    }

    @Test
    fun `rough estimate is a soft cloud that fades out`() {
        val bitmap = render(MapLocation(seefeld, LocationPrecision.ROUGH, 60.0), "estimate-rough")
        val centre = bitmap.getPixel(centerX, centerY)
        assertTrue("bright centre", Color.alpha(centre) > 200 && Color.red(centre) > 240)
        assertTrue("not a solid dot", centre != Color.WHITE)
        // 60 km at zoom 4 and 47 degrees north is about 18 px at density 2; 30 px away the cloud is gone.
        assertTrue(Color.alpha(bitmap.getPixel(centerX + 30, centerY)) < Color.alpha(centre))
    }

    @Test
    fun `short corridor that would hide under the ring is drawn as the small cloud`() {
        // Eibsee to Innsbruck, 40 km: at zoom 4 both ends lie under the ring.
        val bitmap = render(
            MapLocation(seefeld, LocationPrecision.CORRIDOR, 25.0, GeoLocation(47.4566, 10.9726), GeoLocation(47.2692, 11.4041)),
            "estimate-corridor-short"
        )
        val centre = bitmap.getPixel(centerX, centerY)
        assertTrue("bright centre", Color.alpha(centre) > 200 && Color.red(centre) > 240)
        assertTrue("no ring", bitmap.getPixel(centerX + 12, centerY) != Color.WHITE)
    }
}
