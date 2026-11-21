package nl.giejay.mediaslider.transformations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxBlurTest {

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun red(c: Int) = (c shr 16) and 0xFF

    @Test
    fun uniformImageStaysUniform() {
        val px = IntArray(20 * 10) { gray(120) }
        BoxBlur.blur(px, 20, 10, radius = 4)
        assertTrue(px.all { it == gray(120) })
    }

    @Test
    fun hardEdgeBecomesSmoothGradient() {
        val w = 40; val h = 4
        val px = IntArray(w * h) { i -> if (i % w < w / 2) gray(0) else gray(255) }
        BoxBlur.blur(px, w, h, radius = 4)
        val row = (0 until w).map { red(px[it]) }
        // monotonic, no big jumps between neighbours (no visible blocks)
        row.zipWithNext().forEach { (a, b) -> assertTrue("not monotonic: $row", b >= a) }
        assertTrue("step too large: $row", row.zipWithNext().maxOf { (a, b) -> b - a } <= 40)
        assertEquals(0, row.first())
        assertEquals(255, row.last())
    }

    @Test
    fun alphaIsKept() {
        val px = IntArray(10 * 10) { gray(200) }
        BoxBlur.blur(px, 10, 10, radius = 3)
        assertTrue(px.all { it ushr 24 == 0xFF })
    }

    @Test
    fun darkenScalesChannelsAndKeepsAlpha() {
        val px = intArrayOf(gray(200))
        BlurDarkenTransformation.darken(px, 0.5f)
        assertEquals(100, red(px[0]))
        assertEquals(0xFF, px[0] ushr 24)
    }

    @Test
    fun tinyImagesAreLeftAlone() {
        val px = intArrayOf(gray(10))
        BoxBlur.blur(px, 1, 1, radius = 5)
        assertEquals(gray(10), px[0])
    }
}
