package nl.giejay.android.tv.immich.location

import arrow.core.Either
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.MAX_SEARCH_PAGE_SIZE
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.shared.util.AssetDetailCache
import nl.giejay.android.tv.immich.shared.util.AssetMetaDataMapping
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

internal const val HOUR_MS = 3_600_000L
internal const val DAY_MS = 24 * HOUR_MS

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

/** Anchors of one UTC day and when they were loaded from Immich. */
data class CachedAnchorDay(val fetchedAtMs: Long, val anchors: List<LocationAnchor>)

/** Keeps loaded days beyond the app process. */
interface AnchorDayStore {
    fun load(utcDay: Long): CachedAnchorDay?
    fun save(utcDay: Long, day: CachedAnchorDay)
}

/**
 * One small text file per UTC day in the app's cache directory (Android may clear it when space runs low).
 * A plain tab-separated format instead of JSON reflection, so release builds with R8 need no keep rules.
 */
class FileAnchorDayStore(private val directory: () -> File?) : AnchorDayStore {
    companion object {
        const val DIR_NAME = "location-anchors-v1"
        /** About 5 years of days with photos; each file is a few KB. */
        private const val MAX_FILES = 2000
    }

    private fun file(utcDay: Long): File? = directory()?.let { File(it, "$utcDay.tsv") }

    override fun load(utcDay: Long): CachedAnchorDay? {
        val file = file(utcDay)?.takeIf { it.isFile } ?: return null
        return try {
            val lines = file.readLines()
            val fetchedAt = lines.firstOrNull()?.toLongOrNull() ?: return null
            CachedAnchorDay(fetchedAt, lines.drop(1).filter { it.isNotEmpty() }.map { line ->
                val f = line.split('\t')
                LocationAnchor(f[0], f[1].ifEmpty { null }, f[2].toLong(), f[3].toDouble(), f[4].toDouble(),
                    f[5].ifEmpty { null }, f[6].ifEmpty { null })
            })
        } catch (e: Exception) {
            Timber.w(e, "Ignoring unreadable location cache file $file")
            null
        }
    }

    override fun save(utcDay: Long, day: CachedAnchorDay) {
        val dir = directory() ?: return
        try {
            dir.mkdirs()
            val clean = { value: String? -> value.orEmpty().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ') }
            val text = buildString {
                append(day.fetchedAtMs).append('\n')
                for (a in day.anchors) {
                    append(clean(a.assetId)).append('\t').append(clean(a.ownerId)).append('\t').append(a.wallTimeMs).append('\t')
                        .append(a.latitude).append('\t').append(a.longitude).append('\t')
                        .append(clean(a.city)).append('\t').append(clean(a.country)).append('\n')
                }
            }
            val tmp = File(dir, "$utcDay.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(File(dir, "$utcDay.tsv"))) tmp.delete()
            prune(dir)
        } catch (e: Exception) {
            Timber.w(e, "Could not write location cache for day $utcDay")
        }
    }

    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.name.endsWith(".tsv") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }
}

/**
 * Photos and videos with GPS per UTC day, loaded from Immich's metadata search. Immich filters the search on
 * the UTC capture time, so a day of local clock time spans up to three UTC days.
 *
 * Caching, so a day is asked for as rarely as possible:
 * - neighbouring missing days are loaded in one request and split by day afterwards
 * - loaded days stay in memory and in a file cache that survives app restarts ([AnchorDayStore])
 * - days that can still change (the last two days) are reloaded after [RECENT_MAX_AGE_MS], older days
 *   after [OLD_MAX_AGE_MS] (photos uploaded later, e.g. from a partner's phone)
 * - concurrent lookups of the same days share one request
 *
 * Nothing is written to Immich.
 */
object LocationAnchorRepository {
    private const val MAX_DAYS_IN_MEMORY = 120
    private const val MAX_PAGES_PER_DAY = 5
    /** Local clock time differs from UTC by at most 14 hours (UTC+14). */
    private const val MAX_ZONE_OFFSET_MS = 14 * HOUR_MS
    /** Days within this distance of now may still receive uploads. */
    private const val RECENT_DAYS_MS = 2 * DAY_MS
    private const val RECENT_MAX_AGE_MS = 30 * 60_000L
    private const val OLD_MAX_AGE_MS = 30 * DAY_MS

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val memory = object : LinkedHashMap<Long, CachedAnchorDay>(MAX_DAYS_IN_MEMORY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, CachedAnchorDay>?): Boolean =
            size > MAX_DAYS_IN_MEMORY
    }
    /** Running request per day; one request may cover several days. */
    private val inFlight = mutableMapOf<Long, Deferred<Map<Long, List<LocationAnchor>>?>>()

