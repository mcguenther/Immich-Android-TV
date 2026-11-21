package nl.giejay.mediaslider.transformations

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Soft, dark backdrop for the empty edges next to fitted photos and videos.
 *
 * The bitmap is shrunk (in halving steps, which averages pixels) to [targetWidth] pixels wide, then
 * blurred for real with a three-pass box blur of [radius] (≈ Gaussian) and darkened by [darken].
 * Shrinking alone is not enough: a tiny bitmap scaled up to full screen shows visible blocks. After
 * the blur the image is smooth, so scaling it up stays soft. The result is tiny (≈ 96 x 54 px), so
 * memory and CPU cost are negligible.
 */
class BlurDarkenTransformation(
    private val targetWidth: Int = DEFAULT_WIDTH,
    private val radius: Int = DEFAULT_RADIUS,
    private val darken: Float = DEFAULT_DARKEN
) : BitmapTransformation() {

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        var current = toTransform
        // Halve until close to the target, then do the last (<2x) step.
        while (current.width > targetWidth * 2) {
            val next = Bitmap.createScaledBitmap(current, current.width / 2, max(1, current.height / 2), true)
            if (current !== toTransform) current.recycle()
            current = next
        }
        val w = targetWidth.coerceAtMost(current.width)
        val h = max(1, (current.height * (w.toFloat() / current.width)).roundToInt())
        val result = pool.get(w, h, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(current, Rect(0, 0, current.width, current.height), Rect(0, 0, w, h),
            Paint(Paint.FILTER_BITMAP_FLAG))
        if (current !== toTransform) current.recycle()

        val pixels = IntArray(w * h)
        result.getPixels(pixels, 0, w, 0, 0, w, h)
        BoxBlur.blur(pixels, w, h, radius)
        darken(pixels, darken)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("$ID:$targetWidth:$radius:$darken".toByteArray(Charsets.UTF_8))
    }

    override fun equals(other: Any?): Boolean =
        other is BlurDarkenTransformation && other.targetWidth == targetWidth && other.radius == radius && other.darken == darken

    override fun hashCode(): Int = 31 * (31 * (31 * ID.hashCode() + targetWidth) + radius) + darken.hashCode()

    companion object {
        // v2: real blur instead of shrinking only (also changes the cache key, so old blocky results are not reused)
        private const val ID = "nl.giejay.mediaslider.BlurDarkenTransformation.v2"
        const val DEFAULT_WIDTH = 96
        const val DEFAULT_RADIUS = 6
        const val DEFAULT_DARKEN = 0.45f

        /** Multiplies the colour channels of opaque-ish pixels by (1 - [amount]). */
        internal fun darken(pixels: IntArray, amount: Float) {
            val keep = ((1f - amount) * 256).roundToInt().coerceIn(0, 256)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = (((c shr 16) and 0xFF) * keep) shr 8
                val g = (((c shr 8) and 0xFF) * keep) shr 8
                val b = ((c and 0xFF) * keep) shr 8
                pixels[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }
    }
}
