package nl.giejay.android.tv.immich.location

import nl.giejay.mediaslider.model.GeoLocation
import nl.giejay.mediaslider.model.LocationPrecision
import nl.giejay.mediaslider.model.MapLocation
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A photo or video with GPS coordinates that marks a known position in time.
 *
 * @param wallTimeMs local clock time of the capture, encoded as if it were UTC (Immich `localDateTime`).
 * Comparing clock times instead of instants matches what a camera without time zone records.
 */
data class LocationAnchor(
    val assetId: String,
    val ownerId: String?,
    val wallTimeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val city: String?,
    val country: String?
) {
    val location: GeoLocation get() = GeoLocation(latitude, longitude)
}

/** How a [LocationEstimate] is shown, from most to least certain. */
enum class EstimateTier { NEAR, CORRIDOR, ROUGH }

/**
 * Estimated capture position of a photo without GPS.
 *
 * @param radiusKm uncertainty, about two standard deviations; never below [LocationEstimator.MIN_RADIUS_KM]
 * @param before known position before the photo (null when there is none)
 * @param after known position after the photo (null when there is none)
 * @param nearest the known position closest in time, used for the place name
 */
data class LocationEstimate(
    val tier: EstimateTier,
    val latitude: Double,
    val longitude: Double,
    val radiusKm: Double,
    val before: LocationAnchor?,
    val after: LocationAnchor?,
    val nearest: LocationAnchor
) {
    fun toMapLocation(): MapLocation = MapLocation(
        position = GeoLocation(latitude, longitude),
        precision = when (tier) {
            EstimateTier.NEAR -> LocationPrecision.NEAR
            EstimateTier.CORRIDOR -> LocationPrecision.CORRIDOR
            EstimateTier.ROUGH -> LocationPrecision.ROUGH
        },
        radiusKm = radiusKm,
        corridorStart = before?.location?.takeIf { tier == EstimateTier.CORRIDOR },
        corridorEnd = after?.location?.takeIf { tier == EstimateTier.CORRIDOR }
    )

    /**
     * Place name for the metadata block, e.g. "ca. Innsbruck", "zwischen Garmisch-Partenkirchen und Innsbruck"
     * or "Raum Innsbruck". The three formats come from string resources. Falls back to the country when no
     * known position has a city, and returns null when neither is known.
     */
    fun placeLabel(near: (String) -> String, between: (String, String) -> String, area: (String) -> String): String? {
        if (tier == EstimateTier.CORRIDOR) {
            val from = before?.city?.takeIf { it.isNotBlank() }
            val to = after?.city?.takeIf { it.isNotBlank() }
            if (from != null && to != null && !from.equals(to, ignoreCase = true)) return between(from, to)
        }
        val place = listOfNotNull(nearest, before, after)
            .firstNotNullOfOrNull { it.city?.takeIf { city -> city.isNotBlank() } }
            ?: country
            ?: return null
        return if (tier == EstimateTier.ROUGH) area(place) else near(place)
    }

    /** Country when it is unambiguous: all known positions used for the estimate lie in the same country. */
    val country: String?
        get() {
            val countries = listOfNotNull(before, after).ifEmpty { listOf(nearest) }
                .map { it.country?.takeIf { c -> c.isNotBlank() } }
            val first = countries.firstOrNull() ?: return null
            return first.takeIf { countries.all { it == first } }
        }
}

/**
 * Estimates where a photo without GPS (e.g. from a system camera) was taken, from photos with GPS (e.g. from a
 * phone) taken before and after it. Pure function without Android or network, so the rules are unit tested.
 *
 * Model: between a known position before and after the photo, the position is interpolated by time on the
 * straight line. The uncertainty follows a Brownian bridge (zero at both known positions, largest in the
 * middle). Because camera clocks are often off by an hour (time zone or summer time not switched), the result
 * also covers the way travelled within ±[CLOCK_SLACK_HOURS] and is never more precise than [MIN_RADIUS_KM].
 */
