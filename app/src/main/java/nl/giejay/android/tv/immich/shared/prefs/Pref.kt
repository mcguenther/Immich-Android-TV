package nl.giejay.android.tv.immich.shared.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.navigation.NavController
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.SeekBarPreference
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager.sharedPreference


/**
 * [ListPreference.getSummary] runs its summary through [String.format], so a literal '%' (e.g. "30%" or
 * "4:3 needs 25%, ...") throws UnknownFormatConversionException and crashes the settings screen.
 * Always set ListPreference summaries through this escape.
 */
fun listSummary(text: CharSequence?): String? = text?.toString()?.replace("%", "%%")

/**
 * A screen of preferences.
 *
 * @param visibility optional rules that show a preference only while the rule returns true. The rules are evaluated when
 * the screen opens and re-evaluated every time a preference changes, so dependent options appear and disappear live.
 */
sealed class PrefScreen(val title: String,
                        val key: String,
                        val children: List<PrefCategory>,
                        val onViewCreated: (PreferenceManager) -> Unit = {},
                        val visibility: Map<Pref<*, *, *>, (SharedPreferences) -> Boolean> = emptyMap()) {
    fun findByKey(key: String): Pref<*, *, *>? {
        return children.firstNotNullOfOrNull { it.findByKey(key) }
    }
}

data class PrefCategory(val title: String, val children: List<Pref<*, *, *>>) {
    fun findByKey(key: String): Pref<*, *, *>? {
        return children.find { it.key() == key }
    }
}

sealed class Pref<T, PREF : Preference, PREFTYPE>(val defaultValue: T, val title: String, val summary: String) {
    abstract fun getValue(sharedPreferences: SharedPreferences): T

    open fun key() = javaClass.simpleName.lowercase()
    abstract fun save(sharedPreferences: SharedPreferences, value: T)
    open fun fromPrefValue(prefValue: PREFTYPE): T {
        // by default stored pref value and the type of the Pref is the same, but it can differ with for example ListPreferences<Int>
        // stored by String values
        return prefValue as T
    }

    open fun onClick(context: Context, controller: NavController): Boolean {
        // can be implemented by children
        return false
    }

    open fun toPrefValue(value: T): PREFTYPE = value as PREFTYPE

    open fun createPreference(context: Context): PREF {
        val createPref = createPref(context)
        createPref.key = key()
        createPref.title = title
        createPref.setDefaultValue(toPrefValue(defaultValue))
        createPref.summary = if (createPref is ListPreference) listSummary(summary) else summary
        return createPref
    }

    abstract fun createPref(context: Context): PREF
}

sealed class NotUserEditableStringPref(title: String, summary: String) : Pref<String, Preference, String>("", title, summary) {
    override fun save(sharedPreferences: SharedPreferences, value: String) {
        sharedPreference.edit().putString(key(), value).apply()
    }

    override fun createPref(context: Context): Preference {
        return Preference(context)
    }

    override fun getValue(sharedPreferences: SharedPreferences): String {
        return ""
    }
}

open class ActionPref(val keyOverride: String? = null, title: String, summary: String, val onClick: (Context, NavController) -> Boolean) : Pref<String, Preference, String>("",
    title,
    summary) {
    override fun key(): String {
        return keyOverride ?: super.key()
    }
    override fun save(sharedPreferences: SharedPreferences, value: String) {

    }

    override fun getValue(sharedPreferences: SharedPreferences): String {
        return ""
    }

    override fun onClick(context: Context, controller: NavController): Boolean {
        return onClick.invoke(context, controller)
    }

    override fun createPref(context: Context): Preference {
        val preference = Preference(context)
        preference.key = key()
        return preference
    }
}

sealed class EnumPref<T>(defaultValue: T, title: String, summary: String,
                         val titlesResourceId: Int, val valuesResourceId: Int) : Pref<T, ListPreference, String>
    (defaultValue, title, summary) where T : Enum<T> {
    override fun save(sharedPreferences: SharedPreferences, value: T) {
        sharedPreferences.edit().putString(key(), value.toString()).apply()
    }

    override fun toPrefValue(value: T): String {
        return value.toString()
    }

    override fun getValue(sharedPreferences: SharedPreferences): T {
        return fromPrefValue(sharedPreferences.getString(key(), toPrefValue(defaultValue))!!)
    }

    abstract override fun fromPrefValue(prefValue: String): T

    override fun createPref(context: Context): ListPreference {
        val listPreference = ListPreference(context)
        listPreference.setEntries(titlesResourceId)
        listPreference.dialogTitle = summary
        listPreference.setEntryValues(valuesResourceId)
        return listPreference
    }
}

sealed class EnumByTitlePref<T>(defaultValue: T, title: String, summary: String)  : Pref<T, ListPreference, String>(defaultValue, title, summary) where T : Enum<T>, T : EnumWithTitle {
    override fun save(sharedPreferences: SharedPreferences, value: T) {
        sharedPreferences.edit().putString(key(), value.toString()).apply()
    }

    override fun toPrefValue(value: T): String {
        return value.toString()
    }

    abstract override fun fromPrefValue(prefValue: String): T

    override fun getValue(sharedPreferences: SharedPreferences): T {
        return fromPrefValue(sharedPreferences.getString(key(), toPrefValue(defaultValue))!!)
    }

