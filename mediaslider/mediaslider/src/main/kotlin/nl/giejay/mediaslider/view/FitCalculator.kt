package nl.giejay.mediaslider.view

import nl.giejay.mediaslider.config.DisplayOptions
import nl.giejay.mediaslider.config.FillMode
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Outcome of [FitCalculator.compute].
 *
 * @property displayW width in pixels the asset is laid out at. Larger than the slot when [fills].
 * @property displayH height in pixels the asset is laid out at. Larger than the slot when [fills].
 * @property fills true when the asset covers its whole slot (the overflow is clipped by the slot),
 * false when it is fitted (contained) and the slot shows edge fill next to it.
 * @property cropFraction share (0..1) of one image edge that covering the slot would cut away.
 * Reported for FIT results too, then it is what FILL would have cost.
 */
data class FitResult(val displayW: Int, val displayH: Int, val fills: Boolean, val cropFraction: Float)

/** Pure sizing rules for photos and videos inside a slot (full screen or half for portrait pairs). */
object FitCalculator {
    /** Gap between the two halves of a portrait pair, as a share of the page width. */
    const val PAIR_GAP_FRACTION = 0.006f

    private const val EPSILON = 1e-6

    /**
     * Decides whether an [imageW] x [imageH] asset covers or is fitted into a [slotW] x [slotH] slot.
     *
     * crop = 1 - min(ar / slotAr, slotAr / ar). With [FillMode.FILL] the asset covers the slot when
     * `crop * 100 <= display.maxCropPercent` ([DisplayOptions.UNLIMITED_CROP] always covers), else it
     * is fitted. [FillMode.FIT] always fits. Videos are fitted when portrait (height > width) or when
     * [DisplayOptions.fillVideos] is off. Invalid sizes yield a fitted result at slot size.
     */
    fun compute(imageW: Int, imageH: Int, slotW: Int, slotH: Int, display: DisplayOptions, isVideo: Boolean = false): FitResult {
        if (imageW <= 0 || imageH <= 0 || slotW <= 0 || slotH <= 0) {
            return FitResult(max(slotW, 0), max(slotH, 0), false, 0f)
        }
        val ar = imageW.toDouble() / imageH
        val slotAr = slotW.toDouble() / slotH
        val crop = 1.0 - min(ar / slotAr, slotAr / ar)
        val allowed = display.fillMode == FillMode.FILL &&
            (!isVideo || (display.fillVideos && imageH <= imageW)) &&
            (display.maxCropPercent >= DisplayOptions.UNLIMITED_CROP || crop * 100.0 <= display.maxCropPercent + EPSILON)
        return if (allowed) {
            val scale = max(slotW.toDouble() / imageW, slotH.toDouble() / imageH)
            FitResult(
                max(slotW, ceil(imageW * scale - EPSILON).toInt()),
                max(slotH, ceil(imageH * scale - EPSILON).toInt()),
                true,
                crop.toFloat()
            )
        } else {
            val scale = min(slotW.toDouble() / imageW, slotH.toDouble() / imageH)
            FitResult(
                min(slotW, (imageW * scale).roundToInt()).coerceAtLeast(1),
                min(slotH, (imageH * scale).roundToInt()).coerceAtLeast(1),
                false,
                crop.toFloat()
            )
        }
    }

    /** Gap in pixels between the halves of a pair on a page [pageWidth] pixels wide. */
    fun pairGap(pageWidth: Int): Int = (pageWidth * PAIR_GAP_FRACTION).roundToInt()

    /** Width of one slot: the whole page, or one half minus the gap for a portrait pair. */
    fun slotWidth(pageWidth: Int, paired: Boolean): Int =
        if (paired) (pageWidth - pairGap(pageWidth)) / 2 else pageWidth
}