object LocationEstimator {
    /** Never claim more precision than this, whatever the timing says. */
    const val MIN_RADIUS_KM = 10.0
    /** Camera clocks are often off by an hour; the estimate covers the way travelled in that time. */
    const val CLOCK_SLACK_HOURS = 1.0
    /** Photos with GPS further away in time than this are not used. */
    const val SEARCH_WINDOW_HOURS = 36.0
    /** Within this time of a photo with GPS the estimate counts as "near" (ring). */
    const val NEAR_HOURS = 20.0 / 60.0
    /** Diffusion of the Brownian bridge: how fast the uncertainty grows away from a known position. */
    const val DIFFUSION_KM2_PER_HOUR = 200.0
    /** Longer gaps between the known positions are shown as a rough cloud only. */
    const val MAX_CORRIDOR_GAP_HOURS = 12.0
    /** Larger uncertainty is shown as a rough cloud instead of a corridor. */
    const val MAX_CORRIDOR_RADIUS_KM = 60.0
    /** Larger uncertainty is not shown at all. */
    const val MAX_RADIUS_KM = 150.0
    /** A near estimate with a larger uncertainty falls back to the corridor or cloud. */
    const val MAX_NEAR_RADIUS_KM = 30.0
    /** Longer and faster than this between two known positions is a jump (flight, night train): no interpolation. */
    const val JUMP_MIN_KM = 300.0
    const val JUMP_MIN_KMH = 120.0
    /** Across a night, known positions further apart than this are not interpolated either. */
    const val OVERNIGHT_MAX_KM = 300.0
    /** Within this time of either end of a jump, the photo is placed at that end. */
    const val JUMP_SNAP_HOURS = 1.0
    /** Gaps that overlap the night (22:00 to 07:00) by at least this much count as "over night". */
    const val NIGHT_OVERLAP_HOURS = 3.0
    /** Without a known position on the other side, the estimate is shown at most this long after or before. */
    const val ONE_SIDED_MAX_HOURS = 12.0

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 24 * HOUR_MS
    private const val EARTH_RADIUS_KM = 6371.0

    /** Wall time window in which anchors are needed for [estimate] of a photo taken at [wallTimeMs]. */
    fun searchWindow(wallTimeMs: Long): LongRange {
        val window = (SEARCH_WINDOW_HOURS * HOUR_MS).toLong()
        return (wallTimeMs - window)..(wallTimeMs + window)
    }

    /**
     * @param wallTimeMs local clock time of the photo, encoded like [LocationAnchor.wallTimeMs]
     * @param anchors photos with GPS around that time, in any order
     * @return the estimate, or null when the known positions are too far away in time or space
     */
    fun estimate(wallTimeMs: Long, anchors: List<LocationAnchor>): LocationEstimate? {
        val window = searchWindow(wallTimeMs)
        val before = anchors.filter { it.wallTimeMs <= wallTimeMs && it.wallTimeMs in window }.maxByOrNull { it.wallTimeMs }
        val after = anchors.filter { it.wallTimeMs > wallTimeMs && it.wallTimeMs in window }.minByOrNull { it.wallTimeMs }
        return when {
            before != null && after != null -> twoSided(wallTimeMs, before, after)
            before != null -> oneSided(wallTimeMs, before)
            after != null -> oneSided(wallTimeMs, after)
            else -> null
        }
    }