    abstract fun getEnumEntries(): Array<T>

    override fun createPref(context: Context): ListPreference {
        val listPreference = ListPreference(context)
        listPreference.entries = getEnumEntries().map { it.getTitle() }.toTypedArray()
        listPreference.dialogTitle = summary
        listPreference.entryValues = getEnumEntries().map { it.toString() }.toTypedArray()
        return listPreference
    }
}

sealed class BooleanPref(defaultValue: Boolean, title: String, summary: String) : Pref<Boolean, CheckBoxPreference, Boolean>(defaultValue,
    title,
    summary) {
    override fun save(sharedPreferences: SharedPreferences, value: Boolean) {
        sharedPreference.edit().putBoolean(key(), value).apply()
    }

    override fun createPref(context: Context): CheckBoxPreference {
        return CheckBoxPreference(context)
    }

    override fun getValue(sharedPreferences: SharedPreferences): Boolean {
        return sharedPreferences.getBoolean(key(), defaultValue)
    }
}

sealed class StringPref(defaultValue: String, title: String, summary: String) : Pref<String, EditTextPreference, String>(defaultValue,
    title,
    summary) {
    override fun save(sharedPreferences: SharedPreferences, value: String) {
        sharedPreference.edit().putString(key(), value).apply()
    }

    override fun createPref(context: Context): EditTextPreference {
        return EditTextPreference(context)
    }

    override fun getValue(sharedPreferences: SharedPreferences): String {
        return sharedPreferences.getString(key(), defaultValue)!!
    }
}

sealed class StringSetPref(defaultValue: Set<String>, title: String, summary: String) : Pref<Set<String>, Preference, Set<String>>(defaultValue,
    title,
    summary) {
    override fun save(sharedPreferences: SharedPreferences, value: Set<String>) {
        sharedPreference.edit().putStringSet(key(), value).apply()
    }

    override fun createPref(context: Context): Preference {
        return Preference(context)
    }

    override fun getValue(sharedPreferences: SharedPreferences): Set<String> {
        return sharedPreferences.getStringSet(key(), defaultValue)!!
    }
}

sealed class IntSeekbarPref(defaultValue: Int, title: String, summary: String,
                             val min: Int = 0, val max: Int = 100) : Pref<Int, SeekBarPreference, Int>(defaultValue, title, summary) {
    override fun save(sharedPreferences: SharedPreferences, value: Int) {
        sharedPreference.edit().putInt(key(), value.coerceIn(min, max)).apply()
    }

    override fun createPref(context: Context): SeekBarPreference {
        val seekBar = SeekBarPreference(context)
        seekBar.min = min
        seekBar.max = max
        seekBar.showSeekBarValue = true
        return seekBar
    }

    override fun getValue(sharedPreferences: SharedPreferences): Int {
        return sharedPreferences.getInt(key(), defaultValue).coerceIn(min, max)
    }
}

sealed class IntListPref(defaultValue: Int, title: String, summary: String,
                         val titlesResourceId: Int, val valuesResourceId: Int) : Pref<Int, ListPreference, String>(defaultValue, title, summary) {
    override fun save(sharedPreferences: SharedPreferences, value: Int) {
        sharedPreference.edit().putString(key(), value.toString()).apply()
    }

    override fun toPrefValue(value: Int): String {
        return value.toString()
    }

    override fun fromPrefValue(prefValue: String): Int {
        return prefValue.toInt()
    }

    /** Entry titles and values shown in the list. Defaults to the string arrays; override to build them at runtime. */
    protected open fun entries(context: Context): Pair<Array<CharSequence>, Array<CharSequence>> =
        context.resources.getTextArray(titlesResourceId) to context.resources.getTextArray(valuesResourceId)

    override fun createPref(context: Context): ListPreference {
        val listPreference = ListPreference(context)
        val (titles, values) = entries(context)
        listPreference.entries = titles
        listPreference.dialogTitle = summary
        listPreference.entryValues = values
        return listPreference
    }

    override fun getValue(sharedPreferences: SharedPreferences): Int {
        return sharedPreferences.getString(key(), defaultValue.toString())!!.toInt()
    }
}

/**
 * Seconds-per-item setting for the slideshow and screensaver. The options are generated rather than listed in XML:
 * 1 s steps up to 9 s, 2 s steps from 10 s to 1 minute and 10 s steps from there on (see [IntervalOptions]).
 * It is edited with a minutes/seconds picker ([IntervalPickerDialog]); the entries only serve the summary text.
 */
sealed class IntervalPref(defaultValue: Int, title: String, summary: String) :
    IntListPref(defaultValue, title, summary, 0, 0) {
    override fun createPref(context: Context): ListPreference {
        val listPreference = IntervalPreference(context)
        val (titles, values) = entries(context)
        listPreference.entries = titles
        listPreference.dialogTitle = summary
        listPreference.entryValues = values
        return listPreference
    }

    override fun entries(context: Context): Pair<Array<CharSequence>, Array<CharSequence>> {
        val seconds = IntervalOptions.seconds
        return seconds.map<Int, CharSequence> { IntervalOptions.title(context.resources, it) }.toTypedArray() to
                seconds.map<Int, CharSequence> { it.toString() }.toTypedArray()
    }
}
