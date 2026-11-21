package nl.giejay.android.tv.immich.shared.prefs

import android.content.res.Resources
import nl.giejay.android.tv.immich.R

/**
 * The selectable "seconds per item" values for the slideshow and the screensaver.
 *
 * Granularity gets coarser the longer the interval is: every second up to 9 s, every 2 s from 10 s up to one minute,
 * and every 10 s from one minute up to ten minutes. All values the old fixed list offered (e.g. 20, 30, 60, 180, 300,
 * 600) are still part of it, so previously stored settings keep working.
 */
object IntervalOptions {
    val seconds: List<Int> = (1..9) + (10..60 step 2) + (70..600 step 10)

    /** "7 seconds", "1 minute", "1 min 10 s" ... in the current locale. */
    fun title(resources: Resources, totalSeconds: Int): String {
        val minutes = totalSeconds / 60
        val rest = totalSeconds % 60
        return when {
            minutes == 0 -> resources.getQuantityString(R.plurals.interval_seconds, rest, rest)
            rest == 0 -> resources.getQuantityString(R.plurals.interval_minutes, minutes, minutes)
            else -> resources.getString(R.string.interval_minutes_seconds, minutes, rest)
        }
    }
}
