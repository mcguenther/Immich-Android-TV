package nl.giejay.mediaslider.plugin

import android.os.Handler
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import com.zeuskartik.mediaslider.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.map.LocationMapRenderer
import nl.giejay.mediaslider.map.WorldMapData
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.mediaslider.util.OverlayFade
import timber.log.Timber

/**
 * Shows a small offline map behind the bottom-right metadata block, so a place name you don't know
 * can be placed at a glance. The land is drawn as a soft white silhouette with country borders
 * (Natural Earth 1:50m, bundled with the app, no network access) and a dot marks the location.
 *
 * Controlled by [MediaSliderConfiguration.showLocationMap]. Assets without coordinates show no map.
 * For merged portrait pairs the map follows the right-hand photo, like the right metadata column.
 * The map cross-fades with the page transition instead of popping in after it.
 */
class LocationMapViewPlugin : SliderViewPlugin<Unit?> {
    /**
     * Two stacked map views, so the outgoing map can fade out while the incoming one fades in, in
     * lockstep with the page cross-fade ([OverlayFade]), like the metadata texts.
     */
    private val mapViews = mutableListOf<ImageView>()
    private var visibleIndex = -1
    /** View used for the most recent map; the next map takes the other one, so a running fade-out is never cut. */
    private var lastUsedIndex = 1
    private var renderJob: Job? = null
    private var pendingAssetId: String? = null
    private var shownAssetId: String? = null
    private var config: MediaSliderConfiguration? = null
    private val observedViews = mutableListOf<View>()
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateVerticalOffset() }

    override fun attachView(rootView: ConstraintLayout, state: Unit?) {
        val density = rootView.resources.displayMetrics.density
        repeat(2) {
            val view = ImageView(rootView.context).apply {
                id = View.generateViewId()
                scaleType = ImageView.ScaleType.FIT_XY
                // Below the metadata holder (10dp), so the text and its gradient stay on top.
                elevation = MAP_ELEVATION_DP * density
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isFocusable = false
                alpha = 0f
                visibility = View.GONE
            }
            val params = ConstraintLayout.LayoutParams(
                LocationMapRenderer.widthPx(density),
                LocationMapRenderer.heightPx(density)
            ).apply {
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
            rootView.addView(view, params)
            mapViews += view
        }
    }

    override fun onLoadConfig(context: SliderViewPluginContext, config: MediaSliderConfiguration, state: Unit?) {
        this.config = config
        if (!config.showLocationMap) return
        listOfNotNull(
            context.rootView.findViewById(R.id.meta_data_holder),
            context.rootView.findViewById<View>(R.id.metadata_view_right)
        ).forEach {
            it.addOnLayoutChangeListener(layoutListener)
            observedViews += it
        }
        updateVerticalOffset()
    }

    /** Transition start: fade the old map out and render + fade in the new one during the same transition. */
    override fun onPageSelected(context: SliderViewPluginContext, sliderItemIndex: Int, state: Unit?) {
        val config = config ?: return
        val holder = config.items.getOrNull(sliderItemIndex)
        val fadeMs = OverlayFade.fadeMs(config.transition)
        if (!config.showLocationMap || holder == null) {
            fadeOutVisible(fadeMs, config)
            return
        }
        val item = mapItem(holder)
        if (item.id == shownAssetId && visibleIndex >= 0) return
        fadeOutVisible(fadeMs, config)
        render(context, item.id, item, fadeMs, config, startedAt = SystemClock.uptimeMillis())
    }

    /** Hard cut (wrap-around jump, transition NONE): drop the old map at once; onPageSettled renders the new one. */
    override fun onTransitionSkipped(context: SliderViewPluginContext, state: Unit?) {
        renderJob?.cancel()
        pendingAssetId = null
        fadeOutVisible(0L, config)
    }

    /** Idempotent fallback (first page, hard cuts): render if the settled asset's map is not shown yet. */
    override fun onPageSettled(
        context: SliderViewPluginContext,
        config: MediaSliderConfiguration,
        sliderItem: SliderItemViewHolder,
        sliderItemIndex: Int,
        handler: Handler,
        state: Unit?
    ) {
        this.config = config
        if (!config.showLocationMap) {
            renderJob?.cancel()
            pendingAssetId = null
            fadeOutVisible(0L, config)
            return
        }
        val item = mapItem(sliderItem)
        if (item.id == shownAssetId || item.id == pendingAssetId) return
        fadeOutVisible(0L, config)
        render(context, item.id, item, FADE_IN_MS, config, startedAt = SystemClock.uptimeMillis())
    }

    override fun onDestroy(context: SliderViewPluginContext, state: Unit?) {
        renderJob?.cancel()
        renderJob = null
        pendingAssetId = null
        shownAssetId = null
        observedViews.forEach { it.removeOnLayoutChangeListener(layoutListener) }
        observedViews.clear()
        mapViews.forEach { view ->
            view.animate().cancel()
            (view.parent as? ConstraintLayout)?.removeView(view)
        }
        mapViews.clear()
        visibleIndex = -1
    }

    /** For merged portrait pairs the map follows the right-hand photo, like the right metadata column. */
    private fun mapItem(holder: SliderItemViewHolder) = if (holder.hasSecondaryItem()) holder.secondaryItem!! else holder.mainItem

    private fun render(context: SliderViewPluginContext, assetId: String, item: nl.giejay.mediaslider.model.SliderItem,
                       fadeMs: Long, config: MediaSliderConfiguration, startedAt: Long) {
        renderJob?.cancel()
        pendingAssetId = assetId
        val density = context.context.resources.displayMetrics.density
        renderJob = context.ioScope.launch {
            val bitmap = try {
                val location = item.getGeoLocation() ?: return@launch
                LocationMapRenderer.render(WorldMapData.load(context.context), location, density)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not render location map for asset $assetId")
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (pendingAssetId != assetId || mapViews.size < 2) return@withContext
                // Use the other view than last time: the outgoing map may still be fading out in it.
                val index = 1 - lastUsedIndex
                lastUsedIndex = index
                val view = mapViews[index]
                view.animate().cancel()
                view.setImageBitmap(bitmap)
                view.alpha = 0f
                view.visibility = View.VISIBLE
                visibleIndex = index
                shownAssetId = assetId
                pendingAssetId = null
                updateVerticalOffset()
                // Fade in over what is left of the page transition; a late map still fades in briefly.
                val remaining = fadeMs - (SystemClock.uptimeMillis() - startedAt)
                view.animate().alpha(1f)
                    .setDuration(remaining.coerceAtLeast(OverlayFade.LATE_FADE_MS))
                    .setInterpolator(OverlayFade.interpolator(config.transition))
                    .start()
            }
        }
    }

    /** Fades the currently visible map out over [fadeMs] (0 = at once) and frees its bitmap afterwards. */
    private fun fadeOutVisible(fadeMs: Long, config: MediaSliderConfiguration?) {
        val index = visibleIndex
        visibleIndex = -1
        shownAssetId = null
        val view = mapViews.getOrNull(index) ?: return
        view.animate().cancel()
        val hide = Runnable {
            view.visibility = View.GONE
            view.setImageDrawable(null)
        }
        if (fadeMs <= 0L || config == null) {
            view.alpha = 0f
            hide.run()
        } else {
            view.animate().alpha(0f).setDuration(fadeMs)
                .setInterpolator(OverlayFade.interpolator(config.transition))
                .withEndAction { if (visibleIndex != index) hide.run() }
                .start()
        }
    }

    /** Keeps the location dot just above the bottom-right text block, however many lines it has. */
    private fun updateVerticalOffset() {
        val first = mapViews.firstOrNull() ?: return
        val root = first.parent as? View ?: return
        val holder = root.findViewById<View>(R.id.meta_data_holder)
        val rightColumn = root.findViewById<View>(R.id.metadata_view_right)
        val density = first.resources.displayMetrics.density
        val translation = mapTranslationY(
            mapHeightPx = first.layoutParams.height,
            textBlockTopFromBottomPx = (holder?.paddingBottom ?: 0) + (rightColumn?.height ?: 0),
            gapPx = DOT_GAP_ABOVE_TEXT_DP * density
        )
        mapViews.forEach { it.translationY = translation }
    }

    companion object {
        private const val MAP_ELEVATION_DP = 5f
        private const val DOT_GAP_ABOVE_TEXT_DP = 14f
        private const val FADE_IN_MS = 400L

        /**
         * Vertical translation of the bottom-aligned map so that its dot (at [LocationMapRenderer.ANCHOR_Y])
         * ends up [gapPx] above the top of the text block. Positive values move the map down.
         */
        fun mapTranslationY(mapHeightPx: Int, textBlockTopFromBottomPx: Int, gapPx: Float): Float {
            val dotFromBottom = (1f - LocationMapRenderer.ANCHOR_Y) * mapHeightPx
            return dotFromBottom - (textBlockTopFromBottomPx + gapPx)
        }
    }
}
