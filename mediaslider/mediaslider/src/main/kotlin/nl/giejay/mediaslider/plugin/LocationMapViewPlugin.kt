package nl.giejay.mediaslider.plugin

import android.animation.ObjectAnimator
import android.os.Handler
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
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
    /** Asset each map view currently shows (null = none); the metadata layer for it carries the same tag id. */
    private val mapAssetIds = arrayOfNulls<String>(2)
    /** Running glide of a map view's translationY (separate from the alpha fade) and the value it heads to. */
    private val translators = arrayOfNulls<ObjectAnimator>(2)
    private val translationTargets = floatArrayOf(Float.NaN, Float.NaN)
    private var lastTranslation = 0f
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
        observeHolders(context.rootView)
        updateVerticalOffset()
    }

    /** Observes every metadata layer (there are two), not just the first. Idempotent. */
    private fun observeHolders(root: ConstraintLayout) {
        metadataHolders(root).forEach { holder ->
            listOfNotNull(holder, holder.findViewById<View>(R.id.metadata_view_right)).forEach {
                if (it !in observedViews) {
                    it.addOnLayoutChangeListener(layoutListener)
                    observedViews += it
                }
            }
        }
    }

    private fun metadataHolders(root: View): List<View> {
        val group = root as? android.view.ViewGroup ?: return emptyList()
        return (0 until group.childCount).map { group.getChildAt(it) }.filter { it.id == R.id.meta_data_holder }
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
        if (config.showLocationMap) observeHolders(context.rootView)
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
        translators.forEachIndexed { i, a -> a?.cancel(); translators[i] = null }
        mapAssetIds.fill(null)
        translationTargets.fill(Float.NaN)
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
                cancelTranslator(index)
                view.setImageBitmap(bitmap)
                view.alpha = 0f
                view.visibility = View.VISIBLE
                mapAssetIds[index] = assetId
                visibleIndex = index
                shownAssetId = assetId
                pendingAssetId = null
                // Place it while still invisible; without a resolved match use the front-most visible text
                // block (or the last position) and glide once the matching layer is resolved.
                val root = view.parent as? View
                val height = textHeightFor(root, assetId) ?: frontMostVisibleHeight(root)
                translationTargets[index] = Float.NaN
                if (height != null) {
                    val target = targetFor(view, height)
                    view.translationY = target
                    lastTranslation = target
                    translationTargets[index] = if (textHeightFor(root, assetId) != null) target else Float.NaN
                } else {
                    view.translationY = lastTranslation
                }
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
        // The outgoing map keeps its position: freeze any glide in progress.
        cancelTranslator(index)
        val hide = Runnable {
            view.visibility = View.GONE
            view.setImageDrawable(null)
            mapAssetIds[index] = null
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

    /**
     * Keeps the location dot above the bottom-right text block of the metadata layer that belongs to the map
     * being shown, however many lines it has. Only the incoming/visible map moves; the outgoing one is left alone.
     */
    private fun updateVerticalOffset() {
        val index = visibleIndex
        val view = mapViews.getOrNull(index) ?: return
        if (view.visibility != View.VISIBLE) return
        val assetId = mapAssetIds[index] ?: return
        val height = textHeightFor(view.parent as? View, assetId) ?: return // not resolved yet: keep position
        applyTranslation(index, targetFor(view, height))
    }

    private fun targetFor(view: View, textHeightFromBottomPx: Int): Float =
        mapTranslationY(
            mapHeightPx = view.layoutParams.height,
            textBlockTopFromBottomPx = textHeightFromBottomPx,
            gapPx = DOT_GAP_ABOVE_TEXT_DP * view.resources.displayMetrics.density
        )

    private fun holderHeight(holder: View): Int =
        holder.paddingBottom + (holder.findViewById<View>(R.id.metadata_view_right)?.height ?: 0)

    private fun textHeightFor(root: View?, assetId: String): Int? {
        root ?: return null
        return chooseTextHeight(
            metadataHolders(root).map { (it.getTag(R.id.metadata_right_item_id) as? String) to holderHeight(it) },
            assetId
        )
    }

    /** Last (top-most) visible metadata layer's height, used until the layer for the map's asset is resolved. */
    private fun frontMostVisibleHeight(root: View?): Int? {
        root ?: return null
        return metadataHolders(root).lastOrNull { it.visibility == View.VISIBLE }?.let { holderHeight(it) }
    }

    private fun cancelTranslator(index: Int) {
        translators[index]?.cancel()
        translators[index] = null
    }

    /** Glides a visible map to [target]; an invisible one (about to fade in) is set directly. */
    private fun applyTranslation(index: Int, target: Float) {
        val view = mapViews.getOrNull(index) ?: return
        val previous = translationTargets[index]
        if (!previous.isNaN() && Math.abs(previous - target) < 0.5f) return
        translationTargets[index] = target
        lastTranslation = target
        cancelTranslator(index)
        if (view.alpha <= VISIBLE_ALPHA || view.visibility != View.VISIBLE) {
            view.translationY = target
            return
        }
        if (Math.abs(view.translationY - target) < 0.5f) {
            view.translationY = target
            return
        }
        translators[index] = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, view.translationY, target).apply {
            duration = GLIDE_MS
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    companion object {
        private const val MAP_ELEVATION_DP = 5f
        private const val DOT_GAP_ABOVE_TEXT_DP = 30f
        private const val FADE_IN_MS = 400L
        private const val GLIDE_MS = 250L
        private const val VISIBLE_ALPHA = 0.01f

        /**
         * Height (padding + right column, from the screen bottom) of the metadata layer tagged with [assetId],
         * from (tagId, height) pairs of all layers; null when no layer is resolved for that asset yet.
         */
        fun chooseTextHeight(layers: List<Pair<String?, Int>>, assetId: String?): Int? {
            if (assetId == null) return null
            return layers.firstOrNull { it.first == assetId }?.second
        }

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
