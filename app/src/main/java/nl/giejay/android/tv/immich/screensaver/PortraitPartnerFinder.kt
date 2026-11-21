package nl.giejay.android.tv.immich.screensaver

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.shared.prefs.PortraitPairing
import nl.giejay.android.tv.immich.shared.util.SliderItemOptions
import nl.giejay.android.tv.immich.shared.util.Utils.pmap
import nl.giejay.android.tv.immich.shared.util.captureTime
import nl.giejay.android.tv.immich.shared.util.findClosestPartner
import nl.giejay.android.tv.immich.shared.util.groupForSlider
import nl.giejay.android.tv.immich.shared.util.isPortraitImage
import timber.log.Timber
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random

/** Finds photos on the server that may share a page with one portrait photo. */
fun interface PartnerCandidateSource {
    /**
     * Candidates for [portrait]. They may include unsuitable assets (landscape, other day, the
     * portrait itself); [PortraitPartnerFinder] checks every candidate. Failures return an empty list.
     */
    suspend fun candidatesFor(portrait: Asset, pairing: PortraitPairing): List<Asset>
}

private const val PARALLEL_LOOKUPS = 4
private const val MAX_LOOKUPS_PER_BATCH = 40
private const val RECENT_CAPACITY = 2_000

/**
 * Builds the screensaver's slider pages from one loaded batch of assets.
 *
 * Portrait photos are first paired within the batch by [groupForSlider]. Each portrait photo still
 * alone then gets a partner from [source]: the matching photo closest in capture time, so the pairing
 * no longer depends on the batch holding two photos of the same day. A partner is never an asset of
 * this batch, one recently shown, or one [isAllowed] rejects (excluded albums and tags).
 *
 * Batches larger than [lookupBatchLimit] get no lookups: such a batch is a whole album or person
 * already, so it holds the partners a lookup could find, and lookups would only delay the start.
 */
internal class PortraitPartnerFinder(
    private val source: PartnerCandidateSource,
    private val isAllowed: (Asset) -> Boolean = { true },
    private val lookupBatchLimit: Int = 200,
    private val random: Random = Random.Default
) {
    private val mutex = Mutex()

    /** Ids handed to the slider lately, oldest first; a partner is never taken from these. */
    private val recentIds = LinkedHashSet<String>()

    /** Pages for [assets]: one asset alone or two portrait photos side by side, shuffled unless pairing is off. */
    suspend fun group(assets: List<Asset>, options: SliderItemOptions): List<List<Asset>> = mutex.withLock {
        val groups = groupForSlider(assets, keepOrder = false, options, keepAssetId = null).toMutableList()
        if (options.pairing == PortraitPairing.OFF) {
            remember(groups)
            return@withLock groups
        }
        if (assets.size <= lookupBatchLimit) {
            addPartners(groups, options.pairing)
        }
        remember(groups)
        groups.shuffled(random)
    }

    private suspend fun addPartners(groups: MutableList<List<Asset>>, pairing: PortraitPairing) {
        val lonely = groups.indices
            .filter { groups[it].size == 1 && groups[it][0].isPortraitImage() }
            .take(MAX_LOOKUPS_PER_BATCH)
        if (lonely.isEmpty()) return

        val started = System.nanoTime()
        val semaphore = Semaphore(PARALLEL_LOOKUPS)
        val candidates = coroutineScope {
            lonely.map { index ->
                async { semaphore.withPermit { lookup(groups[index][0], pairing) } }
            }.awaitAll()
        }

        val taken = HashSet<String>(recentIds)
        groups.forEach { group -> group.forEach { taken.add(it.id) } }
        var found = 0
        lonely.forEachIndexed { position, index ->
            val portrait = groups[index][0]
            val partner = findClosestPartner(portrait, candidates[position], pairing) {
                it.id !in taken && isAllowed(it)
            } ?: return@forEachIndexed
            taken.add(partner.id)
            groups[index] = listOf(portrait, partner)
            found++
        }
        Timber.d("Portrait partners: found %d of %d in %d ms", found, lonely.size, (System.nanoTime() - started) / 1_000_000)
    }

    private suspend fun lookup(portrait: Asset, pairing: PortraitPairing): List<Asset> = try {
        source.candidatesFor(portrait, pairing)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Portrait partner lookup failed for %s", portrait.id)
        emptyList()
    }

    private fun remember(groups: List<List<Asset>>) {
        groups.forEach { group -> group.forEach { recentIds.remove(it.id); recentIds.add(it.id) } }
        val iterator = recentIds.iterator()
        while (recentIds.size > RECENT_CAPACITY && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }
}

/**
 * Asks Immich for partners of a portrait photo: the photos taken just before and just after it on the
 * same local day and, with [PortraitPairing.SAME_DAY_OR_PLACE], random photos from the same city.
 * With [albumIds] or [personIds] each album or person is searched on its own, so partners come from
 * the same selection the screensaver shows.
 */
internal class ServerPartnerCandidateSource(
    private val apiClient: ApiClient,
    private val albumIds: List<String> = emptyList(),
    private val personIds: List<UUID> = emptyList(),
    private val pageSize: Int = 20,
    private val zone: ZoneId = ZoneId.systemDefault()
) : PartnerCandidateSource {

    override suspend fun candidatesFor(portrait: Asset, pairing: PortraitPairing): List<Asset> {
        val takenAt = portrait.captureTime() ?: return emptyList()
        val time = LocalDateTime.ofInstant(takenAt.toInstant(), zone)
        val dayStart = time.toLocalDate().atStartOfDay()
        val dayEnd = dayStart.plusDays(1)
        val city = portrait.exifInfo?.city?.trim()?.takeIf { it.isNotEmpty() && pairing == PortraitPairing.SAME_DAY_OR_PLACE }

        val queries = scopes().flatMap { (albums, people) ->
            val sameDay: List<suspend () -> List<Asset>> = listOf(
                { search(fromDate = dayStart, endDate = time, order = "desc", albums = albums, people = people) },
                { search(fromDate = time, endDate = dayEnd, order = "asc", albums = albums, people = people) }
            )
            val samePlace: List<suspend () -> List<Asset>> =
                if (city == null) emptyList()
                else listOf({ search(random = true, city = city, albums = albums, people = people) })
            sameDay + samePlace
        }
        return queries.pmap { it() }.flatten()
    }

    private suspend fun search(fromDate: LocalDateTime? = null,
                               endDate: LocalDateTime? = null,
                               order: String = "desc",
                               random: Boolean = false,
                               city: String? = null,
                               albums: List<String>,
                               people: List<UUID>): List<Asset> =
        apiClient.searchPhotos(pageSize, fromDate, endDate, order, random, city, albums, people)
            .getOrElseLogged("portrait partner", emptyList())

    private fun scopes(): List<Pair<List<String>, List<UUID>>> = when {
        albumIds.isNotEmpty() -> albumIds.map { listOf(it) to emptyList() }
        personIds.isNotEmpty() -> personIds.map { emptyList<String>() to listOf(it) }
        else -> listOf(emptyList<String>() to emptyList())
    }
}
