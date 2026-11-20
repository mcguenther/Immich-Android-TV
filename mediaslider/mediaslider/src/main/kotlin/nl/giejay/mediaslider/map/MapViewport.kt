package nl.giejay.mediaslider.map

import nl.giejay.mediaslider.model.GeoLocation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin

/**
 * A pixel window into a Web-Mercator world map. Pure math (no Android types) so it can be unit tested.
 *
 * @param worldSizePx width (and height) of the whole world in pixels at the current zoom
 * @param left world x (px) of the window's left edge
 * @param top world y (px) of the window's top edge
 */
class MapViewport(
    val worldSizePx: Double,
    val left: Double,
    val top: Double,
    val width: Int,
    val height: Int
) {
    companion object {
        /** Mercator is undefined at the poles; clamp like every web map does. */
        const val MAX_LATITUDE = 85.05112878

        fun projectX(longitude: Double, worldSizePx: Double): Double =
            (longitude + 180.0) / 360.0 * worldSizePx

        fun projectY(latitude: Double, worldSizePx: Double): Double {
            val s = sin(Math.toRadians(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)))
            return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * worldSizePx
        }

        /**
         * Places [location] at the fractional position ([anchorX], [anchorY]) of a [width] x [height] window.
         */
        fun anchoredAt(
            location: GeoLocation,
            worldSizePx: Double,
            width: Int,
            height: Int,
            anchorX: Float,
            anchorY: Float
        ): MapViewport {
            val x = projectX(location.longitude, worldSizePx)
            val y = projectY(location.latitude, worldSizePx)
            return MapViewport(worldSizePx, x - anchorX * width, y - anchorY * height, width, height)
        }
    }

    /**
     * Projects every shape whose bounds touch the window (plus [marginPx]) into window pixel
     * coordinates (`x0, y0, x1, y1, …`). Shapes near the antimeridian are emitted once per
     * visible world copy. Consecutive points closer than [minStepPx] are dropped, which keeps
     * paths small at low zoom without visible loss.
     *
     * @param scale pixels per world-map unit, i.e. `worldSizePx / WorldMapData.worldUnits`
     */
    fun visibleShapes(
        shapes: List<IntArray>,
        bounds: IntArray,
        scale: Double,
        marginPx: Double,
        minStepPx: Float
    ): List<FloatArray> {
        val result = mutableListOf<FloatArray>()
        val viewMinX = left - marginPx
        val viewMaxX = left + width + marginPx
        val viewMinY = top - marginPx
        val viewMaxY = top + height + marginPx
        for (i in shapes.indices) {
            val minY = bounds[i * 4 + 1] * scale
            val maxY = bounds[i * 4 + 3] * scale
            if (maxY < viewMinY || minY > viewMaxY) continue
            for (copy in -1..1) {
                val offset = copy * worldSizePx
                val minX = bounds[i * 4] * scale + offset
                val maxX = bounds[i * 4 + 2] * scale + offset
                if (maxX < viewMinX || minX > viewMaxX) continue
                project(shapes[i], scale, offset, minStepPx)?.let { result += it }
            }
        }
        return result
    }

    private fun project(coords: IntArray, scale: Double, offset: Double, minStepPx: Float): FloatArray? {
        val pointCount = coords.size / 2
        if (pointCount < 2) return null
        val out = FloatArray(coords.size)
        var n = 0
        var lastX = 0f
        var lastY = 0f
        for (p in 0 until pointCount) {
            val x = (coords[p * 2] * scale + offset - left).toFloat()
            val y = (coords[p * 2 + 1] * scale - top).toFloat()
            val isLast = p == pointCount - 1
            if (n == 0 || isLast || abs(x - lastX) >= minStepPx || abs(y - lastY) >= minStepPx) {
                out[n++] = x
                out[n++] = y
                lastX = x
                lastY = y
            }
        }
        return if (n >= 4) out.copyOf(n) else null
    }
}
