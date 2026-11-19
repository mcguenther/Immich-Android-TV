package nl.giejay.android.tv.immich.shared.prefs

import nl.giejay.android.tv.immich.shared.util.SliderItemOptions
import nl.giejay.mediaslider.config.DisplayOptions
import nl.giejay.mediaslider.config.LoadingOptions
import nl.giejay.mediaslider.config.MotionOptions
import nl.giejay.mediaslider.config.TransitionOptions
import nl.giejay.mediaslider.config.VideoPlaybackOptions

/**
 * The single place that turns the slideshow preferences into slider option values. Every
 * screen that opens the slider (albums, folders, timeline, memories, deep links, screensaver)
 * reads its look and behaviour from here, so they cannot drift apart.
 */
object SlideshowSettings {

    fun display(): DisplayOptions = DisplayOptions(
        fillMode = PreferenceManager.get(SLIDESHOW_FILL_MODE).value,
        maxCropPercent = PreferenceManager.get(SLIDESHOW_MAX_CROP),
        fillVideos = PreferenceManager.get(SLIDESHOW_FILL_VIDEOS),
        edgeFill = PreferenceManager.get(SLIDESHOW_EDGE_FILL).value
    )

    fun motion(): MotionOptions = MotionOptions(
        enabled = PreferenceManager.get(SLIDESHOW_MOTION_ENABLED),
        zoomPercent = PreferenceManager.get(SLIDESHOW_MOTION_ZOOM).coerceIn(0, MotionOptions.MAX_ZOOM_PERCENT),
        panPercent = PreferenceManager.get(SLIDESHOW_MOTION_PAN).coerceIn(0, MotionOptions.MAX_PAN_PERCENT),
        style = PreferenceManager.get(SLIDESHOW_MOTION_STYLE).value,
        panoramaScroll = PreferenceManager.get(SLIDESHOW_PANORAMA_SCROLL)
    )

    fun transition(): TransitionOptions = TransitionOptions(
        type = PreferenceManager.get(SLIDESHOW_TRANSITION).value,
        durationMillis = PreferenceManager.get(SLIDESHOW_TRANSITION_DURATION)
    )

    fun loading(): LoadingOptions = LoadingOptions(
        useFullResolution = PreferenceManager.get(SLIDESHOW_IMAGE_SOURCE) == SlideshowImageSource.FULL_RESOLUTION,
        prefetchCount = PreferenceManager.get(SLIDESHOW_PREFETCH_COUNT),
        videoCacheMb = PreferenceManager.get(SLIDESHOW_VIDEO_CACHE_MB)
    )

    fun videoPlayback(): VideoPlaybackOptions = VideoPlaybackOptions(
        cinemaFrameRate = PreferenceManager.get(SLIDESHOW_VIDEO_CINEMA_FPS).fps,
        cinemaOnlyWhenMuted = PreferenceManager.get(SLIDESHOW_VIDEO_CINEMA_ONLY_MUTED)
    )

    /** Options for building slider items from assets; [pairing] can be forced off by callers. */
    fun sliderItems(pairing: PortraitPairing = PreferenceManager.get(SLIDESHOW_PORTRAIT_PAIRING)): SliderItemOptions =
        SliderItemOptions(
            pairing = pairing,
            showPortraitVideos = PreferenceManager.get(SLIDESHOW_SHOW_PORTRAIT_VIDEOS)
        )
}
