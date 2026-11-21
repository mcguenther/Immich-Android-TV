package nl.giejay.android.tv.immich.shared.prefs

import android.content.Context
import androidx.preference.ListPreference

/**
 * A [ListPreference] (so storage, entries and the summary logic stay as they are) that opens the minutes/seconds
 * [IntervalPickerDialog] instead of the plain list when clicked.
 */
class IntervalPreference(context: Context) : ListPreference(context) {
    override fun onClick() {
        val current = value?.toIntOrNull() ?: IntervalOptions.seconds.first()
        IntervalPickerDialog(context, title, dialogTitle, current) { picked ->
            val newValue = picked.toString()
            if (callChangeListener(newValue)) value = newValue
        }.show()
    }
}
