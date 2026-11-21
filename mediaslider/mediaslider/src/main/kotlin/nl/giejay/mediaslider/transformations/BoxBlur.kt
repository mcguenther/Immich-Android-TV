package nl.giejay.mediaslider.transformations

/**
 * Separable box blur on ARGB pixels. Three passes approximate a Gaussian blur closely, which keeps
 * the result free of blocky artefacts when the (small) bitmap is scaled up to full screen.
 * Pure Kotlin so it runs on every API level and can be unit tested without Android.
 */
internal object BoxBlur {

    /** Blurs [pixels] (row-major, [width] x [height]) in place with the given [radius] and [passes]. */
    fun blur(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int = 3) {
        require(pixels.size >= width * height) { "pixel array too small" }
        if (radius < 1 || width < 2 || height < 2) return
        val tmp = IntArray(width * height)
        repeat(passes) {
            pass(pixels, tmp, width, height, radius, horizontal = true)
            pass(tmp, pixels, width, height, radius, horizontal = false)
        }
    }

    /** One box pass from [src] to [dst] along rows (horizontal) or columns; edges are clamped. */
    private fun pass(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val window = radius * 2 + 1
        for (line in 0 until lines) {
            fun index(pos: Int): Int {
                val p = pos.coerceIn(0, length - 1)
                return if (horizontal) line * width + p else p * width + line
            }
            var a = 0; var r = 0; var g = 0; var b = 0
            for (k in -radius..radius) {
                val c = src[index(k)]
                a += c ushr 24; r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            for (pos in 0 until length) {
                dst[index(pos)] = ((a / window) shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val out = src[index(pos - radius)]
                val inc = src[index(pos + radius + 1)]
                a += (inc ushr 24) - (out ushr 24)
                r += ((inc shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((inc shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                b += (inc and 0xFF) - (out and 0xFF)
            }
        }
    }
}
