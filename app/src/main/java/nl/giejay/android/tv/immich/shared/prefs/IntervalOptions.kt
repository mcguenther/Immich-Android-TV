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

    /** Largest number of whole minutes among the options (10). */
    val maxMinutes: Int = seconds.last() / 60

    /** The option closest to [totalSeconds]; on a tie the larger one. */
    fun snap(totalSeconds: Int): Int =
        seconds.minWithOrNull(compareBy({ kotlin.math.abs(it - totalSeconds) }, { -it }))!!

    /**
     * Changes the minutes part of [value] by [delta] and keeps the seconds part as far as the new minute allows,
     * e.g. 0:06 + 1 minute becomes 1:10. Returns [value] unchanged at the ends of the range.
     */
    fun stepMinutes(value: Int, delta: Int): Int {
        val minutes = value / 60 + delta
        if (minutes < 0 || minutes > maxMinutes) return value
        return snap(minutes * 60 + value % 60)
    }

    /**
     * Moves to the neighbouring option, as the seconds column of a clock does: past 0:58 comes 1:00, past 1:50 comes
     * 2:00. Returns [value] unchanged at the ends of the range.
     */
    fun stepSeconds(value: Int, delta: Int): Int = seconds.getOrNull(seconds.indexOf(value) + delta) ?: value

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
