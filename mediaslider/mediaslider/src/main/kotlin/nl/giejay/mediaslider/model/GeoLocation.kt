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

/**
 * How sure the location map is about a position. It decides how the map marks it: the more uncertain,
 * the softer the mark, so an estimate never looks like a measured GPS position.
 */
enum class LocationPrecision {
    /** Measured GPS position: solid white dot. */
    EXACT,

    /** Estimate close to a known position (a few minutes from a photo with GPS): ring instead of a dot. */
    NEAR,

    /** Estimate between a known position before and after: dashed line between them, ring on it. */
    CORRIDOR,

    /** Rough estimate (gap over night, known position on one side only): soft cloud without a hard mark. */
    ROUGH
}

/**
 * What the location map shows for one slide.
 *
 * @param radiusKm uncertainty of an estimate (about two standard deviations); 0 for [LocationPrecision.EXACT]
 * @param corridorStart known position before the photo, drawn for [LocationPrecision.CORRIDOR]
 * @param corridorEnd known position after the photo, drawn for [LocationPrecision.CORRIDOR]
 */
data class MapLocation(
    val position: GeoLocation,
    val precision: LocationPrecision = LocationPrecision.EXACT,
    val radiusKm: Double = 0.0,
    val corridorStart: GeoLocation? = null,
    val corridorEnd: GeoLocation? = null
)

/**
 * Supplies an estimated [MapLocation] for a [SliderItem] without GPS coordinates, possibly after network
 * lookups. Returns null when no estimate is possible.
 */
fun interface MapLocationProvider {
    suspend fun getMapLocation(): MapLocation?
}
