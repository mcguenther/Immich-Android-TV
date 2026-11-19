package nl.giejay.android.tv.immich.shared.prefs

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import nl.giejay.android.tv.immich.ImmichApplication
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * Covers the one-time move of the old slideshow preferences to the new keys.
 * The new Pref objects read their labels from the application context, which is mocked here.
 */
class SlideshowPrefsMigrationTest {

    private lateinit var prefs: InMemorySharedPreferences

    @Before
    fun setUp() {
        ImmichApplication.appContext = mock<Context> {
            on { getString(any<Int>()) } doReturn ""
        }
        prefs = InMemorySharedPreferences()
    }

    @After
    fun tearDown() {
        ImmichApplication.appContext = null
    }

    private fun migrate() = SlideshowPrefsMigration.migrateIfNeeded(prefs)

    private fun newValue(pref: Pref<*, *, *>): String? = prefs.getString(pref.key(), null)

    @Test
    fun `center crop becomes no cut-off at all`() {
        prefs.edit().putString(SlideshowPrefsMigration.OLD_GLIDE_TRANSFORMATION, "CENTER_CROP").apply()

        migrate()

        assertThat(newValue(SLIDESHOW_MAX_CROP)).isEqualTo("100")
    }

    @Test
    fun `safe center crop keeps the stored cut-off`() {
        prefs.edit()
            .putString(SlideshowPrefsMigration.OLD_GLIDE_TRANSFORMATION, "SAFE_CENTER_CROP")
            .putInt(SlideshowPrefsMigration.OLD_MAX_CUT_OFF_HEIGHT, 25)
            .apply()

        migrate()

        assertThat(newValue(SLIDESHOW_MAX_CROP)).isEqualTo("25")
    }

    @Test
    fun `safe center crop without a stored cut-off maps to the old default 20`() {
        prefs.edit().putString(SlideshowPrefsMigration.OLD_GLIDE_TRANSFORMATION, "SAFE_CENTER_CROP").apply()

        migrate()

        assertThat(newValue(SLIDESHOW_MAX_CROP)).isEqualTo("20")
    }

    @Test
    fun `stored cut-off is snapped to the nearest new step`() {
        prefs.edit()
            .putString(SlideshowPrefsMigration.OLD_GLIDE_TRANSFORMATION, "SAFE_CENTER_CROP")
            .putInt(SlideshowPrefsMigration.OLD_MAX_CUT_OFF_HEIGHT, 22)
            .apply()

        migrate()

        assertThat(newValue(SLIDESHOW_MAX_CROP)).isEqualTo("20")
    }

    @Test
    fun `no old transformation leaves the crop default alone`() {
        migrate()

        assertThat(newValue(SLIDESHOW_MAX_CROP)).isNull()
    }

    @Test
    fun `merge portrait photos off turns portrait pairing off`() {
        prefs.edit().putBoolean(SlideshowPrefsMigration.OLD_MERGE_PORTRAIT, false).apply()

        migrate()

        assertThat(newValue(SLIDESHOW_PORTRAIT_PAIRING)).isEqualTo(PortraitPairing.OFF.name)
    }

    @Test
    fun `merge portrait photos on keeps the pairing default`() {
        prefs.edit().putBoolean(SlideshowPrefsMigration.OLD_MERGE_PORTRAIT, true).apply()

        migrate()

        assertThat(newValue(SLIDESHOW_PORTRAIT_PAIRING)).isNull()
    }

    @Test
    fun `zoom scroll panoramas off turns motion and panorama scrolling off`() {
        prefs.edit().putBoolean(SlideshowPrefsMigration.OLD_ZOOM_SCROLL_PANORAMAS, false).apply()

        migrate()

        assertThat(prefs.getBoolean(SLIDESHOW_MOTION_ENABLED.key(), true)).isFalse()
        assertThat(prefs.getBoolean(SLIDESHOW_PANORAMA_SCROLL.key(), true)).isFalse()
    }

    @Test
    fun `animate slide off becomes no transition`() {
        prefs.edit().putBoolean(SlideshowPrefsMigration.OLD_SCREENSAVER_ANIMATE_SLIDE, false).apply()

        migrate()

        assertThat(newValue(SLIDESHOW_TRANSITION)).isEqualTo(SlideshowTransition.NONE.name)
    }

    @Test
    fun `animate slide on keeps crossfade as the new default instead of slide`() {
        prefs.edit().putBoolean(SlideshowPrefsMigration.OLD_SCREENSAVER_ANIMATE_SLIDE, true).apply()

        migrate()

        assertThat(newValue(SLIDESHOW_TRANSITION)).isNull()
    }

    @Test
    fun `runs only once`() {
        prefs.edit().putString(SlideshowPrefsMigration.OLD_GLIDE_TRANSFORMATION, "CENTER_CROP").apply()
        migrate()

        // a second run with different old values must not change the migrated result
        prefs.edit().putString(SlideshowPrefsMigration.OLD_GLIDE_TRANSFORMATION, "SAFE_CENTER_CROP").apply()
        migrate()

        assertThat(newValue(SLIDESHOW_MAX_CROP)).isEqualTo("100")
        assertThat(prefs.getBoolean(SlideshowPrefsMigration.DONE_KEY, false)).isTrue()
    }

    @Test
    fun `old keys are removed after migration`() {
        SlideshowPrefsMigration.OLD_KEYS.forEach { prefs.edit().putString(it, "1").apply() }

        migrate()

        SlideshowPrefsMigration.OLD_KEYS.forEach { assertThat(prefs.contains(it)).isFalse() }
    }
}

/** Minimal in-memory [SharedPreferences] for JVM tests; apply and commit write synchronously. */
private class InMemorySharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = put(key, values)
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)

        override fun remove(key: String?): SharedPreferences.Editor = put(key, REMOVED)

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            flush()
            return true
        }

        override fun apply() = flush()

        private fun put(key: String?, value: Any?): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        private fun flush() {
            if (clearAll) values.clear()
            pending.forEach { (key, value) ->
                if (value === REMOVED) values.remove(key) else values[key] = value
            }
            pending.clear()
        }
    }

    private companion object {
        val REMOVED = Any()
    }
}
