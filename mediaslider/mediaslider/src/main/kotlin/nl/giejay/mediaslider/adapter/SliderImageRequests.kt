package nl.giejay.mediaslider.adapter

import android.content.Context
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import nl.giejay.mediaslider.config.LoadingOptions
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.SliderItem
import nl.giejay.mediaslider.transformations.BlurDarkenTransformation
import nl.giejay.mediaslider.view.FitCalculator
import kotlin.math.roundToInt

/**
 * The one place that builds Glide requests for slider photos, so showing a slide and preloading it
 * (`request(...).preload()`) use exactly the same URL, size and options and hit the same cache entry.
 */
object SliderImageRequests {
    /** Decode this much larger than the covering size so slow zoom/pan motion has pixels to spare. */
    const val MOTION_HEADROOM = 1.15f

    /** Longest edge (px) ever requested from Glide, so a 4K-reporting display does not decode ~4400 px bitmaps. */
    const val MAX_DECODE_LONG_EDGE = 2880

    /**
     * Long-edge cap for a fitted panorama that is scrolled (see [panoramaDecodeSize]): such a photo is
     * shown scaled up until it covers the slot's short axis, so its long edge is several screens wide.
     */
    const val PANORAMA_MAX_DECODE_LONG_EDGE = 4096

    /** Width in pixels of the blurred edge-fill source before [BlurDarkenTransformation] shrinks it. */
    private const val BACKDROP_DECODE_WIDTH = 112

    /**
     * Request for [item] shown in a [slotW] x [slotH] slot (full page, or one half of a portrait pair).
     * Deterministic for an item and slot. The photo is never cropped here: "cover" is achieved by
     * laying the whole image out larger than the slot.
     *
     * Decode size: covering items use the cover size x [MOTION_HEADROOM] with CENTER_OUTSIDE, fitted
     * items the slot size with CENTER_INSIDE, items of unknown size slot x [MOTION_HEADROOM] with
     * CENTER_OUTSIDE. The long edge is capped at
     * [MAX_DECODE_LONG_EDGE]. Fitted panoramas with scrolling enabled are decoded at the scaled-up size
     * ([panoramaDecodeSize]) with the long edge capped at [PANORAMA_MAX_DECODE_LONG_EDGE]. The original is used when [LoadingOptions.useFullResolution] (with the
     * thumbnail shown first as a placeholder, and as the fallback if the original cannot be decoded),
     * otherwise just the thumbnail.
     */
    fun request(context: Context, config: MediaSliderConfiguration, item: SliderItem, slotW: Int, slotH: Int): RequestBuilder<Drawable> {
        val w = item.width
        val h = item.height
        val target: Triple<Int, Int, DownsampleStrategy> = if (w != null && h != null && w > 0 && h > 0) {
            val fit = FitCalculator.compute(w, h, slotW, slotH, config.display)
            if (fit.fills) {
                Triple(scaled(fit.displayW), scaled(fit.displayH), DownsampleStrategy.CENTER_OUTSIDE)
            } else {
                Triple(fit.displayW, fit.displayH, DownsampleStrategy.CENTER_INSIDE)
            }
        } else {
            Triple(scaled(slotW), scaled(slotH), DownsampleStrategy.CENTER_OUTSIDE)
        }
        val (rawW, rawH, strategy) = target
        val panorama = item.isPanorama && config.motion.enabled && config.motion.panoramaScroll
        val (tw, th) = if (panorama && w != null && h != null && w > 0 && h > 0) {
            // The scrolling panorama is scaled up by the animator; decode at that size so it stays sharp.
            val fit = FitCalculator.compute(w, h, slotW, slotH, config.display)
            panoramaDecodeSize(fit, slotW, slotH)
                ?.let { capLongEdge(it.first, it.second, PANORAMA_MAX_DECODE_LONG_EDGE) }
                ?: capLongEdge(rawW, rawH, MAX_DECODE_LONG_EDGE)
        } else {
            capLongEdge(rawW, rawH, MAX_DECODE_LONG_EDGE)
        }
        val full = config.loading.useFullResolution
        var builder = Glide.with(context)
            .load(if (full) item.url else item.thumbnailUrl)
            .override(tw, th)
            .downsample(strategy)
            // Without this, `into(ImageView)` would add a scale-type transformation (and its own downsample
            // strategy) that a plain `preload()` does not have, so both would use different cache entries.
            .dontTransform()
        if (full) {
            val thumb = Glide.with(context)
                .load(item.thumbnailUrl)
                .override(tw, th)
                .downsample(strategy)
            builder = builder.thumbnail(thumb)
            // An original the TV cannot decode (HEIC, RAW) must not leave the slide empty.
            if (item.thumbnailUrl != null) builder = builder.error(thumb)
        }
        return builder
    }

    /**
     * Small, blurred and darkened version of the thumbnail for the edge fill behind fitted photos
     * and videos. Show it in an image view with centerCrop.
     */
    fun blurredBackdrop(context: Context, thumbnailUrl: String?): RequestBuilder<Drawable> =
        Glide.with(context)
            .load(thumbnailUrl)
            .override(BACKDROP_DECODE_WIDTH, BACKDROP_DECODE_WIDTH)
            .downsample(DownsampleStrategy.CENTER_OUTSIDE)
            .transform(BlurDarkenTransformation())

    /**
     * Size a fitted (non-covering) panorama must be decoded at to stay sharp while it scrolls: the fitted
     * size scaled by the factor `KenBurnsPlanner.planPanorama` uses (it covers the slot on its short
     * axis). Null for covering results, which are already decoded at cover size.
     */
    fun panoramaDecodeSize(fit: nl.giejay.mediaslider.view.FitResult, slotW: Int, slotH: Int): Pair<Int, Int>? {
        if (fit.fills || fit.displayW <= 0 || fit.displayH <= 0) return null
        val scale = maxOf(1f, slotW.toFloat() / fit.displayW, slotH.toFloat() / fit.displayH)
        return (fit.displayW * scale).roundToInt().coerceAtLeast(1) to (fit.displayH * scale).roundToInt().coerceAtLeast(1)
    }

    /** Scales [w] x [h] down, keeping the aspect ratio, so that the longer side is at most [max]. */
    fun capLongEdge(w: Int, h: Int, max: Int): Pair<Int, Int> {
        val longEdge = maxOf(w, h)
        if (longEdge <= max) return w to h
        val f = max.toDouble() / longEdge
        return (w * f).roundToInt().coerceAtLeast(1) to (h * f).roundToInt().coerceAtLeast(1)
    }

    private fun scaled(px: Int): Int = (px * MOTION_HEADROOM).roundToInt().coerceAtLeast(1)
}
