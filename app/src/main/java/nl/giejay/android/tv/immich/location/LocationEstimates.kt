package nl.giejay.android.tv.immich.location

import arrow.core.Either
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.MAX_SEARCH_PAGE_SIZE
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.shared.util.AssetDetailCache
import nl.giejay.android.tv.immich.shared.util.AssetMetaDataMapping
import timber.log.Timber
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24 * HOUR_MS

/**
 * Local clock time of the capture, encoded as if it were UTC. Uses Immich's time-zone-free `localDateTime`
 * and falls back to the EXIF date (interpreted in the device time zone). Null when the asset has no date.
 */
internal fun Asset.wallTimeMs(): Long? {
    localDateTime?.let { parseWallTimeMs(it) }?.let { return it }
    val date = exifInfo?.dateTimeOriginal ?: fileCreatedAt ?: return null
    return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime().toEpochSecond(ZoneOffset.UTC) * 1000
}

/** Parses "2025-06-14T12:05:00.000Z" (or with an offset, or without zone) into the clock time, ignoring the zone. */
internal fun parseWallTimeMs(value: String): Long? {
    val local = try {
        OffsetDateTime.parse(value).toLocalDateTime()
    } catch (e: Exception) {
        try {
            LocalDateTime.parse(value)
        } catch (e: Exception) {
            return null
        }
    }
    return local.toEpochSecond(ZoneOffset.UTC) * 1000 + local.nano / 1_000_000
}

/** The asset as a known position, or null without valid GPS coordinates or capture time. */
internal fun Asset.toLocationAnchor(): LocationAnchor? {
    val location = AssetMetaDataMapping.geoLocationOf(this) ?: return null
    val wallTime = wallTimeMs() ?: return null
    return LocationAnchor(id, ownerId, wallTime, location.latitude, location.longitude, exifInfo?.city, exifInfo?.country)
}

/**
 * Photos and videos with GPS per UTC day, loaded from Immich's metadata search and kept in memory.
 * Immich filters the search on the UTC capture time, so a day of local clock time spans up to three UTC days.
 *
 * Nothing is written to Immich; the cache lives only as long as the app process.
 */
object LocationAnchorRepository {
    private const val MAX_CACHED_DAYS = 60
    private const val MAX_PAGES_PER_DAY = 5
    /** Local clock time differs from UTC by at most 14 hours (UTC+14). */
    internal const val MAX_ZONE_OFFSET_MS = 14 * HOUR_MS

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val days = object : LinkedHashMap<Long, List<LocationAnchor>>(MAX_CACHED_DAYS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<LocationAnchor>>?): Boolean =
            size > MAX_CACHED_DAYS
    }
    private val inFlight = mutableMapOf<Long, Deferred<List<LocationAnchor>?>>()

    private val defaultFetchPage: suspend (takenAfter: String, takenBefore: String, page: Int) -> Either<String, Pair<List<Asset>, Boolean>> =
        { after, before, page ->
            ApiClient.getClient(ApiClientConfig.fromPrefs())
                .searchTakenBetween(after, before, page, MAX_SEARCH_PAGE_SIZE)
                .map { it.assets.items to (it.assets.nextPage != null) }
        }

    /** Test seam: one page of assets in a UTC range, and whether there are more pages. */
    @Volatile
    var fetchPage = defaultFetchPage

    /**
     * Anchors captured on the UTC day [utcDay] (days since 1970-01-01), or null when Immich could not be asked.
     * Failures are not cached, so the next slide tries again.
     */
    suspend fun anchorsOfUtcDay(utcDay: Long): List<LocationAnchor>? {
        val deferred = mutex.withLock {
            days[utcDay]?.let { return it }
            inFlight.getOrPut(utcDay) {
                // Own scope: a slide change cancels the caller, but the lookup still fills the cache for the next one.
                scope.async {
                    val result = load(utcDay)
                    mutex.withLock {
                        if (result != null) days[utcDay] = result
                        inFlight.remove(utcDay)
                    }
                    result
                }
            }
        }
        return deferred.await()
    }

    private suspend fun load(utcDay: Long): List<LocationAnchor>? {
        val after = Instant.ofEpochMilli(utcDay * DAY_MS).toString()
        val before = Instant.ofEpochMilli((utcDay + 1) * DAY_MS - 1000).toString()
        val anchors = mutableListOf<LocationAnchor>()
        try {
            for (page in 1..MAX_PAGES_PER_DAY) {
                val (assets, more) = fetchPage(after, before, page).fold(
                    { error ->
                        Timber.w("Could not load photos with location for $after: $error")
                        return null
                    },
                    { it }
                )
                assets.mapNotNullTo(anchors) { it.toLocationAnchor() }
                if (!more) break
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not load photos with location for $after")
            return null
        }
        return anchors
    }

    /**
     * Anchors needed to estimate a photo taken at [wallTimeMs]: first the days around that time, then further
     * days back or forward only while no anchor was found on that side. Null when a lookup failed.
     */
    suspend fun anchorsAround(wallTimeMs: Long): List<LocationAnchor>? {
        val window = LocationEstimator.searchWindow(wallTimeMs)
        val firstDay = Math.floorDiv(window.first - MAX_ZONE_OFFSET_MS, DAY_MS)
        val lastDay = Math.floorDiv(window.last + MAX_ZONE_OFFSET_MS, DAY_MS)
        var low = Math.floorDiv(wallTimeMs - MAX_ZONE_OFFSET_MS, DAY_MS)
        var high = Math.floorDiv(wallTimeMs + MAX_ZONE_OFFSET_MS, DAY_MS)
        val anchors = mutableListOf<LocationAnchor>()
        for (day in low..high) anchors += anchorsOfUtcDay(day) ?: return null
        while (low > firstDay && anchors.none { it.wallTimeMs <= wallTimeMs && it.wallTimeMs in window }) {
            low--
            anchors += anchorsOfUtcDay(low) ?: return null
        }
        while (high < lastDay && anchors.none { it.wallTimeMs > wallTimeMs && it.wallTimeMs in window }) {
            high++
            anchors += anchorsOfUtcDay(high) ?: return null
        }
        return anchors
    }

    /** Test helper. */
    fun clear() {
        days.clear()
        inFlight.clear()
        fetchPage = defaultFetchPage
    }
}

