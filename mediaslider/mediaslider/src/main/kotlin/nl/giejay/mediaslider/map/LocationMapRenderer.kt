package nl.giejay.mediaslider.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import nl.giejay.mediaslider.model.GeoLocation
import kotlin.math.roundToInt

/**
 * Draws the slideshow location map: a country-level silhouette of the land (white, half transparent),
 * country borders, transparent water, soft edges that fade into the photo and a white dot on the location.
 */
object LocationMapRenderer {
    /** Size of the map in dp. It sits behind the bottom-right metadata block. */
    const val WIDTH_DP = 360
    const val HEIGHT_DP = 270

    /** Where the location dot sits inside the map (fractions of width/height). */
    const val ANCHOR_X = 0.7f
    const val ANCHOR_Y = 0.45f

    /** Web-map zoom level 4 with 256 dp tiles: roughly 1,000–1,500 km across, i.e. "which part of the country". */
    const val ZOOM = 4
    private const val TILE_DP = 256

    private const val LAND_LAYER_ALPHA = 0.5f
    private const val BORDER_ALPHA = 0.45f
    private const val BORDER_WIDTH_DP = 1f

    /** Soft edge: opaque up to [MASK_SOLID] of the ellipse radius, then fading to transparent. */
    private const val MASK_RADIUS = 0.44f
    private const val MASK_SOLID = 0.4f

    private const val DOT_RADIUS_DP = 4.5f
    private const val DOT_RING_DP = 2f
    private const val DOT_SHADOW_DP = 6f

    fun widthPx(density: Float): Int = (WIDTH_DP * density).roundToInt()
    fun heightPx(density: Float): Int = (HEIGHT_DP * density).roundToInt()

    fun render(data: WorldMapData, location: GeoLocation, density: Float): Bitmap {
        val width = widthPx(density)
        val height = heightPx(density)
        val worldSizePx = TILE_DP * density.toDouble() * (1 shl ZOOM)
        val viewport = MapViewport.anchoredAt(location, worldSizePx, width, height, ANCHOR_X, ANCHOR_Y)
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

        drawDot(canvas, ANCHOR_X * width, ANCHOR_Y * height, density)
        return bitmap
    }

    private fun applySoftEdges(canvas: Canvas, width: Int, height: Int) {
        val cx = ANCHOR_X * width
        val cy = ANCHOR_Y * height
        val rx = MASK_RADIUS * width
        val ry = MASK_RADIUS * height
        val gradient = RadialGradient(
            cx, cy, rx,
            intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
            floatArrayOf(0f, MASK_SOLID, 1f),
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
