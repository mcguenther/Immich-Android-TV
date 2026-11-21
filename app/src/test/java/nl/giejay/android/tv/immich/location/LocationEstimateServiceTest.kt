package nl.giejay.android.tv.immich.location

import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.AssetExifInfo
import nl.giejay.android.tv.immich.shared.util.AssetDetailCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant

class LocationEstimateServiceTest {

    private val requests = mutableListOf<String>()
    private var library: List<Asset> = emptyList()
    private var failing = false

    private fun asset(id: String, owner: String, local: String, utc: String, lat: Double?, lon: Double?, city: String? = null) = Asset(
        id = id,
        type = "IMAGE",
        deviceAssetId = null,
        exifInfo = AssetExifInfo(null, 1, null, null, city, null, null, null, null, lat, lon),
        fileCreatedAt = java.util.Date.from(Instant.parse(utc)),
        fileModifiedAt = null,
        albumName = null,
        people = null,
        tags = null,
        originalPath = null,
        originalFileName = null,
        ownerId = owner,
        localDateTime = local
    )

    // Phones record UTC; local time is two hours ahead (summer time in Germany).
    private val myPhoneBefore = asset("p1", "me", "2025-06-14T10:48:00.000Z", "2025-06-14T08:48:00Z", 47.4917, 11.0955, "Garmisch-Partenkirchen")
    private val myPhoneAfter = asset("p2", "me", "2025-06-14T16:55:00.000Z", "2025-06-14T14:55:00Z", 47.2692, 11.4041, "Innsbruck")
    private val partnerPhone = asset("q1", "partner", "2025-06-14T12:00:00.000Z", "2025-06-14T10:00:00Z", 47.42, 10.98, "Grainau")
    // Camera without time zone: Immich stores the clock time as if it were UTC.
    private val camera = asset("c1", "me", "2025-06-14T12:05:00.000Z", "2025-06-14T12:05:00Z", null, null)
    private val camera2 = asset("c2", "me", "2025-06-14T13:30:00.000Z", "2025-06-14T13:30:00Z", null, null)

    /** Persistent day cache that survives [LocationAnchorRepository.clearMemory], like files on disk. */
    private val disk = object : AnchorDayStore {
        val days = mutableMapOf<Long, CachedAnchorDay>()
        override fun load(utcDay: Long) = days[utcDay]
        override fun save(utcDay: Long, day: CachedAnchorDay) { days[utcDay] = day }
    }
    private var clock = Instant.parse("2026-11-21T12:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        LocationAnchorRepository.store = disk
        LocationAnchorRepository.now = { clock }
        library = listOf(myPhoneBefore, partnerPhone, myPhoneAfter, camera, camera2)
        LocationEstimateService.settings = { LocationEstimateSettings(enabled = true, includePartners = true) }
        LocationAnchorRepository.fetchPage = { after, before, page ->
            requests += after
            if (failing) {
                "offline".left()
            } else {
                val from = Instant.parse(after)
                val to = Instant.parse(before)
                library.filter { !it.fileCreatedAt!!.toInstant().isBefore(from) && !it.fileCreatedAt.toInstant().isAfter(to) }
                    .let { (if (page == 1) it else emptyList()) to false }.right()
            }
        }
        AssetDetailCache.fetchAsset = { id -> library.first { it.id == id }.right() }
    }

    @After
    fun tearDown() {
        LocationEstimateService.clear()
        LocationAnchorRepository.clear()
        AssetDetailCache.clear()
    }

    @Test
    fun `camera photo between phone photos gets a corridor estimate`() = runBlocking {
        LocationEstimateService.settings = { LocationEstimateSettings(enabled = true, includePartners = false) }
        val e = LocationEstimateService.estimate(camera)!!
        assertEquals(EstimateTier.CORRIDOR, e.tier)
        assertEquals("p1", e.before!!.assetId)
        assertEquals("p2", e.after!!.assetId)
    }

    @Test
    fun `partner photos count unless switched off`() = runBlocking {
        val withPartner = LocationEstimateService.estimate(camera)!!
        assertEquals("q1", withPartner.nearest.assetId)
        LocationEstimateService.settings = { LocationEstimateSettings(enabled = true, includePartners = false) }
        val withoutPartner = LocationEstimateService.estimate(camera)!!
        assertTrue(withoutPartner.before!!.assetId != "q1" && withoutPartner.after!!.assetId != "q1")
    }

