package nl.giejay.mediaslider.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import nl.giejay.mediaslider.model.GeoLocation
import nl.giejay.mediaslider.model.LocationPrecision
import nl.giejay.mediaslider.model.MapLocation
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws the slideshow location map: a country-level silhouette of the land (white, half transparent),
 * country borders, transparent water, soft edges that fade into the photo and a mark on the location.
 *
 * The mark follows [MapLocation.precision]: a solid dot for GPS positions, and softer marks for estimates
 * (ring, dashed corridor with ring, soft cloud), so an estimate never looks like a measured position.
 */
object LocationMapRenderer {
    /** Size of the map in dp. It sits behind the bottom-right metadata block. */
    const val WIDTH_DP = 324
    const val HEIGHT_DP = 243

    /** Where the location dot sits inside the map (fractions of width/height). */
    const val ANCHOR_X = 0.7f
    const val ANCHOR_Y = 0.45f

    /** Web-map zoom level 4 with 256 dp tiles: roughly 1,000–1,500 km across, i.e. "which part of the country". */
    const val ZOOM = 4
    private const val TILE_DP = 256

    private const val LAND_LAYER_ALPHA = 0.5f
    private const val BORDER_ALPHA = 0.45f
    private const val BORDER_WIDTH_DP = 1f

    /**
     * Soft edge: the ellipse (radius [MASK_RADIUS] of width/height, centred on the dot) is opaque only up to
     * [MASK_SOLID] of its radius, then eases out (1 - smoothstep) to fully transparent at the ellipse edge.
     * The radius in y (0.44 * height) stays inside the distance to the top (0.45 * height) and bottom (0.55 * height);
     * only the right side may be clipped by the bitmap edge (it coincides with the screen edge).
     */
    private const val MASK_RADIUS = 0.44f
    private const val MASK_SOLID = 0.1f
    private const val MASK_STOPS = 9

    private const val DOT_RADIUS_DP = 4.5f
    private const val DOT_RING_DP = 2f
    private const val DOT_SHADOW_DP = 6f

    private const val RING_RADIUS_DP = 6f
    private const val RING_STROKE_DP = 2.2f
    private const val RING_HALO_DP = 4.2f
    private const val CORRIDOR_STROKE_DP = 1.6f
    private const val CORRIDOR_DASH_DP = 4f
    private const val CORRIDOR_GAP_DP = 3f
    private const val CORRIDOR_END_RADIUS_DP = 2.8f
    /** Smallest cloud, so a rough estimate never shrinks to something that looks like a dot. */
    private const val CLOUD_MIN_RADIUS_DP = 10f
    /**
     * A corridor end must lie at least this far outside the ring to be seen. Shorter corridors (at zoom 4
     * roughly under 100 km) would vanish under the ring, so they are drawn as the small cloud instead.
     */
    private const val CORRIDOR_MIN_VISIBLE_DP = 8f

    private const val EARTH_CIRCUMFERENCE_KM = 40075.0

    /** Positions closer than this on the map (about 7 km at zoom 4 and 45 degrees north) look the same. */
    private const val SAME_MAP_DP = 1.0

    fun widthPx(density: Float): Int = (WIDTH_DP * density).roundToInt()
    fun heightPx(density: Float): Int = (HEIGHT_DP * density).roundToInt()

    fun render(data: WorldMapData, location: GeoLocation, density: Float): Bitmap =
        render(data, MapLocation(location), density)

    fun render(data: WorldMapData, location: MapLocation, density: Float): Bitmap {
        val width = widthPx(density)
        val height = heightPx(density)
        val worldSizePx = TILE_DP * density.toDouble() * (1 shl ZOOM)
        val viewport = MapViewport.anchoredAt(location.position, worldSizePx, width, height, ANCHOR_X, ANCHOR_Y)
        val scale = worldSizePx / data.worldUnits
        val minStep = 0.75f * density

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val layer = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (LAND_LAYER_ALPHA * 255).roundToInt())

