package nl.giejay.mediaslider.config

/**
 * Option groups that drive how the slideshow looks and behaves. They are plain values so the
 * hosting app can build them from its own preferences and the slider stays free of app types.
 */

/** How a photo or video is scaled into its slot (the full screen, or one half for portrait pairs). */
enum class FillMode {
    /** Show the whole asset; empty space is filled according to [EdgeFill]. */
    FIT,

    /** Scale until the slot is covered (like CSS `object-fit: cover`), cropping the overflow. */
    FILL
}

/** What fills the empty space next to an asset that does not cover its slot. */
enum class EdgeFill { BLUR, BLACK }

data class DisplayOptions(
    val fillMode: FillMode = FillMode.FILL,
    /**
     * Largest share (0-100) of one image edge that [FillMode.FILL] may crop away. Assets that
     * would need more are shown with [FillMode.FIT] instead. [UNLIMITED_CROP] disables the limit.
     */
    val maxCropPercent: Int = 30,
    /** Apply [fillMode] to landscape videos too. Portrait videos are never cropped. */
    val fillVideos: Boolean = true,
    val edgeFill: EdgeFill = EdgeFill.BLUR
) {
    companion object {
        const val UNLIMITED_CROP = 100
    }
}

enum class MotionStyle { MIXED, ZOOM_ONLY, PAN_ONLY }

/**
 * Slow "Ken Burns" style camera motion on photos. Percentages are deliberately small:
 * [zoomPercent] is the total scale change over one slide (5 = 1.00 -> 1.05) and [panPercent]
 * the total travel as a share of the slot width.
 */
data class MotionOptions(
    val enabled: Boolean = true,
    val zoomPercent: Int = 5,
    val panPercent: Int = 3,
    val style: MotionStyle = MotionStyle.MIXED,
    /** Scroll very wide/tall panoramas across the screen. Independent of [enabled]. */
    val panoramaScroll: Boolean = true
) {
    companion object {
        const val MAX_ZOOM_PERCENT = 12
        const val MAX_PAN_PERCENT = 8
    }
}

enum class TransitionType { CROSSFADE, SLIDE, NONE }

data class TransitionOptions(
    val type: TransitionType = TransitionType.CROSSFADE,
    val durationMillis: Int = 1000
)

data class LoadingOptions(
    /** Load the original (decoded at screen size) instead of Immich's preview thumbnail. */
    val useFullResolution: Boolean = true,
    /** Number of upcoming slides (a portrait pair counts as one) to load ahead. 0 disables. */
    val prefetchCount: Int = 3,
    /** Disk cache size for pre-buffered video starts. */
    val videoCacheMb: Int = 500
)

/** Video playback tweaks. */
data class VideoPlaybackOptions(
    /** Target frame rate (e.g. 24f or 23.976f) faster videos are slowed down to; null plays at normal speed. */
    val cinemaFrameRate: Float? = null,
    /** Apply [cinemaFrameRate] only while the video plays without sound. */
    val cinemaOnlyWhenMuted: Boolean = true
)
