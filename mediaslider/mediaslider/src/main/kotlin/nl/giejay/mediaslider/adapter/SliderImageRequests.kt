package nl.giejay.mediaslider.adapter

import android.content.Context
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
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

    /** Width in pixels of the blurred edge-fill source before [BlurDarkenTransformation] shrinks it. */
    private const val BACKDROP_DECODE_WIDTH = 112

    /**
     * Request for [item] shown in a [slotW] x [slotH] slot (full page, or one half of a portrait pair).
     * Deterministic for an item and slot. The photo is never cropped here: "cover" is achieved by
     * laying the whole image out larger than the slot.
     *
     * Decode size: covering items use the cover size x [MOTION_HEADROOM] with CENTER_OUTSIDE, fitted
     * items the slot size with CENTER_INSIDE, items of unknown size slot x [MOTION_HEADROOM] with
     * CENTER_OUTSIDE. Unless [MediaSliderConfiguration.isOnlyUseThumbnails], the preview thumbnail
     * is requested first as a placeholder.
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
        val (tw, th, strategy) = target
        var builder = Glide.with(context)
            .load(if (config.isOnlyUseThumbnails) item.thumbnailUrl else item.url)
            .override(tw, th)
            .downsample(strategy)
            // Without this, `into(ImageView)` would add a scale-type transformation (and its own downsample
            // strategy) that a plain `preload()` does not have, so both would use different cache entries.
            .dontTransform()
        if (!config.isOnlyUseThumbnails) {
            builder = builder.thumbnail(
                Glide.with(context)
                    .load(item.thumbnailUrl)
                    .override(tw, th)
                    .downsample(strategy)
            )
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

    private fun scaled(px: Int): Int = (px * MOTION_HEADROOM).roundToInt().coerceAtLeast(1)
}