    @Test
    fun `switched off gives no estimate and no requests`() = runBlocking {
        LocationEstimateService.settings = { LocationEstimateSettings(enabled = false, includePartners = true) }
        assertNull(LocationEstimateService.estimate(camera))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `days are loaded once and shared between photos`() = runBlocking {
        assertNotNull(LocationEstimateService.estimate(camera))
        val afterFirst = requests.size
        assertTrue(afterFirst > 0)
        assertNotNull(LocationEstimateService.estimate(camera2))
        assertEquals(afterFirst, requests.size)
    }

    @Test
    fun `failed lookups are not cached`() = runBlocking {
        failing = true
        assertNull(LocationEstimateService.estimate(camera))
        failing = false
        assertNotNull(LocationEstimateService.estimate(camera))
    }

    @Test
    fun `assets with their own GPS are not estimated`() = runBlocking {
        assertNull(LocationEstimateService.estimate(myPhoneBefore))
    }

    @Test
    fun `local date time is read as clock time, ignoring the zone`() {
        assertEquals(parseWallTimeMs("2025-06-14T12:05:00"), parseWallTimeMs("2025-06-14T12:05:00.000Z"))
        assertEquals(parseWallTimeMs("2025-06-14T12:05:00"), parseWallTimeMs("2025-06-14T12:05:00+02:00"))
        assertNull(parseWallTimeMs("not a date"))
    }

    @Test
    fun `the days around a photo are loaded in one request`() = runBlocking {
        assertNotNull(LocationEstimateService.estimate(camera))
        assertEquals(1, requests.size)
    }

    @Test
    fun `loaded days survive an app restart`() = runBlocking {
        assertNotNull(LocationEstimateService.estimate(camera))
        val before = requests.size
        LocationAnchorRepository.clearMemory()
        LocationEstimateService.clear()
        LocationEstimateService.settings = { LocationEstimateSettings(enabled = true, includePartners = true) }
        assertNotNull(LocationEstimateService.estimate(camera))
        assertEquals(before, requests.size)
    }

    @Test
    fun `recent days are reloaded after half an hour, old days are kept`() = runBlocking {
        val day = Math.floorDiv(Instant.parse("2025-06-14T00:00:00Z").toEpochMilli(), 86_400_000L)
        LocationAnchorRepository.anchorsOfUtcDay(day)
        clock += 2 * 3_600_000L
        LocationAnchorRepository.anchorsOfUtcDay(day)
        assertEquals("old day still cached", 1, requests.size)

        val today = Math.floorDiv(clock, 86_400_000L)
        LocationAnchorRepository.anchorsOfUtcDay(today)
        clock += 10 * 60_000L
        LocationAnchorRepository.anchorsOfUtcDay(today)
        assertEquals("today cached for a few minutes", 2, requests.size)
        clock += 31 * 60_000L
        LocationAnchorRepository.anchorsOfUtcDay(today)
        assertEquals("today reloaded", 3, requests.size)
    }

    @Test
    fun `photos in the last second of a UTC day are found`() = runBlocking {
        val late = asset("l1", "me", "2025-06-15T01:59:59.500Z", "2025-06-14T23:59:59.500Z", 47.0, 11.0)
        library = listOf(late)
        val day = Math.floorDiv(Instant.parse("2025-06-14T00:00:00Z").toEpochMilli(), 86_400_000L)
        assertEquals(listOf("l1"), LocationAnchorRepository.anchorsOfUtcDay(day)!!.map { it.assetId })
    }

    @Test
    fun `a request that fails with an error is not left in flight`() = runBlocking {
        // An Error (e.g. out of memory while parsing a page) is not caught by the request itself.
        var calls = 0
        LocationAnchorRepository.fetchPage = { _, _, _ ->
            calls++
            if (calls == 1) throw AssertionError("boom")
            (listOf(myPhoneBefore) to false).right()
        }
        val day = Math.floorDiv(Instant.parse("2025-06-14T00:00:00Z").toEpochMilli(), 86_400_000L)
        try {
            LocationAnchorRepository.anchorsOfUtcDay(day)
            fail("the first request should fail")
        } catch (e: AssertionError) {
            assertEquals("boom", e.message)
        }
        // Without the cleanup the day would stay in flight and every later lookup would get the old failure.
        assertEquals(listOf("p1"), LocationAnchorRepository.anchorsOfUtcDay(day)!!.map { it.assetId })
        assertEquals(2, calls)
    }

    @Test
    fun `a cancelled lookup leaves the request running and the day is cached`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        LocationAnchorRepository.fetchPage = { after, _, _ ->
            requests += after
            started.complete(Unit)
            gate.await()
            (listOf(myPhoneBefore) to false).right()
        }
        val day = Math.floorDiv(Instant.parse("2025-06-14T00:00:00Z").toEpochMilli(), 86_400_000L)
        val caller = launch(Dispatchers.IO) { LocationAnchorRepository.anchorsOfUtcDay(day) }
        started.await()
        caller.cancelAndJoin()
        gate.complete(Unit)
        // The request was not cancelled with its caller: the next lookup shares or reuses it.
        assertEquals(listOf("p1"), LocationAnchorRepository.anchorsOfUtcDay(day)!!.map { it.assetId })
        assertEquals(1, requests.size)
    }

    @Test
    fun `days cut off by the page limit are not cached`() = runBlocking {
        // Every page claims there is more: the request stops at the page limit.
        LocationAnchorRepository.fetchPage = { after, _, _ ->
            requests += after
            (listOf(myPhoneBefore) to true).right()
        }
        val day = Math.floorDiv(Instant.parse("2025-06-14T00:00:00Z").toEpochMilli(), 86_400_000L)
        LocationAnchorRepository.anchorsOfUtcDay(day)
        val first = requests.size
        LocationAnchorRepository.anchorsOfUtcDay(day)
        assertTrue(requests.size > first)
        assertTrue(disk.days.isEmpty())
    }
}
