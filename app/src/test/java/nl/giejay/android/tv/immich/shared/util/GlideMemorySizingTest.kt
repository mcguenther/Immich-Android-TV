package nl.giejay.android.tv.immich.shared.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers the memory cache sizing for the slideshow prefetch. Pure JVM.
 */
class GlideMemorySizingTest {

    // 1920x1080 with the 15% margin: 2208 x 1242 pixels, 4 bytes each
    private val fullHdBitmap = 2208L * 1242L * 4L

    @Test
    fun `full screen bitmap includes the 15 percent margin`() {
        assertThat(GlideMemorySizing.fullScreenBitmapBytes(1920, 1080)).isEqualTo(fullHdBitmap)
        assertThat(GlideMemorySizing.fullScreenBitmapBytes(1920, 1080)).isEqualTo(10_969_344L)
    }

    @Test
    fun `normal device gets room for prefetched slides plus three bitmaps`() {
        // default prefetch of 3 -> 6 full screen bitmaps, heap 512 MB is far above that
        val bytes = GlideMemorySizing.memoryCacheBytes(
            screenWidth = 1920, screenHeight = 1080, prefetchCount = 3, heapMb = 512, isLowRamDevice = false
        )

        assertThat(bytes).isEqualTo(6 * fullHdBitmap)
    }

    @Test
    fun `small heap caps the cache at one fifth of the heap`() {
        // 256 MB heap -> cap of 256 MB / 5, below the 6 bitmaps wanted
        val bytes = GlideMemorySizing.memoryCacheBytes(
            screenWidth = 1920, screenHeight = 1080, prefetchCount = 3, heapMb = 256, isLowRamDevice = false
        )

        assertThat(bytes).isEqualTo(256L * 1024L * 1024L / 5)
        assertThat(bytes!!).isLessThan(6 * fullHdBitmap)
    }

    @Test
    fun `low ram device keeps Glide default`() {
        val bytes = GlideMemorySizing.memoryCacheBytes(
            screenWidth = 1920, screenHeight = 1080, prefetchCount = 3, heapMb = 512, isLowRamDevice = true
        )

        assertThat(bytes).isNull()
    }

    @Test
    fun `prefetch zero still keeps the three extra bitmaps`() {
        val bytes = GlideMemorySizing.memoryCacheBytes(
            screenWidth = 1920, screenHeight = 1080, prefetchCount = 0, heapMb = 512, isLowRamDevice = false
        )

        assertThat(bytes).isEqualTo(3 * fullHdBitmap)
    }

    @Test
    fun `negative prefetch count is treated as zero`() {
        val negative = GlideMemorySizing.memoryCacheBytes(1920, 1080, -5, 512, false)
        val zero = GlideMemorySizing.memoryCacheBytes(1920, 1080, 0, 512, false)

        assertThat(negative).isEqualTo(zero)
    }

    @Test
    fun `unknown screen or heap size keeps Glide default`() {
        assertThat(GlideMemorySizing.memoryCacheBytes(0, 1080, 3, 512, false)).isNull()
        assertThat(GlideMemorySizing.memoryCacheBytes(1920, 0, 3, 512, false)).isNull()
        assertThat(GlideMemorySizing.memoryCacheBytes(1920, 1080, 3, 0, false)).isNull()
    }

    @Test
    fun `large prefetch count on a 4K screen is capped by the heap`() {
        val bytes = GlideMemorySizing.memoryCacheBytes(
            screenWidth = 3840, screenHeight = 2160, prefetchCount = 10, heapMb = 512, isLowRamDevice = false
        )

        assertThat(bytes).isEqualTo(512L * 1024L * 1024L / 5)
    }
}
