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
}