    private val defaultFetchPage: suspend (takenAfter: String, takenBefore: String, page: Int) -> Either<String, Pair<List<Asset>, Boolean>> =
        { after, before, page ->
            ApiClient.getClient(ApiClientConfig.fromPrefs())
                .searchTakenBetween(after, before, page, MAX_SEARCH_PAGE_SIZE)
                .map { it.assets.items to (it.assets.nextPage != null) }
        }
    private val defaultStore: AnchorDayStore =
        FileAnchorDayStore { ImmichApplication.appContext?.cacheDir?.let { File(it, FileAnchorDayStore.DIR_NAME) } }

    /** Test seam: one page of assets in a UTC range, and whether there are more pages. */
    @Volatile
    internal var fetchPage = defaultFetchPage

    /** Test seam: persistent day cache. */
    @Volatile
    internal var store: AnchorDayStore = defaultStore

    /** Test seam: current time. */
    @Volatile
    internal var now: () -> Long = { System.currentTimeMillis() }

    private fun isFresh(utcDay: Long, day: CachedAnchorDay): Boolean {
        val dayEnd = (utcDay + 1) * DAY_MS
        val maxAge = if (now() - dayEnd < RECENT_DAYS_MS) RECENT_MAX_AGE_MS else OLD_MAX_AGE_MS
        return now() - day.fetchedAtMs in 0..maxAge
    }

    /** Anchors captured on the UTC day [utcDay] (days since 1970-01-01), or null when Immich could not be asked. */
    internal suspend fun anchorsOfUtcDay(utcDay: Long): List<LocationAnchor>? = anchorsOfUtcDays(utcDay..utcDay)

    /**
     * Anchors of all UTC days in [days]. Cached days come from memory or the file cache; the missing ones are
     * loaded in as few requests as possible. Null when a request failed (failures are not cached).
     */
    suspend fun anchorsOfUtcDays(days: LongRange): List<LocationAnchor>? {
        if (days.isEmpty()) return emptyList()
        val result = mutableListOf<LocationAnchor>()
        val waits = mutableListOf<Pair<Long, Deferred<Map<Long, List<LocationAnchor>>?>>>()
        mutex.withLock {
            val missing = mutableListOf<Long>()
            for (day in days) {
                val cached = memory[day]?.takeIf { isFresh(day, it) }
                    ?: store.load(day)?.takeIf { isFresh(day, it) }?.also { memory[day] = it }
                when {
                    cached != null -> result += cached.anchors
                    inFlight[day] != null -> waits += day to inFlight.getValue(day)
                    else -> missing += day
                }
            }
            for (run in contiguousRuns(missing)) {
                // Own scope: a slide change cancels the caller, but the request still fills the cache.
                val deferred = scope.async {
                    val loaded = load(run)
                    mutex.withLock {
                        val fetchedAt = now()
                        loaded?.complete?.forEach { (day, anchors) ->
                            val entry = CachedAnchorDay(fetchedAt, anchors)
                            memory[day] = entry
                            store.save(day, entry)
                        }
                        run.forEach { inFlight.remove(it) }
                    }
                    loaded?.all
                }
                run.forEach { day ->
                    inFlight[day] = deferred
                    waits += day to deferred
                }
            }
        }
        for ((day, deferred) in waits) {
            val loaded = deferred.await() ?: return null
            result += loaded[day].orEmpty()
        }
        return result
    }

    private fun contiguousRuns(days: List<Long>): List<LongRange> {
        val runs = mutableListOf<LongRange>()
        var start: Long? = null
        var previous = 0L
        for (day in days.sorted()) {
            if (start != null && day == previous + 1) {
                previous = day
                continue
            }
            start?.let { runs += it..previous }
            start = day
            previous = day
        }
        start?.let { runs += it..previous }
        return runs
    }

