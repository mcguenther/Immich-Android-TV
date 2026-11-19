package nl.giejay.android.tv.immich.shared.prefs

import androidx.annotation.StringRes
import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.R
import nl.giejay.mediaslider.config.EdgeFill
import nl.giejay.mediaslider.config.FillMode
import nl.giejay.mediaslider.config.MotionStyle
import nl.giejay.mediaslider.config.TransitionType

/*
 * Preference-facing enums for the slideshow settings. Each carries its display title and maps to
 * the matching value of the slider library, which must not depend on app resources.
 */

private fun title(@StringRes res: Int): String = ImmichApplication.appContext!!.getString(res)

enum class SlideshowFillMode(@StringRes private val titleRes: Int, val value: FillMode) : EnumWithTitle {
    FIT(R.string.slideshow_fill_mode_fit, FillMode.FIT),
    FILL(R.string.slideshow_fill_mode_fill, FillMode.FILL);

    override fun getTitle() = title(titleRes)
}

enum class SlideshowEdgeFill(@StringRes private val titleRes: Int, val value: EdgeFill) : EnumWithTitle {
    BLUR(R.string.slideshow_edge_fill_blur, EdgeFill.BLUR),
    BLACK(R.string.slideshow_edge_fill_black, EdgeFill.BLACK);

    override fun getTitle() = title(titleRes)
}

enum class SlideshowMotionStyle(@StringRes private val titleRes: Int, val value: MotionStyle) : EnumWithTitle {
    MIXED(R.string.slideshow_motion_style_mixed, MotionStyle.MIXED),
    ZOOM_ONLY(R.string.slideshow_motion_style_zoom, MotionStyle.ZOOM_ONLY),
    PAN_ONLY(R.string.slideshow_motion_style_pan, MotionStyle.PAN_ONLY);

    override fun getTitle() = title(titleRes)
}

enum class SlideshowTransition(@StringRes private val titleRes: Int, val value: TransitionType) : EnumWithTitle {
    CROSSFADE(R.string.slideshow_transition_crossfade, TransitionType.CROSSFADE),
    SLIDE(R.string.slideshow_transition_slide, TransitionType.SLIDE),
    NONE(R.string.slideshow_transition_none, TransitionType.NONE);

    override fun getTitle() = title(titleRes)
}

/** Target frame rate for cinema-style video playback; [fps] null means off. */
enum class CinemaFrameRateOption(@StringRes private val titleRes: Int, val fps: Float?) : EnumWithTitle {
    OFF(R.string.slideshow_video_cinema_fps_off, null),
    FPS_24(R.string.slideshow_video_cinema_fps_24, 24f),
    FPS_23_976(R.string.slideshow_video_cinema_fps_23976, 23.976f);

    override fun getTitle() = title(titleRes)
}

/** Which two portrait photos may be shown side by side. */
enum class PortraitPairing(@StringRes private val titleRes: Int) : EnumWithTitle {
    /** Portraits are always shown alone. */
    OFF(R.string.slideshow_pairing_off),

    /** Both photos were taken on the same local calendar day. */
    SAME_DAY(R.string.slideshow_pairing_same_day),

    /** Same local calendar day, or the same city (and country) from EXIF on any day. */
    SAME_DAY_OR_PLACE(R.string.slideshow_pairing_same_day_or_place);

    override fun getTitle() = title(titleRes)
}

enum class SlideshowImageSource(@StringRes private val titleRes: Int) : EnumWithTitle {
    FULL_RESOLUTION(R.string.slideshow_image_source_full),
    PREVIEW(R.string.slideshow_image_source_preview);

    override fun getTitle() = title(titleRes)
}

internal inline fun <reified E : Enum<E>> enumValueOfOr(name: String, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default
