package nl.giejay.mediaslider.util

import nl.giejay.mediaslider.model.SliderItem
import nl.giejay.mediaslider.model.SliderItemType
import nl.giejay.mediaslider.model.SliderItemViewHolder
import kotlin.math.ceil
import kotlin.math.min

/**
 * Pure helpers for changing the slider's page list while it is shown. No Android types, so they are unit tested.
 *
 * Repeats: an asset should not come back within [NO_REPEAT_WINDOW] slides, and as long as the source
 * delivers enough new assets it does not come back at all.
 */
object PageList {

    /** Minimum number of slides between two showings of the same photo or video, where the source allows it. */
    const val NO_REPEAT_WINDOW = 100

    /**
     * Below this share of never shown pages in a loaded batch the source is (nearly) exhausted, e.g. a
     * random source that has delivered the whole library. Then assets may come back, but only those
     * that were not shown within the last [NO_REPEAT_WINDOW] slides.
     */
    const val MIN_FRESH_RATIO = 0.25

    /**
     * [existing] followed by the pages of [incoming] that do not repeat an asset too soon. Random
     * sources sample with replacement, and the same asset can come back alone or paired with another
     * portrait photo.
     *
     * - While the batch holds enough never shown pages ([MIN_FRESH_RATIO]), only those are appended:
     *   no asset is shown twice.
     * - Otherwise every page is appended whose assets were not on one of the last [window] pages.
     *
     * No asset repeats within the appended pages. A pair of which only one photo repeats keeps the
     * other photo, alone. The pages of [existing] are kept as they are (same objects, same positions).
     */
    fun appendNew(
        existing: List<SliderItemViewHolder>,
        incoming: List<SliderItemViewHolder>,
        window: Int = NO_REPEAT_WINDOW
    ): List<SliderItemViewHolder> {
        if (incoming.isEmpty()) return existing
        val everShown = HashSet<String>()
        existing.forEach { page -> keysOf(page).forEach { everShown.add(it) } }
        val fresh = pick(incoming, everShown)
        val enoughFresh = fresh.isNotEmpty() && fresh.size >= ceil(incoming.size * MIN_FRESH_RATIO)
        val added = if (enoughFresh || existing.isEmpty()) {
            fresh
        } else {
            val recent = HashSet<String>()
            existing.takeLast(window.coerceAtLeast(0)).forEach { page -> keysOf(page).forEach { recent.add(it) } }
            pick(incoming, recent)
        }
        if (added.isEmpty()) return existing
        return ArrayList<SliderItemViewHolder>(existing.size + added.size).apply {
            addAll(existing)
            addAll(separateVideos(added, existing.lastOrNull()))
        }
    }

    /**
     * [pages] reordered so that no video follows a video (the next video player can only be warmed up
     * while a photo is shown). A video that would follow one is held back and placed after the next photo;
     * everything else keeps its order. [previous] is the page before the first one. Videos that find no
     * photo to follow (the list ends or holds only videos) stay together at the end.
     */
    fun separateVideos(pages: List<SliderItemViewHolder>, previous: SliderItemViewHolder? = null): List<SliderItemViewHolder> {
        val out = ArrayList<SliderItemViewHolder>(pages.size)
        val held = ArrayDeque<SliderItemViewHolder>()
        fun lastIsVideo() = (out.lastOrNull() ?: previous)?.type == SliderItemType.VIDEO
        pages.forEach { page ->
            if (page.type == SliderItemType.VIDEO) {
                if (lastIsVideo()) held.addLast(page) else out.add(page)
            } else {
                out.add(page)
                held.removeFirstOrNull()?.let { out.add(it) }
            }
        }
        out.addAll(held)
        return out
    }

    /**
     * Where the slideshow continues when it runs past the last page: the first index from which the
     * next [window] slides repeat no asset of the last slides before the wrap, so the end of the list
     * does not run straight into its own photos. 0 when no index satisfies that (e.g. the whole list
     * is shorter than [window], which then simply repeats).
     */
    fun wrapTarget(items: List<SliderItemViewHolder>, window: Int = NO_REPEAT_WINDOW): Int {
        val n = items.size
        if (n == 0 || window <= 1) return 0
        // key -> slides from the last page (0 = the last page itself) for the last `window` pages
        val fromEnd = HashMap<String, Int>()
        for (d in 0 until min(window, n)) keysOf(items[n - 1 - d]).forEach { fromEnd.putIfAbsent(it, d) }
        if (fromEnd.isEmpty()) return 0
        val keys = items.map { keysOf(it) }
        for (start in 0 until n) {
            var ok = true
            var k = 0
            // Page start + k is shown k + 1 slides after the last page.
            while (ok && k < window && start + k < n) {
                for (key in keys[start + k]) {
                    val d = fromEnd[key] ?: continue
                    if (k + 1 + d <= window) {
                        ok = false
                        break
                    }
                }
                k++
            }
            if (ok) return start
        }
        return 0
    }

    /**
     * Pause in ms before asking a source again after [emptyLoads] requests in a row added nothing:
     * 15 s, doubling up to 4 minutes.
     */
    fun emptyLoadBackoffMs(emptyLoads: Int): Long {
        if (emptyLoads <= 0) return 0L
        return min(15_000L shl min(emptyLoads - 1, 4), 240_000L)
    }

    /**
     * Whether a page built for [shown] at [position] can stay when the list changes to [items]: only
     * when the very same page object is still at that position. A kept page keeps its view, so its
     * photo is not reloaded and its motion keeps running.
     */
    fun canKeepPage(shown: SliderItemViewHolder?, position: Int, items: List<SliderItemViewHolder>): Boolean =
        shown != null && items.getOrNull(position) === shown

    /**
     * The pages of [incoming] without the assets in [blocked] (which is extended with every asset taken):
     * a page is kept as is when nothing of it is blocked, a pair loses its blocked photo.
     */
    private fun pick(incoming: List<SliderItemViewHolder>, blocked: Set<String>): List<SliderItemViewHolder> {
        val taken = HashSet(blocked)
        val result = ArrayList<SliderItemViewHolder>()
        incoming.forEach { page ->
            val items = listOfNotNull(page.mainItem, page.secondaryItem)
            // add() also catches a page that holds the same asset twice
            val free = items.filter { taken.add(keyOf(it)) }
            if (free.isEmpty()) return@forEach
            result.add(if (free.size == items.size) page else SliderItemViewHolder(free[0]))
        }
        return result
    }

    private fun keysOf(page: SliderItemViewHolder): List<String> =
        listOfNotNull(page.mainItem, page.secondaryItem).map { keyOf(it) }

    /** The asset id; the url for items without one (equality of [SliderItem] is based on the url). */
    private fun keyOf(item: SliderItem): String = item.id.ifEmpty { "url:" + item.url }
}