    private fun twoSided(t: Long, before: LocationAnchor, after: LocationAnchor): LocationEstimate? {
        val distanceKm = distanceKm(before.location, after.location)
        val gapHours = (after.wallTimeMs - before.wallTimeMs).toDouble() / HOUR_MS
        val hoursBefore = (t - before.wallTimeMs).toDouble() / HOUR_MS
        val hoursAfter = (after.wallTimeMs - t).toDouble() / HOUR_MS
        val nearest = if (hoursBefore <= hoursAfter) before else after
        val overnight = nightOverlapHours(before.wallTimeMs, after.wallTimeMs) >= NIGHT_OVERLAP_HOURS
        val speedKmh = distanceKm / max(gapHours, 1.0 / 60.0)

        if (distanceKm > JUMP_MIN_KM && speedKmh > JUMP_MIN_KMH) {
            // Flight or night train: being "in between" makes no sense. Close to either end, stay there.
            val hoursToEnd = min(hoursBefore, hoursAfter)
            if (hoursToEnd <= JUMP_SNAP_HOURS) {
                return LocationEstimate(
                    EstimateTier.ROUGH, nearest.latitude, nearest.longitude,
                    oneSidedRadiusKm(hoursToEnd), before = nearest.takeIf { it === before }, after = nearest.takeIf { it === after }, nearest = nearest
                )
            }
            return sameDayFallback(t, before, after)
        }
        if (overnight && distanceKm > OVERNIGHT_MAX_KM) {
            // Long drive with a night in between: where the night was spent is unknown.
            return sameDayFallback(t, before, after)
        }

        val fraction = if (gapHours > 0) (hoursBefore / gapHours).coerceIn(0.0, 1.0) else 0.5
        val latitude = before.latitude + (after.latitude - before.latitude) * fraction
        val longitude = before.longitude + (after.longitude - before.longitude) * fraction
        val sigmaKm = sqrt(DIFFUSION_KM2_PER_HOUR * fraction * (1 - fraction) * gapHours)
        // Way travelled within the clock slack, along the line, in the direction with more room.
        val slack = if (gapHours > 0) CLOCK_SLACK_HOURS / gapHours else 1.0
        val clockSpreadKm = distanceKm * max(min(slack, 1 - fraction), min(slack, fraction))
        val radiusKm = maxOf(MIN_RADIUS_KM, 2 * sigmaKm, clockSpreadKm)
        if (radiusKm > MAX_RADIUS_KM) return null

        val tier = when {
            min(hoursBefore, hoursAfter) <= NEAR_HOURS && radiusKm <= MAX_NEAR_RADIUS_KM -> EstimateTier.NEAR
            overnight || gapHours > MAX_CORRIDOR_GAP_HOURS || radiusKm > MAX_CORRIDOR_RADIUS_KM -> EstimateTier.ROUGH
            else -> EstimateTier.CORRIDOR
        }
        return LocationEstimate(tier, latitude, longitude, radiusKm, before, after, nearest)
    }

    /** When before and after cannot be connected, keep the one on the photo's own day, if there is exactly one. */
    private fun sameDayFallback(t: Long, before: LocationAnchor, after: LocationAnchor): LocationEstimate? {
        val sameDay = listOf(before, after).filter { Math.floorDiv(it.wallTimeMs, DAY_MS) == Math.floorDiv(t, DAY_MS) }
        return sameDay.singleOrNull()?.let { oneSided(t, it) }
    }

    private fun oneSided(t: Long, anchor: LocationAnchor): LocationEstimate? {
        val hours = abs(t - anchor.wallTimeMs).toDouble() / HOUR_MS
        val overnight = nightOverlapHours(min(t, anchor.wallTimeMs), max(t, anchor.wallTimeMs)) >= NIGHT_OVERLAP_HOURS
        val tier = when {
            hours <= NEAR_HOURS -> EstimateTier.NEAR
            hours <= ONE_SIDED_MAX_HOURS && !overnight -> EstimateTier.ROUGH
            else -> return null
        }
        val radiusKm = oneSidedRadiusKm(hours)
        if (radiusKm > MAX_RADIUS_KM) return null
        val isBefore = anchor.wallTimeMs <= t
        return LocationEstimate(
            tier, anchor.latitude, anchor.longitude, radiusKm,
            before = anchor.takeIf { isBefore }, after = anchor.takeIf { !isBefore }, nearest = anchor
        )
    }

    /** Uncertainty with a known position on one side only: grows with the time since then, plus the clock slack. */
    private fun oneSidedRadiusKm(hours: Double): Double =
        max(MIN_RADIUS_KM, 2 * sqrt(DIFFUSION_KM2_PER_HOUR * (hours + CLOCK_SLACK_HOURS)))

    /** Hours between [fromMs] and [toMs] (wall times) that fall into a night from 22:00 to 07:00. */
    internal fun nightOverlapHours(fromMs: Long, toMs: Long): Double {
        if (toMs <= fromMs) return 0.0
        var total = 0L
        var dayStart = (Math.floorDiv(fromMs, DAY_MS) - 1) * DAY_MS
        while (dayStart < toMs) {
            val nightStart = dayStart + 22 * HOUR_MS
            val nightEnd = dayStart + 31 * HOUR_MS
            total += max(0L, min(nightEnd, toMs) - max(nightStart, fromMs))
            dayStart += DAY_MS
        }
        return total.toDouble() / HOUR_MS
    }

    /** Great-circle distance in kilometres. */
    fun distanceKm(a: GeoLocation, b: GeoLocation): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
