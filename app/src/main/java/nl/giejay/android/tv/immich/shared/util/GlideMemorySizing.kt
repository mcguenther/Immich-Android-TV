package nl.giejay.android.tv.immich.shared.util

/**
 * Sizes Glide's memory cache for the slideshow. Pure function without Android dependencies,
 * so the rules are unit tested; [nl.giejay.android.tv.immich.shared.CustomGlideModule] feeds it
 * the screen size, heap class and preferences.
 */
object GlideMemorySizing {
    /** Bytes per pixel of a decoded ARGB_8888 bitmap. */
    private const val BYTES_PER_PIXEL = 4

    /** Slides are decoded slightly larger than the screen so they stay sharp while panning and zooming. */
    private const val SCREEN_MARGIN_PERCENT = 115L

    /** Bitmaps kept besides the prefetched ones: the one on screen, the previous one and one spare. */
    private const val EXTRA_BITMAPS = 3

    /** The cache may use at most this fraction of the app heap. */
    private const val HEAP_DIVISOR = 5

    private const val BYTES_PER_MB = 1024L * 1024L

    /** Bytes of one full-screen decoded slide, including the margin. Integer math keeps the result exact. */
    fun fullScreenBitmapBytes(screenWidth: Int, screenHeight: Int): Long {
        val width = screenWidth.toLong() * SCREEN_MARGIN_PERCENT / 100
        val height = screenHeight.toLong() * SCREEN_MARGIN_PERCENT / 100
        return width * height * BYTES_PER_PIXEL
    }

    /**
     * Size in bytes for the memory cache and bitmap pool: room for the prefetched slides plus
     * [EXTRA_BITMAPS], capped at [HEAP_DIVISOR]th of [heapMb].
     *
     * Returns null when Glide's default should stay: low-RAM devices, unknown screen size or heap size.
     */
    fun memoryCacheBytes(
        screenWidth: Int,
        screenHeight: Int,
        prefetchCount: Int,
        heapMb: Int,
        isLowRamDevice: Boolean
    ): Long? {
        if (isLowRamDevice || screenWidth <= 0 || screenHeight <= 0 || heapMb <= 0) return null
        val bitmaps = prefetchCount.coerceAtLeast(0) + EXTRA_BITMAPS
        val wanted = bitmaps * fullScreenBitmapBytes(screenWidth, screenHeight)
        val heapCap = heapMb * BYTES_PER_MB / HEAP_DIVISOR
        return minOf(wanted, heapCap)
    }
}
