package nl.giejay.mediaslider.plugin

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Handler
import android.os.SystemClock
import android.view.View
import android.widget.ListView
import androidx.constraintlayout.widget.ConstraintLayout
import com.zeuskartik.mediaslider.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.mediaslider.adapter.AlignOption
import nl.giejay.mediaslider.adapter.MetaDataAdapter
import nl.giejay.mediaslider.adapter.MetaDataClock
import nl.giejay.mediaslider.adapter.MetaDataMediaCount
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.SliderItem
import nl.giejay.mediaslider.model.SliderItemType
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.mediaslider.util.OverlayFade
import nl.giejay.mediaslider.util.OverlaySwitcher
import nl.giejay.mediaslider.view.MediaSliderController

/**
 * Bottom metadata texts. Two identical layers are stacked; when a slide change starts the new slide's
 * values are bound into the layer behind and the two cross-fade over the page transition's duration
 * ([OverlayFade]). The clock does not depend on the slide and therefore stays fully visible in the
 * outgoing layer until the end of the fade, so it does not flicker.
 */
class MetadataViewPlugin : SliderViewPlugin<MetadataRenderState>, SliderControllerPlugin {

    override fun createState(context: SliderViewPluginContext, config: MediaSliderConfiguration): MetadataRenderState =
        MetadataRenderState(listOf(MetadataLayer(context, config), MetadataLayer(context, config)), config)

    override fun attachView(rootView: ConstraintLayout, state: MetadataRenderState?) {
        val renderState = state ?: return
        lastState = renderState
        renderState.layers.forEachIndexed { index, layer ->
            val view = View.inflate(rootView.context, R.layout.metadata_holder, null)
            val params = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
            rootView.addView(view, params)
            layer.holder = view
            // The layer behind starts hidden; the front one shows the first slide once it is bound.
            if (index != renderState.frontIndex) layer.setFade(0f, 0f)
        }
    }

    override fun onLoadConfig(
        context: SliderViewPluginContext,
        config: MediaSliderConfiguration,
        state: MetadataRenderState?
    ) {
        val renderState = state ?: return
        renderState.layers.forEach { layer ->
            val holder = layer.holder ?: return@forEach
            holder.findViewById<ListView>(R.id.metadata_view_right)?.apply {
                divider = null
                adapter = layer.right
            }
            holder.findViewById<ListView>(R.id.metadata_view_left)?.apply {
                divider = null
                adapter = layer.left
            }
        }
    }

    override fun onPageSelected(context: SliderViewPluginContext, sliderItemIndex: Int, state: MetadataRenderState?) {
        state?.beginTransition(context, context.currentItemProvider(), sliderItemIndex)
    }

    override fun onTransitionSkipped(context: SliderViewPluginContext, state: MetadataRenderState?) {
        state?.finishNow()
    }

    override fun onPageSettled(
        context: SliderViewPluginContext,
        config: MediaSliderConfiguration,
        sliderItem: SliderItemViewHolder,
        sliderItemIndex: Int,
        handler: Handler,
        state: MetadataRenderState?
    ) {
        state?.settle(context, sliderItem, sliderItemIndex)
    }

    override fun onControllerVisibilityChanged(isVisible: Boolean, rootView: View, controller: MediaSliderController, config: MediaSliderConfiguration) {
        // This callback gets no state, so the one from attachView is kept.
        lastState?.setControllerVisible(isVisible)
    }

    private var lastState: MetadataRenderState? = null

    override fun onDestroy(context: SliderViewPluginContext, state: MetadataRenderState?) {
        state?.release()
        lastState = null
    }
}

/**
 * One stacked set of metadata texts (the two lists and their holder). It always shows the values of
 * [item] (not of the pager's current item), so an outgoing layer keeps its old texts while it fades out.
 */
class MetadataLayer(private val context: SliderViewPluginContext, private val config: MediaSliderConfiguration) {
    var holder: View? = null
    var item: SliderItemViewHolder? = null
    var index: Int = -1

    /** Alpha of rows that depend on the slide (and of the gradient), and of the clock. */
    var itemAlpha = 1f
        private set
    var staticAlpha = 1f
        private set

    /** Asset id whose values are all resolved in the adapters, and the one currently being resolved. */
    var valuesFor: String? = null
    var loadingFor: String? = null

    /** Bumped on every bind so that late results of an older bind are ignored. */
    var token = 0

    private var gradientApplied = false

    private fun shown(): SliderItemViewHolder = item ?: context.currentItemProvider()

    val right = MetaDataAdapter(
        context.context,
        config.metaDataConfig.filter { it.align == AlignOption.RIGHT },
        config.metaDataConfig.map { it.withAlign(align = AlignOption.RIGHT) }.distinct(),
        { val current = shown(); if (current.hasSecondaryItem()) current.secondaryItem!! else current.mainItem },
        { shown().hasSecondaryItem() }
    )

