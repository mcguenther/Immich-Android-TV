package nl.giejay.mediaslider.util

import nl.giejay.mediaslider.model.SliderItem
import nl.giejay.mediaslider.model.SliderItemViewHolder

/**
 * Pure helpers for changing the slider's page list while it is shown. No Android types, so they are unit tested.
 */
object PageList {

    /**
     * [existing] followed by the pages of [incoming] that show no photo or video already on a page
     * before them. Random sources sample with replacement, and the same asset can come back alone or
     * paired with another portrait photo; such a page is dropped, so an asset is never shown twice
     * (in particular never on two pages in a row, which would look like the photo jumping back).
     * The pages of [existing] are kept as they are (same objects, same positions).
     */
    fun appendNew(existing: List<SliderItemViewHolder>, incoming: List<SliderItemViewHolder>): List<SliderItemViewHolder> {
        if (incoming.isEmpty()) return existing
        val seen = HashSet<String>()
        existing.forEach { page -> keysOf(page).forEach { seen.add(it) } }
        val result = ArrayList<SliderItemViewHolder>(existing.size + incoming.size)
        result.addAll(existing)
        incoming.forEach { page ->
            val keys = keysOf(page)
            if (keys.none { it in seen }) {
                seen.addAll(keys)
                result.add(page)
            }
        }
        return result
    }

    /**
     * Whether a page built for [shown] at [position] can stay when the list changes to [items]: only
     * when the very same page object is still at that position. A kept page keeps its view, so its
     * photo is not reloaded and its motion keeps running.
     */
    fun canKeepPage(shown: SliderItemViewHolder?, position: Int, items: List<SliderItemViewHolder>): Boolean =
        shown != null && items.getOrNull(position) === shown

    private fun keysOf(page: SliderItemViewHolder): List<String> =
        listOfNotNull(page.mainItem, page.secondaryItem).map { keyOf(it) }

    /** The asset id; the url for items without one (equality of [SliderItem] is based on the url). */
    private fun keyOf(item: SliderItem): String = item.id.ifEmpty { "url:" + item.url }
}
