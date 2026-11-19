package nl.giejay.mediaslider.transformations

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Cheap "blur" for the edge fill behind fitted photos and videos: the bitmap is shrunk (in halving
 * steps, which averages pixels) to [targetWidth] pixels wide and darkened by [darken]. The image view
 * showing the result scales it up with bilinear filtering, which gives a soft, blurred look. It
 * works on every API level and the result is tiny, so it costs next to no memory.
 */
class BlurDarkenTransformation(
    private val targetWidth: Int = DEFAULT_WIDTH,
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
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val shade = (255 * (1f - darken)).roundToInt().coerceIn(0, 255)
        paint.colorFilter = PorterDuffColorFilter(Color.rgb(shade, shade, shade), PorterDuff.Mode.MULTIPLY)
        Canvas(result).drawBitmap(current, Rect(0, 0, current.width, current.height), Rect(0, 0, w, h), paint)
        if (current !== toTransform) current.recycle()
        return result
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("$ID:$targetWidth:$darken".toByteArray(Charsets.UTF_8))
    }

    override fun equals(other: Any?): Boolean =
        other is BlurDarkenTransformation && other.targetWidth == targetWidth && other.darken == darken

    override fun hashCode(): Int = 31 * ID.hashCode() + 31 * targetWidth + darken.hashCode()

    companion object {
        private const val ID = "nl.giejay.mediaslider.BlurDarkenTransformation"
        const val DEFAULT_WIDTH = 28
        const val DEFAULT_DARKEN = 0.45f
    }
}