        val land = Path().apply { fillType = Path.FillType.EVEN_ODD }
        viewport.visibleShapes(data.land, data.landBounds, scale, 0.0, minStep).forEach { land.addPoints(it, close = true) }
        canvas.drawPath(land, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        })

        val borderWidth = BORDER_WIDTH_DP * density
        val borders = Path()
        viewport.visibleShapes(data.borders, data.borderBounds, scale, borderWidth.toDouble(), minStep)
            .forEach { borders.addPoints(it, close = false) }
        canvas.drawPath(borders, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = Color.argb((BORDER_ALPHA * 255).roundToInt(), 0, 0, 0)
        })

        applySoftEdges(canvas, width, height)
        canvas.restoreToCount(layer)

        val x = ANCHOR_X * width
        val y = ANCHOR_Y * height
        when (location.precision) {
            LocationPrecision.EXACT -> drawDot(canvas, x, y, density)
            LocationPrecision.NEAR -> drawRing(canvas, x, y, density)
            LocationPrecision.CORRIDOR -> {
                val start = location.corridorStart?.let { viewport.pixelOf(it) }
                val end = location.corridorEnd?.let { viewport.pixelOf(it) }
                if (start != null && end != null &&
                    isCorridorVisible(x, y, start.first, start.second, end.first, end.second, density)) {
                    drawCorridor(canvas, start.first, start.second, end.first, end.second, density)
                    drawRing(canvas, x, y, density)
                } else {
                    drawCloud(canvas, x, y, CLOUD_MIN_RADIUS_DP * density)
                }
            }
            LocationPrecision.ROUGH -> {
                val pxPerKm = worldSizePx / (EARTH_CIRCUMFERENCE_KM * cos(Math.toRadians(location.position.latitude)))
                drawCloud(canvas, x, y, max(location.radiusKm * pxPerKm, CLOUD_MIN_RADIUS_DP * density.toDouble()).toFloat())
            }
        }
        return bitmap
    }

    /**
     * True when at least one corridor end lies visibly outside the ring around ([x], [y]). Otherwise the
     * line would hide under the ring and the corridor would look like a near estimate.
     */
    internal fun isCorridorVisible(x: Float, y: Float, sx: Float, sy: Float, ex: Float, ey: Float, density: Float): Boolean {
        val minDistance = (RING_RADIUS_DP + CORRIDOR_MIN_VISIBLE_DP) * density
        return hypot(sx - x, sy - y) >= minDistance || hypot(ex - x, ey - y) >= minDistance
    }

    /**
     * True when [a] and [b] would be drawn as practically the same map: same kind of mark, and positions
     * (and for estimates their corridor ends and cloud size) less than [SAME_MAP_DP] apart on the map.
     * A slide at such a place keeps the map on screen instead of fading it out and in again.
     */
    fun isSameMap(a: MapLocation, b: MapLocation): Boolean {
        if (a.precision != b.precision) return false
        if (!isClose(a.position, b.position)) return false
        return when (a.precision) {
            LocationPrecision.EXACT, LocationPrecision.NEAR -> true
            LocationPrecision.ROUGH -> abs(a.radiusKm - b.radiusKm) <= 0.1 * max(a.radiusKm, b.radiusKm)
            LocationPrecision.CORRIDOR -> isClose(a.corridorStart, b.corridorStart) && isClose(a.corridorEnd, b.corridorEnd)
        }
    }

    private fun isClose(p: GeoLocation?, q: GeoLocation?): Boolean {
        if (p == null || q == null) return p == q
        val world = TILE_DP.toDouble() * (1 shl ZOOM)
        return hypot(
            MapViewport.projectX(p.longitude, world) - MapViewport.projectX(q.longitude, world),
            MapViewport.projectY(p.latitude, world) - MapViewport.projectY(q.latitude, world)
        ) <= SAME_MAP_DP
    }

    private fun MapViewport.pixelOf(location: GeoLocation): Pair<Float, Float> =
        (MapViewport.projectX(location.longitude, worldSizePx) - left).toFloat() to
            (MapViewport.projectY(location.latitude, worldSizePx) - top).toFloat()

    /** Hollow ring: "about here". A dark halo keeps it readable on bright photos. */
    private fun drawRing(canvas: Canvas, x: Float, y: Float, density: Float) {
        val radius = RING_RADIUS_DP * density
        canvas.drawCircle(x, y, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = RING_HALO_DP * density
            color = Color.argb(115, 0, 0, 0)
            setShadowLayer(5f * density, 0f, 0f, Color.argb(140, 0, 0, 0))
        })
        canvas.drawCircle(x, y, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = RING_STROKE_DP * density
            color = Color.WHITE
        })
    }

    /** Dashed line between the known positions before and after the photo, with small end points. */
    private fun drawCorridor(canvas: Canvas, sx: Float, sy: Float, ex: Float, ey: Float, density: Float) {
        canvas.drawLine(sx, sy, ex, ey, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = CORRIDOR_STROKE_DP * density
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(217, 255, 255, 255)
            pathEffect = DashPathEffect(floatArrayOf(CORRIDOR_DASH_DP * density, CORRIDOR_GAP_DP * density), 0f)
            setShadowLayer(3f * density, 0f, 0f, Color.argb(128, 0, 0, 0))
        })
        val radius = CORRIDOR_END_RADIUS_DP * density
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = Color.argb(140, 0, 0, 0)
        }
        for ((px, py) in listOf(sx to sy, ex to ey)) {
            canvas.drawCircle(px, py, radius, fill)
            canvas.drawCircle(px, py, radius, outline)
        }
    }

    /** Soft white cloud with the uncertainty radius, no hard edge and no centre mark. */
    private fun drawCloud(canvas: Canvas, x: Float, y: Float, radius: Float) {
        canvas.drawCircle(x, y, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                x, y, radius,
                intArrayOf(Color.argb(242, 255, 255, 255), Color.argb(166, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        })
    }

    private fun applySoftEdges(canvas: Canvas, width: Int, height: Int) {
        val cx = ANCHOR_X * width
        val cy = ANCHOR_Y * height
        val rx = MASK_RADIUS * width
        val ry = MASK_RADIUS * height
        // Stop 0 is the solid core; stops 1..MASK_STOPS-1 sample alpha = 1 - smoothstep(u) with u in 0..1
        // between MASK_SOLID and 1, giving a long, smooth falloff.
        val positions = FloatArray(MASK_STOPS + 1)
        val colors = IntArray(MASK_STOPS + 1)
        positions[0] = 0f
        colors[0] = Color.BLACK
        for (i in 0 until MASK_STOPS) {
            val u = i / (MASK_STOPS - 1).toFloat()
            positions[i + 1] = MASK_SOLID + (1f - MASK_SOLID) * u
            val alpha = 1f - u * u * (3f - 2f * u)
            colors[i + 1] = Color.argb((alpha * 255).roundToInt(), 0, 0, 0)
        }
        val gradient = RadialGradient(
            cx, cy, rx,
            colors,
            positions,
            Shader.TileMode.CLAMP
        ).apply { setLocalMatrix(Matrix().apply { setScale(1f, ry / rx, cx, cy) }) }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
            shader = gradient
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })
    }

    private fun drawDot(canvas: Canvas, x: Float, y: Float, density: Float) {
        val radius = DOT_RADIUS_DP * density
        canvas.drawCircle(x, y, radius + DOT_RING_DP * density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(140, 0, 0, 0)
            setShadowLayer(DOT_SHADOW_DP * density, 0f, 0f, Color.argb(153, 0, 0, 0))
        })
        canvas.drawCircle(x, y, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    }

    private fun Path.addPoints(points: FloatArray, close: Boolean) {
        moveTo(points[0], points[1])
        var i = 2
        while (i < points.size) {
            lineTo(points[i], points[i + 1])
            i += 2
        }
        if (close) close()
    }
}
