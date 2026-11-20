package nl.giejay.mediaslider.view

import nl.giejay.mediaslider.config.DisplayOptions
import nl.giejay.mediaslider.config.FillMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FitCalculatorTest {
    private val sw = 1920
    private val sh = 1080
    private val half = FitCalculator.slotWidth(sw, true)
    private val d30 = DisplayOptions(fillMode = FillMode.FILL, maxCropPercent = 30)

    @Test
    fun fourThreeCoversFullSlotWithQuarterCrop() {
        val r = FitCalculator.compute(4000, 3000, sw, sh, d30)
        assertTrue(r.fills)
        assertEquals(0.25f, r.cropFraction, 0.001f)
        assertEquals(1920, r.displayW)
        assertEquals(1440, r.displayH)
    }

    @Test
    fun threeTwoCovers() {
        val r = FitCalculator.compute(6000, 4000, sw, sh, d30)
        assertTrue(r.fills)
        assertEquals(0.1563f, r.cropFraction, 0.001f)
        assertEquals(1920, r.displayW)
        assertEquals(1280, r.displayH)
    }

    @Test
    fun squareIsFittedAboveLimit() {
        val r = FitCalculator.compute(2000, 2000, sw, sh, d30)
        assertFalse(r.fills)
        assertEquals(0.4375f, r.cropFraction, 0.001f)
        assertEquals(1080, r.displayW)
        assertEquals(1080, r.displayH)
    }

    @Test
    fun portraitInFullSlotIsFitted() {
        val r = FitCalculator.compute(3000, 4000, sw, sh, d30)
        assertFalse(r.fills)
        assertEquals(810, r.displayW)
        assertEquals(1080, r.displayH)
    }

    @Test
    fun halfSlotPairsCoverPortraits() {
        assertEquals((1920 - 12) / 2, half)
        val r = FitCalculator.compute(3000, 4000, half, sh, d30)
        assertTrue(r.fills)
        assertTrue(r.displayW >= half && r.displayH >= sh)
        // 3:4 into 954x1080 (ar 0.883): crop = 1 - 0.75/0.883
        assertEquals(0.151f, r.cropFraction, 0.002f)
    }

    @Test
    fun squareInHalfSlotCovers() {
        val r = FitCalculator.compute(2000, 2000, half, sh, d30)
        assertTrue(r.fills)
        assertEquals(1080, r.displayH)
        assertEquals(1080, r.displayW)
    }

    @Test
    fun landscapeInHalfSlotIsFitted() {
        val r = FitCalculator.compute(4000, 3000, half, sh, d30)
        assertFalse(r.fills)
        assertEquals(half, r.displayW)
    }

    @Test
    fun limitIsInclusive() {
        assertTrue(FitCalculator.compute(4000, 3000, sw, sh, d30.copy(maxCropPercent = 25)).fills)
        assertFalse(FitCalculator.compute(4000, 3000, sw, sh, d30.copy(maxCropPercent = 24)).fills)
    }

    @Test
    fun unlimitedAlwaysCovers() {
        val unlimited = d30.copy(maxCropPercent = DisplayOptions.UNLIMITED_CROP)
        val r = FitCalculator.compute(3000, 4000, sw, sh, unlimited)
        assertTrue(r.fills)
        assertEquals(1920, r.displayW)
        assertEquals(2560, r.displayH)
        assertTrue(FitCalculator.compute(10000, 1000, sw, sh, unlimited).fills)
    }

    @Test
    fun fitModeNeverCovers() {
        val fit = d30.copy(fillMode = FillMode.FIT)
        val r = FitCalculator.compute(16, 9, sw, sh, fit)
        assertFalse(r.fills)
        assertEquals(sw, r.displayW)
        val r2 = FitCalculator.compute(4000, 3000, sw, sh, fit)
        assertFalse(r2.fills)
        assertEquals(1440, r2.displayW)
    }

    @Test
    fun portraitVideoNeverFills() {
        val unlimited = d30.copy(maxCropPercent = DisplayOptions.UNLIMITED_CROP)
        assertFalse(FitCalculator.compute(1080, 1920, sw, sh, unlimited, isVideo = true).fills)
        assertFalse(FitCalculator.compute(1080, 1920, 1080, 1920, unlimited, isVideo = true).fills)
    }

    @Test
    fun landscapeVideoFollowsFillVideos() {
        assertTrue(FitCalculator.compute(1440, 1080, sw, sh, d30, isVideo = true).fills)
        assertFalse(FitCalculator.compute(1440, 1080, sw, sh, d30.copy(fillVideos = false), isVideo = true).fills)
        assertFalse(FitCalculator.compute(1000, 1000, sw, sh, d30, isVideo = true).fills)
    }

    @Test
    fun invalidSizesAreFitted() {
        val r = FitCalculator.compute(0, 0, sw, sh, d30)
        assertFalse(r.fills)
        assertEquals(sw, r.displayW)
    }
}
