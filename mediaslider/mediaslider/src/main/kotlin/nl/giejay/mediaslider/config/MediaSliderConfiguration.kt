package nl.giejay.mediaslider.config

import nl.giejay.mediaslider.adapter.MetaDataItem
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.mediaslider.plugin.SliderControllerPlugin
import nl.giejay.mediaslider.plugin.SliderKeyEventPlugin
import nl.giejay.mediaslider.plugin.SliderViewPlugin
import nl.giejay.mediaslider.util.LoadMore

class MediaSliderConfiguration(
    val startPosition: Int,
    val interval: Int,
    var isVideoSoundEnable: Boolean,
    var items: List<SliderItemViewHolder>,
    var loadMore: LoadMore?,
    var onAssetSelected: (SliderItemViewHolder) -> Unit = {},
    val gradiantOverlay: Boolean,
    val metaDataConfig: List<MetaDataItem>,
    val display: DisplayOptions = DisplayOptions(),
    val motion: MotionOptions = MotionOptions(),
    val transition: TransitionOptions = TransitionOptions(),
    val loading: LoadingOptions = LoadingOptions(),
    val useLargeVideoBuffer: Boolean = false,
    /** When true, D-pad Left/Right seek in video; when false (default), they change assets. */
    val dpadSeeksInVideo: Boolean = false,
    /** When true, [nl.giejay.mediaslider.plugin.DateOverlayViewPlugin] shows DATE at the top. */
    val showDateTopLeft: Boolean = false,
    var controllerPlugins: List<SliderControllerPlugin> = emptyList(),
    var viewPlugins: List<SliderViewPlugin<*>> = emptyList(),
    var keyEventPlugins: List<SliderKeyEventPlugin> = emptyList()
) {
    val isGradiantOverlayVisible: Boolean
        get() = (metaDataConfig.isNotEmpty()) && this.gradiantOverlay

    // ---------------------------------------------------------------------------------------
    // Transitional accessors: they map the new option groups onto the names the slider code
    // used before the redesign, so it keeps compiling while each area is reworked. Remove an
    // accessor together with its last caller.
    // ---------------------------------------------------------------------------------------

    /** TRANSITIONAL (loading rework): use [loading]. */
    val isOnlyUseThumbnails: Boolean
        get() = !loading.useFullResolution

    /** TRANSITIONAL (transition rework): use [transition]. */
    val animationSpeedMillis: Int
        get() = transition.durationMillis

    /** TRANSITIONAL (transition rework): use [transition]. */
    val enableSlideAnimation: Boolean
        get() = transition.type != TransitionType.NONE

    /** TRANSITIONAL (motion rework): use [motion]. */
    val zoomAndScrollPanorama: Boolean
        get() = motion.enabled || motion.panoramaScroll

    /** TRANSITIONAL (motion rework): use [motion]. */
    val zoomEffectPercent: Int
        get() = if (motion.enabled) motion.zoomPercent else 0

    /** TRANSITIONAL (motion rework): use [motion]. */
    val panEffectPercent: Int
        get() = if (motion.enabled) motion.panPercent else 0
}