    /** Anchors per day of one request; [complete] leaves out days cut off by the page limit. */
    private class Loaded(val all: Map<Long, List<LocationAnchor>>, val complete: Map<Long, List<LocationAnchor>>)

    private suspend fun load(days: LongRange): Loaded? {
        val after = Instant.ofEpochMilli(days.first * DAY_MS).toString()
        val before = Instant.ofEpochMilli((days.last + 1) * DAY_MS - 1000).toString()
        val byDay = days.associateWith { mutableListOf<LocationAnchor>() }
        val maxPages = MAX_PAGES_PER_DAY * (days.last - days.first + 1).toInt()
        var cutOffFrom: Long? = null
        try {
            for (page in 1..maxPages) {
                val (assets, more) = fetchPage(after, before, page).fold(
                    { error ->
                        Timber.w("Could not load photos with location for $after..$before: $error")
                        return null
                    },
                    { it }
                )
                for (asset in assets) {
                    val day = asset.fileCreatedAt?.time?.let { Math.floorDiv(it, DAY_MS) } ?: continue
                    val anchor = asset.toLocationAnchor() ?: continue
                    byDay[day]?.add(anchor)
                }
                if (!more) break
                if (page == maxPages) {
                    // Oldest first: everything from the day of the last asset on may be incomplete.
                    cutOffFrom = assets.lastOrNull()?.fileCreatedAt?.time?.let { Math.floorDiv(it, DAY_MS) } ?: days.first
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not load photos with location for $after..$before")
            return null
        }
        val complete = cutOffFrom?.let { cut -> byDay.filterKeys { it < cut } } ?: byDay
        return Loaded(byDay, complete)
    }

    /**
     * Anchors needed to estimate a photo taken at [wallTimeMs]: first the days around that time (one request),
     * then, only if one side has no anchor yet, the remaining days on that side (one request each).
     * Null when a lookup failed.
     */
    suspend fun anchorsAround(wallTimeMs: Long): List<LocationAnchor>? {
        val window = LocationEstimator.searchWindow(wallTimeMs)
        val firstDay = Math.floorDiv(window.first - MAX_ZONE_OFFSET_MS, DAY_MS)
        val lastDay = Math.floorDiv(window.last + MAX_ZONE_OFFSET_MS, DAY_MS)
        val low = Math.floorDiv(wallTimeMs - MAX_ZONE_OFFSET_MS, DAY_MS)
        val high = Math.floorDiv(wallTimeMs + MAX_ZONE_OFFSET_MS, DAY_MS)
        val anchors = anchorsOfUtcDays(low..high)?.toMutableList() ?: return null
        if (anchors.none { it.wallTimeMs <= wallTimeMs && it.wallTimeMs in window }) {
            anchors += anchorsOfUtcDays(firstDay until low) ?: return null
        }
        if (anchors.none { it.wallTimeMs > wallTimeMs && it.wallTimeMs in window }) {
            anchors += anchorsOfUtcDays((high + 1)..lastDay) ?: return null
        }
        return anchors
    }

    /** Test helper: forgets the memory cache only, like an app restart. */
    internal fun clearMemory() {
        memory.clear()
        inFlight.clear()
    }

    /** Test helper. */
    internal fun clear() {
        clearMemory()
        fetchPage = defaultFetchPage
        store = defaultStore
        now = { System.currentTimeMillis() }
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
    private val defaultSettings: () -> LocationEstimateSettings = { LocationEstimatePrefs.current() }

    /** Cached result; [estimate] is null when there is none. */
    private class Result(val estimate: LocationEstimate?)

    /** Test seam for the settings; production reads the preferences. */
    @Volatile
    internal var settings: () -> LocationEstimateSettings = defaultSettings

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
                    var result: Result? = null
                    try {
                        result = compute(asset, current.includePartners)
                        result
                    } finally {
                        // Also on an exception, so a failed lookup is tried again instead of failing forever.
                        withContext(NonCancellable) {
                            mutex.withLock {
                                result?.let { cache[key] = it }
                                inFlight.remove(key)
                            }
                        }
                    }
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
    internal fun clear() {
        cache.clear()
        inFlight.clear()
        settings = defaultSettings
    }
}
