package nl.giejay.android.tv.immich.shared.prefs

import android.content.SharedPreferences

/**
 * One-time move from the pre-redesign slideshow preferences to the new keys.
 *
 * Old values are read by their raw keys (the old Pref objects no longer exist). Old zoom/pan
 * percentages are intentionally not carried over: on the old 0-100 scale, 50 meant zooming to
 * 1.25x-2x, which is far outside the new calm range.
 */
object SlideshowPrefsMigration {
    internal const val DONE_KEY = "slideshow_prefs_migrated_v1"

    // keys of the removed Pref objects (Pref.key() defaults to the lowercased object name)
    internal const val OLD_GLIDE_TRANSFORMATION = "slider_glide_transformation"
    internal const val OLD_MAX_CUT_OFF_HEIGHT = "slider_max_cut_off_height"
    internal const val OLD_MERGE_PORTRAIT = "slider_merge_portrait_photos"
    internal const val OLD_ZOOM_SCROLL_PANORAMAS = "slider_zoom_scroll_panoramas"
    internal const val OLD_ANIMATION_SPEED = "slider_animation_speed"
    internal const val OLD_SCREENSAVER_ANIMATE_SLIDE = "screensaver_animate_asset_slide"
    internal val OLD_KEYS = listOf(
        OLD_GLIDE_TRANSFORMATION, OLD_MAX_CUT_OFF_HEIGHT, "slider_max_cut_off_width", OLD_MERGE_PORTRAIT,
        OLD_ZOOM_SCROLL_PANORAMAS, "slider_zoom_effect", "slider_pan_effect", OLD_ANIMATION_SPEED,
        OLD_SCREENSAVER_ANIMATE_SLIDE, "slider_only_use_thumbnails"
    )

    fun migrateIfNeeded(prefs: SharedPreferences) {
        if (prefs.getBoolean(DONE_KEY, false)) return
        val edit = prefs.edit()

        // Display: Center Inside becomes the new default (fill with 30% limit) on purpose;
        // explicit crop choices keep cropping.
        when (prefs.getString(OLD_GLIDE_TRANSFORMATION, null)) {
            "CENTER_CROP" -> edit.putString(SLIDESHOW_MAX_CROP.key(), "100")
            "SAFE_CENTER_CROP" -> {
                val old = prefs.all[OLD_MAX_CUT_OFF_HEIGHT] as? Int
                if (old != null) edit.putString(SLIDESHOW_MAX_CROP.key(), nearestMaxCrop(old).toString())
            }
        }

        if (prefs.contains(OLD_MERGE_PORTRAIT) && !prefs.getBoolean(OLD_MERGE_PORTRAIT, true)) {
            edit.putString(SLIDESHOW_PORTRAIT_PAIRING.key(), PortraitPairing.OFF.name)
        }

        if (prefs.contains(OLD_ZOOM_SCROLL_PANORAMAS)) {
            val on = prefs.getBoolean(OLD_ZOOM_SCROLL_PANORAMAS, false)
            edit.putBoolean(SLIDESHOW_MOTION_ENABLED.key(), on)
            edit.putBoolean(SLIDESHOW_PANORAMA_SCROLL.key(), on)
        }

        // "Animate slide" off meant no transition at all; otherwise crossfade is the new default.
        if (prefs.contains(OLD_SCREENSAVER_ANIMATE_SLIDE) && !prefs.getBoolean(OLD_SCREENSAVER_ANIMATE_SLIDE, true)) {
            edit.putString(SLIDESHOW_TRANSITION.key(), SlideshowTransition.NONE.name)
        }

        // Thumbnails were on by default and the stored value cannot tell a deliberate choice
        // from the default, so everyone moves to the new full-resolution default.

        OLD_KEYS.forEach { edit.remove(it) }
        edit.putBoolean(DONE_KEY, true)
        edit.apply()
    }

    private val CROP_STEPS = listOf(15, 20, 25, 30, 40, 50)

    internal fun nearestMaxCrop(value: Int): Int = CROP_STEPS.minBy { kotlin.math.abs(it - value) }
}
