package nl.giejay.mediaslider.model

/** WGS84 coordinates of where a photo or video was taken. */
data class GeoLocation(val latitude: Double, val longitude: Double)

/**
 * Supplies the [GeoLocation] of a [SliderItem], possibly after a lazy network lookup.
 * Returns null when the asset has no coordinates.
 */
fun interface GeoLocationProvider {
    suspend fun getLocation(): GeoLocation?
}
