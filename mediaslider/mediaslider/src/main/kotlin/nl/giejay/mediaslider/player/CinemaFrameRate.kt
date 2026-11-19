package nl.giejay.mediaslider.player

/** Cinema frame rate helpers: slow faster videos down so they play at a film frame rate. */
object CinemaFrameRate {
    /** Sources up to this far above the target are left alone (24.5 fps for a 24 fps target). */
    const val TOLERANCE_FPS = 0.5f

    /** Slowest speed ever applied, so 60+ fps and phone slow-mo clips are not slowed further. */
    const val MIN_SPEED = 0.4f
}

/**
 * Playback speed that shows a [sourceFps] video at [target] fps. Returns 1 when [target] is null
 * (option off), when the source frame rate is unknown or not positive, or when the source is at
 * most [CinemaFrameRate.TOLERANCE_FPS] above the target. Never returns less than [minSpeed].
 */
fun playbackSpeedFor(sourceFps: Float, target: Float?, minSpeed: Float = CinemaFrameRate.MIN_SPEED): Float {
    if (target == null || target <= 0f) return 1f
    if (sourceFps.isNaN() || sourceFps <= 0f) return 1f
    if (sourceFps <= target + CinemaFrameRate.TOLERANCE_FPS) return 1f
    return (target / sourceFps).coerceIn(minSpeed, 1f)
}
