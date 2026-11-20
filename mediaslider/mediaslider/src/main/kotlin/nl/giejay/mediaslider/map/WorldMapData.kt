package nl.giejay.mediaslider.map

import android.content.Context
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Offline world outlines (Natural Earth 1:50m land polygons and country borders, public domain),
 * stored as Web-Mercator world units in `[0, 2^scaleBits)`.
 *
 * Each shape is an [IntArray] of absolute `x0, y0, x1, y1, …` coordinates. Land rings are closed
 * implicitly and are meant to be filled with the even-odd rule (holes are separate rings).
 *
 * The asset is produced by `tools/natural-earth/build_map_asset.py`.
 */
class WorldMapData(
    val scaleBits: Int,
    val land: List<IntArray>,
    val borders: List<IntArray>
) {
    /** World size in units: coordinates are in `[0, worldUnits)`. */
    val worldUnits: Int = 1 shl scaleBits

    /** `minX, minY, maxX, maxY` per land shape. */
    val landBounds: IntArray = bounds(land)

    /** `minX, minY, maxX, maxY` per border line. */
    val borderBounds: IntArray = bounds(borders)

    companion object {
        const val ASSET_PATH = "naturalearth/ne_50m.bin"
        private val MAGIC = byteArrayOf('N'.code.toByte(), 'E'.code.toByte(), 'M'.code.toByte(), '1'.code.toByte())

        @Volatile
        private var cached: WorldMapData? = null

        /** Loads (once) and caches the bundled world map. Call off the main thread. */
        fun load(context: Context): WorldMapData {
            cached?.let { return it }
            return synchronized(this) {
                cached ?: context.applicationContext.assets.open(ASSET_PATH).use { read(it) }.also { cached = it }
            }
        }

        /** Decodes the gzip-compressed binary format written by `build_map_asset.py`. */
        fun read(input: InputStream): WorldMapData {
            val stream = BufferedInputStream(GZIPInputStream(input), 64 * 1024)
            val magic = ByteArray(4)
            if (stream.readNBytesCompat(magic) != 4 || !magic.contentEquals(MAGIC)) {
                throw IOException("Not a world map asset")
            }
            val scaleBits = stream.readVarint().toInt()
            if (scaleBits !in 8..30) throw IOException("Unsupported scale $scaleBits")
            val land = stream.readSection()
            val borders = stream.readSection()
            return WorldMapData(scaleBits, land, borders)
        }

        private fun InputStream.readSection(): List<IntArray> {
            val count = readVarint().toInt()
            return List(count) {
                val points = readVarint().toInt()
                val coords = IntArray(points * 2)
                var x = 0
                var y = 0
                for (i in 0 until points) {
                    x += readZigZag()
                    y += readZigZag()
                    coords[i * 2] = x
                    coords[i * 2 + 1] = y
                }
                coords
            }
        }

        private fun InputStream.readZigZag(): Int {
            val raw = readVarint()
            return ((raw ushr 1) xor -(raw and 1)).toInt()
        }

        private fun InputStream.readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = read()
                if (b < 0) throw IOException("Unexpected end of world map asset")
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IOException("Malformed varint")
            }
        }

        private fun InputStream.readNBytesCompat(buffer: ByteArray): Int {
            var read = 0
            while (read < buffer.size) {
                val n = read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            return read
        }

        private fun bounds(shapes: List<IntArray>): IntArray {
            val out = IntArray(shapes.size * 4)
            shapes.forEachIndexed { index, coords ->
                var minX = Int.MAX_VALUE
                var minY = Int.MAX_VALUE
                var maxX = Int.MIN_VALUE
                var maxY = Int.MIN_VALUE
                var i = 0
                while (i < coords.size) {
                    val x = coords[i]
                    val y = coords[i + 1]
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    i += 2
                }
                out[index * 4] = minX
                out[index * 4 + 1] = minY
                out[index * 4 + 2] = maxX
                out[index * 4 + 3] = maxY
            }
            return out
        }
    }
}
