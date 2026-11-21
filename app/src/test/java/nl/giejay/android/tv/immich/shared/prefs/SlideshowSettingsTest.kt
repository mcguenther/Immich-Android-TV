package nl.giejay.android.tv.immich.shared.prefs

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import nl.giejay.android.tv.immich.ImmichApplication
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Covers which preferences [SlideshowSettings.sliderOptions] reads for each screen. */
class SlideshowSettingsTest {

    private val values = mutableMapOf<String, Any?>()

    @Before
    fun setUp() {
        ImmichApplication.appContext = mock<Context> {
            on { getString(any<Int>()) } doReturn ""
        }
        val prefs = mock<SharedPreferences>()
        whenever(prefs.getString(any(), anyOrNull())).thenAnswer { values[it.getArgument(0)] as? String ?: it.getArgument(1) }
        whenever(prefs.getBoolean(any(), any())).thenAnswer { values[it.getArgument(0)] as? Boolean ?: it.getArgument(1) }
        whenever(prefs.getInt(any(), any())).thenAnswer { values[it.getArgument(0)] as? Int ?: it.getArgument(1) }
        whenever(prefs.contains(any())).thenAnswer { values.containsKey(it.getArgument<String>(0)) }
        PreferenceManager.sharedPreference = prefs
        liveContext().clear()
    }

    @After
    fun tearDown() {
        liveContext().clear()
        ImmichApplication.appContext = null
    }

    @Suppress("UNCHECKED_CAST")
    private fun liveContext(): MutableMap<String, Any?> =
        PreferenceManager.javaClass.getDeclaredField("liveContext").apply { isAccessible = true }
            .get(PreferenceManager) as MutableMap<String, Any?>

    @Test
    fun `viewer reads the slider location map preference`() {
        values[SLIDER_SHOW_LOCATION_MAP.key()] = false
        values[SCREENSAVER_SHOW_LOCATION_MAP.key()] = true

        assertThat(SlideshowSettings.sliderOptions(MetaDataScreen.VIEWER).showLocationMap).isFalse()
        assertThat(SlideshowSettings.sliderOptions(MetaDataScreen.SCREENSAVER).showLocationMap).isTrue()
    }

    @Test
    fun `screensaver reads the screensaver location map preference`() {
        values[SLIDER_SHOW_LOCATION_MAP.key()] = true
        values[SCREENSAVER_SHOW_LOCATION_MAP.key()] = false

        assertThat(SlideshowSettings.sliderOptions(MetaDataScreen.VIEWER).showLocationMap).isTrue()
        assertThat(SlideshowSettings.sliderOptions(MetaDataScreen.SCREENSAVER).showLocationMap).isFalse()
    }

    @Test
    fun `option groups are shared by all screens`() {
        values[SLIDESHOW_MOTION_ENABLED.key()] = false
        values[SLIDESHOW_TRANSITION_DURATION.key()] = "400"

        MetaDataScreen.entries.forEach { screen ->
            val options = SlideshowSettings.sliderOptions(screen)
            assertThat(options.display).isEqualTo(SlideshowSettings.display())
            assertThat(options.motion).isEqualTo(SlideshowSettings.motion())
            assertThat(options.transition).isEqualTo(SlideshowSettings.transition())
            assertThat(options.loading).isEqualTo(SlideshowSettings.loading())
            assertThat(options.videoPlayback).isEqualTo(SlideshowSettings.videoPlayback())
            assertThat(options.motion.enabled).isFalse()
            assertThat(options.transition.durationMillis).isEqualTo(400)
        }
    }
}