    val left = MetaDataAdapter(
        context.context,
        config.metaDataConfig.filter { it.align == AlignOption.LEFT },
        config.metaDataConfig.filterNot { it is MetaDataClock || it is MetaDataMediaCount }
            .map { it.withAlign(align = AlignOption.LEFT) }
            .distinct(),
        { shown().mainItem },
        { shown().hasSecondaryItem() }
    )

    /** Points this layer at [newItem]; its values are not loaded yet (see [MetadataRenderState]). */
    fun bind(newItem: SliderItemViewHolder, newIndex: Int) {
        item = newItem
        index = newIndex
        token++
        valuesFor = if (left.hasAllValues(newItem.mainItem.id) && right.hasAllValues(newItem.rightItem().id)) newItem.key() else null
        loadingFor = null
        publishRightItemId()
        left.notifyDataSetChanged()
        right.notifyDataSetChanged()
    }

    /**
     * Tags the holder with the id of the right-column item once this layer's values are resolved (null while
     * they are not), so the location map can find the layer that belongs to its asset and its text height.
     */
    fun publishRightItemId() {
        val current = item
        holder?.setTag(R.id.metadata_right_item_id, if (valuesFor != null && current != null) current.rightItem().id else null)
    }

    fun setFade(itemAlpha: Float, staticAlpha: Float) {
        this.itemAlpha = itemAlpha
        this.staticAlpha = staticAlpha
        left.setFade(itemAlpha, staticAlpha)
        right.setFade(itemAlpha, staticAlpha)
        holder?.let { view ->
            view.visibility = if (itemAlpha > 0f || staticAlpha > 0f) View.VISIBLE else View.INVISIBLE
            view.background?.alpha = (itemAlpha * 255).toInt()
        }
    }

    /** Shows the gradient scrim when wanted: not for videos, not while the controller is visible. */
    fun refreshBackground(controllerVisible: Boolean) {
        val view = holder ?: return
        val wanted = config.isGradiantOverlayVisible && !controllerVisible && item?.type != SliderItemType.VIDEO
        if (wanted && !gradientApplied) {
            view.setBackgroundResource(R.drawable.gradient_overlay)
            view.background?.mutate()
            gradientApplied = true
        } else if (!wanted && gradientApplied) {
            view.background = null
            gradientApplied = false
        }
        view.background?.alpha = (itemAlpha * 255).toInt()
    }
}

private fun SliderItemViewHolder.rightItem(): SliderItem = secondaryItem ?: mainItem

/** Identity of a slide (a portrait pair has two photos). */
private fun SliderItemViewHolder.key(): String = ids().joinToString("+")

/**
 * State machine of the metadata cross-fade for two [MetadataLayer]s; see [MetadataViewPlugin].
 *
 *  - [beginTransition] (slide change starts): bind the new slide into the back layer, start fading the old
 *    texts out at once; the new texts follow as soon as their values are resolved.
 *  - [finishNow] (hard cut) / [settle] (transition done, first page, non-smooth jump): idempotent end state.
 *  - Overlapping transitions abort the running fade (keeping the partial alpha of the layer that was fading in,
 *    which now fades out) and flip again.
 */
class MetadataRenderState(val layers: List<MetadataLayer>, private val config: MediaSliderConfiguration) {
    private val switcher = OverlaySwitcher()
    private var driver: ValueAnimator? = null
    private var lateDriver: ValueAnimator? = null
    private var fadeStart = 0L
    private var readyAt = -1L
    private var rampMs = 0L
    private var outStart = 1f
    private var outStatic = 1f
    private var controllerVisible = false

    val frontIndex: Int get() = switcher.front
    private val front: MetadataLayer get() = layers[switcher.front]
    private val back: MetadataLayer get() = layers[switcher.back]

    fun setControllerVisible(visible: Boolean) {
        controllerVisible = visible
        layers.forEach { it.refreshBackground(visible) }
    }

