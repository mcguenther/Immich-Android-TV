package nl.giejay.mediaslider.util

import kotlin.math.abs

/** Pure navigation decisions of the slider, free of Android types so they can be unit tested. */
object PageJump {
    /**
     * Whether moving the pager from [current] to [target] may be animated. A smooth `setCurrentItem`
     * scrolls through every page in between; with the pinned crossfade the pager is then nearly empty
     * (the in-between pages are not loaded or fully transparent), and a slide would sweep the whole
     * list past. So only neighbouring pages animate; a wrap-around (last to first and back) is a
     * hard cut, which is calmer than a black gap or a fast sweep.
     */
    fun smooth(current: Int, target: Int, smoothEnabled: Boolean): Boolean =
        smoothEnabled && abs(target - current) <= 1
}
