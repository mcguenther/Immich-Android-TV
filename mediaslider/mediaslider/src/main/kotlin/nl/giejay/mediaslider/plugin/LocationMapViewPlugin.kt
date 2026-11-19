package nl.giejay.mediaslider.plugin

import android.os.Handler
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
import timber.log.Timber

/**
 * Shows a small offline map behind the bottom-right metadata block, so a place name you don't know
 * can be placed at a glance. The land is drawn as a soft white silhouette with country borders
 * (Natural Earth 1:50m, bundled with the app, no network access) and a dot marks the location.
 *
 * Controlled by [MediaSliderConfiguration.showLocationMap]. Assets without coordinates show no map.
 * For merged portrait pairs the map follows the right-hand photo, like the right metadata column.
 */
class LocationMapViewPlugin : SliderViewPlugin<Unit?> {
    private var mapView: ImageView? = null
    private var renderJob: Job? = null
    private var pendingAssetId: String? = null
    private val observedViews = mutableListOf<View>()
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateVerticalOffset() }

    override fun attachView(rootView: ConstraintLayout, state: Unit?) {
        val density = rootView.resources.displayMetrics.density
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
        mapView = view
    }

    override fun onLoadConfig(context: SliderViewPluginContext, config: MediaSliderConfiguration, state: Unit?) {
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

    override fun onPageSelected(context: SliderViewPluginContext, sliderItemIndex: Int, state: Unit?) {
        // Hide right away while paging so the previous asset's map does not linger.
        pendingAssetId = null
        renderJob?.cancel()
        hideMap()
    }

    override fun onPageSettled(
        context: SliderViewPluginContext,
        config: MediaSliderConfiguration,
        sliderItem: SliderItemViewHolder,
        sliderItemIndex: Int,
        handler: Handler,
        state: Unit?
    ) {
        renderJob?.cancel()
        if (!config.showLocationMap) {
            pendingAssetId = null
            hideMap()
            return
        }
        val item = if (sliderItem.hasSecondaryItem()) sliderItem.secondaryItem!! else sliderItem.mainItem
        val assetId = item.id
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
                val view = mapView ?: return@withContext
                if (pendingAssetId != assetId) return@withContext
                view.setImageBitmap(bitmap)
                view.visibility = View.VISIBLE
                updateVerticalOffset()
                view.animate().alpha(1f).setDuration(FADE_IN_MS).start()
            }
        }
    }

    override fun onDestroy(context: SliderViewPluginContext, state: Unit?) {
        renderJob?.cancel()
        renderJob = null
        pendingAssetId = null
        observedViews.forEach { it.removeOnLayoutChangeListener(layoutListener) }
        observedViews.clear()
        mapView?.let { view ->
            view.animate().cancel()
            (view.parent as? ConstraintLayout)?.removeView(view)
        }
        mapView = null
    }

    private fun hideMap() {
        mapView?.apply {
            animate().cancel()
            alpha = 0f
            visibility = View.GONE
            setImageDrawable(null)
        }
    }

    /** Keeps the location dot just above the bottom-right text block, however many lines it has. */
    private fun updateVerticalOffset() {
        val view = mapView ?: return
        val root = view.parent as? View ?: return
        val holder = root.findViewById<View>(R.id.meta_data_holder)
        val rightColumn = root.findViewById<View>(R.id.metadata_view_right)
        val density = view.resources.displayMetrics.density
        view.translationY = mapTranslationY(
            mapHeightPx = view.layoutParams.height,
            textBlockTopFromBottomPx = (holder?.paddingBottom ?: 0) + (rightColumn?.height ?: 0),
            gapPx = DOT_GAP_ABOVE_TEXT_DP * density
        )
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
