package nl.giejay.mediaslider.view

import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.ViewPager
import kotlin.math.abs

/**
 * Crossfade between pager pages. Pages are pinned in place (the pager's own slide is cancelled)
 * and the incoming page fades in on top of the outgoing one, which stays opaque; fading both
 * would dip to dark in the middle.
 *
 * [SurfaceView] ignores alpha, so a page that contains one cannot fade: it is shown fully while
 * it is the nearer page (|position| < 0.5) and hidden otherwise, i.e. a hard cut at the midpoint.
 *
 * @param currentItem the pager's target item; it is the incoming page during a scroll
 */
class CrossfadePageTransformer(private val currentItem: () -> Int) : ViewPager.PageTransformer {

    override fun transformPage(page: View, position: Float) {
        val visible = abs(position) < 1f
        page.translationX = if (visible) pinnedTranslationX(position, page.width) else 0f
        val incoming = page.tag == "view${currentItem()}"
        page.translationZ = if (incoming) 1f else 0f
        if (containsSurfaceView(page)) {
            page.alpha = 1f
            page.visibility = if (abs(position) < 0.5f) View.VISIBLE else View.INVISIBLE
        } else {
            page.visibility = View.VISIBLE
            page.alpha = alphaFor(position, incoming)
        }
    }

    private fun containsSurfaceView(view: View): Boolean {
        if (view is SurfaceView) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) if (containsSurfaceView(view.getChildAt(i))) return true
        }
        return false
    }

    companion object {
        /** Cancels the pager's horizontal slide of a page at [position]. */
        fun pinnedTranslationX(position: Float, width: Int): Float = -position * width

        /** The incoming page fades in over the opaque outgoing page, which only disappears when fully covered. */
        fun alphaFor(position: Float, incoming: Boolean): Float {
            val a = abs(position)
            if (a >= 1f) return 0f
            return if (incoming) 1f - a else 1f
        }
    }
}
