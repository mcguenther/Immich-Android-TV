package nl.giejay.android.tv.immich.settings

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.shared.prefs.ActionPref
import nl.giejay.android.tv.immich.shared.prefs.PrefScreen
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SLIDESHOW_FILL_MODE
import nl.giejay.android.tv.immich.shared.prefs.SLIDESHOW_MAX_CROP
import nl.giejay.android.tv.immich.shared.prefs.SLIDESHOW_MOTION_ENABLED
import nl.giejay.android.tv.immich.shared.prefs.SLIDESHOW_MOTION_ZOOM
import nl.giejay.android.tv.immich.shared.prefs.SLIDESHOW_TRANSITION
import nl.giejay.android.tv.immich.shared.prefs.SLIDESHOW_TRANSITION_DURATION
import nl.giejay.android.tv.immich.shared.prefs.SlideshowDisplayPrefScreen
import nl.giejay.android.tv.immich.shared.prefs.SlideshowFillMode
import nl.giejay.android.tv.immich.shared.prefs.SlideshowMotionPrefScreen
import nl.giejay.android.tv.immich.shared.prefs.SlideshowTimingPrefScreen
import nl.giejay.android.tv.immich.shared.prefs.SlideshowVideosPrefScreen
import nl.giejay.android.tv.immich.shared.prefs.SLIDESHOW_VIDEO_CLIP_LENGTH
import nl.giejay.android.tv.immich.shared.prefs.SLIDESHOW_VIDEO_CLIP_POSITION
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SettingsMenuTest {
    @Before
    fun setUp() {
        val context: Context = mock()
        whenever(context.getString(any())).thenAnswer { "res-${it.getArgument<Int>(0)}" }
        ImmichApplication.appContext = context
    }

    @After
    fun tearDown() {
        ImmichApplication.appContext = null
    }

    private fun screens() = PreferenceManager.subclasses(PrefScreen::class).mapNotNull { it.objectInstance }

    @Test
    fun screenKeysAreUnique() {
        val keys = screens().map { it.key }
        assertThat(keys).containsNoDuplicates()
    }

    @Test
    fun everyNavigationEntryOfTheMenuScreensResolvesToAScreen() {
        val screenKeys = screens().map { it.key }.toSet()
        val notScreens = setOf("screensaver_start_now", "screensaver_set")
        val menus = screens().filter { it.key in setOf("view", "slideshow", "screensaver") }
        assertThat(menus).hasSize(3)
        menus.forEach { menu ->
            menu.children.flatMap { it.children }.filterIsInstance<ActionPref>()
                .map { it.key() }
                .filter { it !in notScreens }
                .forEach { key -> assertThat(screenKeys).contains(key) }
        }
    }

    @Test
    fun dependentOptionsFollowTheirSwitch() {
        val prefs: SharedPreferences = mock()
        whenever(prefs.getString(eq(SLIDESHOW_FILL_MODE.key()), any())).thenReturn(SlideshowFillMode.FIT.toString())
        whenever(prefs.getString(eq(SLIDESHOW_TRANSITION.key()), any())).thenReturn("NONE")
        whenever(prefs.getBoolean(eq(SLIDESHOW_MOTION_ENABLED.key()), any())).thenReturn(false)

        assertThat(SlideshowDisplayPrefScreen.visibility.getValue(SLIDESHOW_MAX_CROP)(prefs)).isFalse()
        assertThat(SlideshowTimingPrefScreen.visibility.getValue(SLIDESHOW_TRANSITION_DURATION)(prefs)).isFalse()
        assertThat(SlideshowMotionPrefScreen.visibility.getValue(SLIDESHOW_MOTION_ZOOM)(prefs)).isFalse()

        whenever(prefs.getString(eq(SLIDESHOW_FILL_MODE.key()), any())).thenReturn(SlideshowFillMode.FILL.toString())
        whenever(prefs.getString(eq(SLIDESHOW_TRANSITION.key()), any())).thenReturn("SLIDE")
        whenever(prefs.getBoolean(eq(SLIDESHOW_MOTION_ENABLED.key()), any())).thenReturn(true)

        assertThat(SlideshowDisplayPrefScreen.visibility.getValue(SLIDESHOW_MAX_CROP)(prefs)).isTrue()
        assertThat(SlideshowTimingPrefScreen.visibility.getValue(SLIDESHOW_TRANSITION_DURATION)(prefs)).isTrue()
        assertThat(SlideshowMotionPrefScreen.visibility.getValue(SLIDESHOW_MOTION_ZOOM)(prefs)).isTrue()
    }

    @Test
    fun clipPositionIsOnlyVisibleWhenAClipLengthIsSet() {
        val prefs: SharedPreferences = mock()
        val visible = SlideshowVideosPrefScreen.visibility.getValue(SLIDESHOW_VIDEO_CLIP_POSITION)
        whenever(prefs.getString(eq(SLIDESHOW_VIDEO_CLIP_LENGTH.key()), any())).thenReturn("0")
        assertThat(visible(prefs)).isFalse()
        whenever(prefs.getString(eq(SLIDESHOW_VIDEO_CLIP_LENGTH.key()), any())).thenReturn("-1")
        assertThat(visible(prefs)).isTrue()
    }
}
