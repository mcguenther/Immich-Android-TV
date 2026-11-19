package nl.giejay.mediaslider.plugin

import android.os.Handler
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.zeuskartik.mediaslider.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.MetaDataType
import nl.giejay.mediaslider.model.SliderItem
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.mediaslider.util.OverlayFade

/**
 * Optional top-of-screen date overlay with a dark-to-transparent scrim.
 * Controlled by [MediaSliderConfiguration.showDateTopLeft]; bottom DATE metadata is unchanged.
 *
 * Two identical views are stacked and cross-fade with the page transition: when a slide change starts
 * the new date is resolved and, if it differs from the shown one, faded in while the old one fades out
 * (same duration and easing as the page transition). An unchanged date is not touched, so it does not flicker.
 */
class DateOverlayViewPlugin : SliderViewPlugin<Unit?> {
    private val views = arrayOfNulls<TextView>(2)
    private var front = 0
    private var config: MediaSliderConfiguration? = null

    /** Asset the front view shows or is about to show; guards late results. */
    private var pendingDateAssetId: String? = null

    override fun attachView(rootView: ConstraintLayout, state: Unit?) {
        for (i in 0..1) {
            val view = View.inflate(rootView.context, R.layout.metadata_date_overlay, null) as TextView
            val params = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_CONSTRAINT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            }
            rootView.addView(view, params)
            views[i] = view
        }
    }

    override fun onLoadConfig(context: SliderViewPluginContext, config: MediaSliderConfiguration, state: Unit?) {
        this.config = config
    }

    override fun onPageSelected(context: SliderViewPluginContext, sliderItemIndex: Int, state: Unit?) {
        val cfg = config ?: return
        if (!cfg.showDateTopLeft) {
            hideAll()
            return
        }
        show(context, context.currentItemProvider().mainItem, OverlayFade.fadeMs(cfg.transition))
    }

    override fun onTransitionSkipped(context: SliderViewPluginContext, state: Unit?) {
        // A hard cut: end the fade at once.
        views.forEach { it?.animate()?.cancel() }
        views.forEachIndexed { i, view ->
            view?.alpha = if (i == front && !view?.text.isNullOrEmpty()) 1f else 0f
            if (i != front) view?.visibility = View.GONE
        }
    }

    override fun onPageSettled(
        context: SliderViewPluginContext,
        config: MediaSliderConfiguration,
        sliderItem: SliderItemViewHolder,
        sliderItemIndex: Int,
        handler: Handler,
        state: Unit?
    ) {
        if (!config.showDateTopLeft) {
            hideAll()
            return
        }
        // Normally onPageSelected already started this; first page and jumps are shown without a fade.
        if (pendingDateAssetId != sliderItem.mainItem.id) show(context, sliderItem.mainItem, 0L)
    }

    override fun onDestroy(context: SliderViewPluginContext, state: Unit?) {
        views.forEachIndexed { i, view ->
            view?.animate()?.cancel()
            view?.let { (it.parent as? ConstraintLayout)?.removeView(it) }
            views[i] = null
        }
        pendingDateAssetId = null
    }

    private fun hideAll() {
        pendingDateAssetId = null
        views.forEach {
            it?.animate()?.cancel()
            it?.text = ""
            it?.visibility = View.GONE
            it?.alpha = 0f
        }
    }

    /** Resolves [sliderItem]'s date and swaps it in over [fadeMs] (0 = at once). */
    private fun show(context: SliderViewPluginContext, sliderItem: SliderItem, fadeMs: Long) {
        pendingDateAssetId = sliderItem.id
        val started = SystemClock.uptimeMillis()
        context.ioScope.launch {
            val value = sliderItem.get(MetaDataType.DATE).orEmpty().trim()
            withContext(Dispatchers.Main) {
                if (pendingDateAssetId != sliderItem.id) return@withContext
                swapTo(value, fadeMs, fadeMs - (SystemClock.uptimeMillis() - started))
            }
        }
    }

    private fun swapTo(value: String, fadeMs: Long, remainingMs: Long) {
        val outgoing = views[front] ?: return
        val incoming = views[1 - front] ?: return
        val shown = outgoing.text.toString()
        if (value.isNotEmpty() && value == shown && outgoing.visibility == View.VISIBLE) {
            // Same date: leave it alone (also settles a half-faded outgoing view of an aborted fade).
            outgoing.animate().cancel()
            outgoing.alpha = 1f
            incoming.animate().cancel()
            incoming.visibility = View.GONE
            incoming.alpha = 0f
            return
        }
        val cfg = config
        val interpolator = cfg?.let { OverlayFade.interpolator(it.transition) }
        val duration = if (fadeMs <= 0L) 0L else remainingMs.coerceAtLeast(OverlayFade.LATE_FADE_MS.coerceAtMost(fadeMs))

        outgoing.animate().cancel()
        incoming.animate().cancel()
        if (duration == 0L) {
            outgoing.text = ""
            outgoing.visibility = View.GONE
            outgoing.alpha = 0f
            incoming.text = value
            incoming.alpha = 1f
            incoming.visibility = if (value.isNotEmpty()) View.VISIBLE else View.GONE
        } else {
            // The previous outgoing view (if an earlier fade was aborted) is reused as incoming; clear it first.
            // (No end action: ViewPropertyAnimator may run it on cancel; a view at alpha 0 costs nothing to draw.)
            if (outgoing.visibility == View.VISIBLE) {
                outgoing.animate().alpha(0f).setDuration(duration).setInterpolator(interpolator).start()
            }
            incoming.text = value
            incoming.alpha = 0f
            if (value.isNotEmpty()) {
                incoming.visibility = View.VISIBLE
                incoming.animate().alpha(1f).setDuration(duration).setInterpolator(interpolator).start()
            } else {
                incoming.visibility = View.GONE
            }
        }
        front = 1 - front
    }
}