/** User settings for estimating locations, read at lookup time. */
data class LocationEstimateSettings(val enabled: Boolean, val includePartners: Boolean)

/**
 * Estimated locations per asset, cached in memory. The map and the place name of the same slide share one
 * lookup. Results without an estimate are cached too, so a slide does not ask Immich again.
 */
object LocationEstimateService {
    private const val MAX_ENTRIES = 300

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<String, Result>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result>?): Boolean = size > MAX_ENTRIES
    }
    private val inFlight = mutableMapOf<String, Deferred<Result?>>()

    /** Cached result; [estimate] is null when there is none. */
    private class Result(val estimate: LocationEstimate?)

    /** Test seam for the settings; production reads the preferences. */
    @Volatile
    var settings: () -> LocationEstimateSettings = { LocationEstimatePrefs.current() }

    /**
     * Estimate for [asset] (possibly a partial timeline asset; details are loaded when time or owner are
     * missing). Null when estimating is switched off, the asset has GPS itself, or no estimate is possible.
     */
    suspend fun estimate(asset: Asset): LocationEstimate? {
        val current = settings()
        if (!current.enabled) return null
        val key = "${asset.id}|${current.includePartners}"
        val deferred = mutex.withLock {
            cache[key]?.let { return it.estimate }
            inFlight.getOrPut(key) {
                scope.async {
                    val result = compute(asset, current.includePartners)
                    mutex.withLock {
                        if (result != null) cache[key] = result
                        inFlight.remove(key)
                    }
                    result
                }
            }
        }
        return deferred.await()?.estimate
    }

    /** Null when a lookup failed (not cached, tried again next time). */
    private suspend fun compute(asset: Asset, includePartners: Boolean): Result? {
        val full = if (asset.localDateTime == null || asset.ownerId == null) {
            AssetDetailCache.get(asset.id).getOrNull() ?: asset
        } else {
            asset
        }
        if (AssetMetaDataMapping.geoLocationOf(full) != null) return Result(null)
        val wallTime = full.wallTimeMs() ?: return Result(null)
        val anchors = LocationAnchorRepository.anchorsAround(wallTime) ?: return null
        val usable = anchors.filter { anchor ->
            anchor.assetId != full.id &&
                (includePartners || full.ownerId == null || anchor.ownerId == null || anchor.ownerId == full.ownerId)
        }
        return Result(LocationEstimator.estimate(wallTime, usable))
    }

    /** Test helper. */
    fun clear() {
        cache.clear()
        inFlight.clear()
        settings = { LocationEstimatePrefs.current() }
    }
}