    fun beginTransition(context: SliderViewPluginContext, item: SliderItemViewHolder, index: Int) {
        stopAnimators()
        // Settle what the aborted fade left behind: the layer that was fading in is now fully "static",
        // the one that was fading out is gone.
        front.setFade(front.itemAlpha, 1f)
        back.setFade(0f, 0f)
        val outgoing = front
        val incoming = layers[switcher.begin()]
        outStart = outgoing.itemAlpha
        outStatic = outgoing.staticAlpha

        incoming.bind(item, index)
        incoming.refreshBackground(controllerVisible)
        val fadeMs = OverlayFade.fadeMs(config.transition)
        fadeStart = SystemClock.uptimeMillis()
        readyAt = -1L
        rampMs = 0L

        if (fadeMs == 0L) {
            outgoing.setFade(0f, 0f)
            incoming.setFade(0f, 1f)
            resolve(context, incoming) { incoming.setFade(1f, 1f) }
            return
        }
        incoming.setFade(0f, 0f)
        resolve(context, incoming) { markReady(incoming, fadeMs) }
        driver = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = fadeMs
            interpolator = OverlayFade.interpolator(config.transition)
            addUpdateListener { applyFrame(outgoing, incoming, it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    driver = null
                    outgoing.setFade(0f, 0f)
                    incoming.setFade(if (readyAt >= 0) 1f else 0f, 1f)
                }
            })
            start()
        }
    }

    private fun applyFrame(outgoing: MetadataLayer, incoming: MetadataLayer, progress: Float) {
        val now = SystemClock.uptimeMillis()
        val readyFor = if (readyAt < 0) -1L else now - readyAt
        outgoing.setFade(OverlayFade.outgoingAlpha(outStart, progress), outStatic)
        incoming.setFade(OverlayFade.incomingAlpha(progress, readyFor, rampMs), 0f)
    }

    /** The incoming values are in: fade them with the running transition, or on their own when it is over. */
    private fun markReady(incoming: MetadataLayer, fadeMs: Long) {
        val now = SystemClock.uptimeMillis()
        if (readyAt < 0) readyAt = now
        rampMs = OverlayFade.rampMs(now - fadeStart, fadeMs)
        if (driver?.isRunning == true) return // applyFrame picks the value up
        lateDriver?.cancel()
        lateDriver = ValueAnimator.ofFloat(incoming.itemAlpha, 1f).apply {
            duration = OverlayFade.LATE_FADE_MS
            addUpdateListener { incoming.setFade(it.animatedValue as Float, 1f) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { lateDriver = null }
            })
            start()
        }
    }

    /** Ends a running fade at once: front fully shown (once its values are in), back hidden. */
    fun finishNow() {
        stopAnimators()
        val f = front
        f.setFade(if (f.valuesFor != null && f.valuesFor == f.item?.key()) 1f else 0f, 1f)
        back.setFade(0f, 0f)
    }

    /**
     * Idempotent end state for [item]: normally [beginTransition] already bound it and this only makes
     * sure everything is resolved; first page and jumps without a transition are bound here, without fade.
     */
    fun settle(context: SliderViewPluginContext, item: SliderItemViewHolder, index: Int) {
        val f = front
        val animating = driver?.isRunning == true || lateDriver?.isRunning == true
        if (f.item?.key() != item.key() || f.index != index) {
            stopAnimators()
            f.bind(item, index)
            f.setFade(1f, 1f)
            back.setFade(0f, 0f)
            f.refreshBackground(controllerVisible)
            resolve(context, f) {}
            return
        }
        f.refreshBackground(controllerVisible)
        if (!animating) {
            f.setFade(if (f.valuesFor == item.key()) 1f else f.itemAlpha, 1f)
            back.setFade(0f, 0f)
        }
        resolve(context, f) { if (!animating && f.itemAlpha < 1f && driver == null && lateDriver == null) markReady(f, 0L) }
    }

    fun release() {
        stopAnimators()
    }

    private fun stopAnimators() {
        driver?.cancel()
        driver = null
        lateDriver?.cancel()
        lateDriver = null
    }

    /**
     * Resolves all values of [layer]'s slide on the IO scope (skipped when they are known), refreshes the lists
     * and then calls [onReady] on the main thread, unless the layer was bound to another slide meanwhile.
     */
    private fun resolve(context: SliderViewPluginContext, layer: MetadataLayer, onReady: () -> Unit) {
        val item = layer.item ?: return
        if (layer.valuesFor == item.key()) {
            layer.left.notifyDataSetChanged()
            layer.right.notifyDataSetChanged()
            onReady()
            return
        }
        if (layer.loadingFor == item.key()) return
        layer.loadingFor = item.key()
        val token = layer.token
        context.ioScope.launch {
            fill(context, layer.left, item.mainItem, layer.index)
            fill(context, layer.right, if (item.hasSecondaryItem()) item.secondaryItem!! else item.mainItem, layer.index)
            withContext(Dispatchers.Main) {
                if (layer.token != token) return@withContext
                layer.loadingFor = null
                layer.valuesFor = item.key()
                layer.publishRightItemId()
                layer.left.notifyDataSetChanged()
                layer.right.notifyDataSetChanged()
                onReady()
            }
        }
    }

    private suspend fun fill(context: SliderViewPluginContext, adapter: MetaDataAdapter, sliderItem: SliderItem, index: Int) {
        adapter.getItemsToShow().forEachIndexed { metaDataIndex, metaItem ->
            if (adapter.hasStateForItem(sliderItem.id, metaDataIndex)) return@forEachIndexed
            val value = metaItem.getValue(context.context, sliderItem, index, config.items.size)
            adapter.updateState(sliderItem.id, metaDataIndex, value ?: "")
        }
    }
}
