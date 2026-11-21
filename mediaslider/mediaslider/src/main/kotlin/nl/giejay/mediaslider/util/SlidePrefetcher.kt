package nl.giejay.mediaslider.util

import android.app.ActivityManager
import android.content.Context
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.Target
import nl.giejay.mediaslider.adapter.SliderImageRequests
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.SliderItemType
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.mediaslider.player.VideoPrecacher

/**
 * Warms the caches for the next slides: photos are preloaded into Glide with the very same request the
 * pager uses for display, video starts are pre-cached by [VideoPrecacher] (plus their thumbnail, the poster). Everything runs
 * asynchronously (Glide's executors, the precacher thread); methods must be called on the main thread.
 *
 * @param slotSize slot size for an image page (`paired` = portrait pair), see `ScreenSlidePagerAdapter.slotSize`
 */
class SlidePrefetcher(
    private val context: Context,
    private val config: MediaSliderConfiguration,
    private val precacher: VideoPrecacher?,
    private val clipPlanner: ClipPlanner,
    private val slotSize: (paired: Boolean) -> Pair<Int, Int>
) {
    private class Entry(val targets: List<Target<Drawable>>, val videoUrl: String?)

    private val active = LinkedHashMap<String, Entry>()
    private val count: Int by lazy {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        PrefetchPlanner.effectiveCount(config.loading.prefetchCount, am?.isLowRamDevice ?: false, am?.memoryClass ?: Int.MAX_VALUE)
    }

    /**
     * Re-plans the window after the current page became [current] (or [items] changed). With
     * [reverse] the slides before [current] are prefetched (a slideshow playing backwards).
     */
    fun onPageChanged(current: Int, items: List<SliderItemViewHolder>, reverse: Boolean = false) {
        if (count == 0) return
        val wanted = PrefetchPlanner.indicesToPrefetch(current, count, items.size, reverse).map { items[it] }
        val wantedKeys = wanted.map { key(it) }.toSet()
        PrefetchPlanner.staleKeys(active.keys.toList(), wantedKeys).forEach { cancel(it) }
        wanted.forEach { slide ->
            val k = key(slide)
            if (active.containsKey(k)) return@forEach
            active[k] = if (slide.type == SliderItemType.VIDEO) {
                slide.url?.let { precacher?.precache(it, clipStartFraction(slide)) }
                // The thumbnail covers the black surface until the first frame; it must not be fetched at that moment.
                val poster = slide.mainItem.thumbnailUrl?.takeIf { it.isNotBlank() }?.let {
                    val (w, h) = slotSize(false)
                    listOf(Glide.with(context).load(it).centerInside().preload(w, h))
                }.orEmpty()
                Entry(poster, slide.url)
            } else {
                val (w, h) = slotSize(slide.hasSecondaryItem())
                val targets = listOfNotNull(slide.mainItem, slide.secondaryItem).map {
                    SliderImageRequests.request(context, config, it, w, h).preload()
                }
                Entry(targets, null)
            }
        }
    }

    /** Cancels all preloads. */
    fun clear() {
        active.keys.toList().forEach { cancel(it) }
    }

    private fun cancel(key: String) {
        val entry = active.remove(key) ?: return
        entry.targets.forEach { Glide.with(context).clear(it) }
        entry.videoUrl?.let { precacher?.cancel(it) }
    }

    /**
     * Where the excerpt of an upcoming video starts (fraction of its length), 0 when it starts at the
     * beginning, plays completely or the duration is unknown. Uses the planner the player uses.
     */
    private fun clipStartFraction(slide: SliderItemViewHolder): Double {
        val duration = slide.mainItem.durationMs ?: return 0.0
        val window = clipPlanner.excerptFor(slide.mainItem.id, duration, config.videoPlayback, config.interval, 1f)
            ?: return 0.0
        return window.startMs.toDouble() / duration
    }

    private fun key(slide: SliderItemViewHolder) = slide.ids().joinToString("+") + "|" + slide.type
}
