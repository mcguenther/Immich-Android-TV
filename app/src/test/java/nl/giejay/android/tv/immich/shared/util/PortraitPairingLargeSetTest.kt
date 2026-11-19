package nl.giejay.android.tv.immich.shared.util

import com.google.common.truth.Truth.assertThat
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.AssetExifInfo
import nl.giejay.android.tv.immich.shared.prefs.PortraitPairing
import org.junit.Test
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

/**
 * Checks the bucketed portrait pairing in [groupForSlider] against a brute-force copy of the
 * original rule on random sets, and that a large set is paired correctly and quickly.
 */
class PortraitPairingLargeSetTest {

    private val cities = listOf("Amsterdam", " amsterdam ", "Utrecht", "Paris", "", null, "Rome")
    private val countries = listOf("Netherlands", "NL", null, "France", "Italy", " ")

    private fun portrait(id: String, taken: Date?, city: String?, country: String?) = Asset(
        id = id,
        type = "IMAGE",
        deviceAssetId = null,
        exifInfo = AssetExifInfo(
            description = null,
            orientation = 1,
            exifImageWidth = 3000,
            exifImageHeight = 4000,
            city = city,
            country = country,
            dateTimeOriginal = taken,
            make = null,
            model = null
        ),
        fileModifiedAt = null,
        albumName = null,
        people = null,
        tags = null,
        originalPath = null,
        originalFileName = null,
        fileCreatedAt = null
    )

    private fun randomDate(random: Random, days: Int): Date? {
        if (random.nextInt(10) == 0) return null
        val day = random.nextInt(days)
        return Calendar.getInstance().apply {
            clear()
            set(2024, Calendar.JANUARY, 1 + day, random.nextInt(24), random.nextInt(60), random.nextInt(60))
        }.time
    }

    private fun randomPortraits(random: Random, count: Int, days: Int): List<Asset> = (0 until count).map { index ->
        portrait(
            id = "p$index",
            taken = randomDate(random, days),
            city = cities[random.nextInt(cities.size)],
            country = countries[random.nextInt(countries.size)]
        )
    }

    private fun pairingOf(assets: List<Asset>, pairing: PortraitPairing): List<List<String>> =
        groupForSlider(assets, false, SliderItemOptions(pairing, true), null).map { page -> page.map { it.id } }

    /** The original rule, quadratic and with a Calendar per comparison: the reference for the bucketed version. */
    private fun referenceGroups(assets: List<Asset>, pairing: PortraitPairing): List<List<String>> {
        val queue = assets.toMutableList()
        val groups = mutableListOf<List<String>>()
        while (queue.isNotEmpty()) {
            val first = queue.removeAt(0)
            val partnerIndex = queue.indices
                .filter { referenceCanPair(first, queue[it], pairing) }
                .minByOrNull { referenceDistance(first, queue[it]) }
            if (partnerIndex != null) {
                groups.add(listOf(first.id, queue.removeAt(partnerIndex).id))
            } else {
                groups.add(listOf(first.id))
            }
        }
        return groups
    }

    private fun referenceCanPair(a: Asset, b: Asset, pairing: PortraitPairing): Boolean = when (pairing) {
        PortraitPairing.OFF -> false
        PortraitPairing.SAME_DAY -> referenceSameDay(a, b)
        PortraitPairing.SAME_DAY_OR_PLACE -> referenceSameDay(a, b) || referenceSamePlace(a, b)
    }

    private fun referenceSameDay(a: Asset, b: Asset): Boolean {
        val timeA = a.exifInfo?.dateTimeOriginal ?: a.fileCreatedAt ?: return false
        val timeB = b.exifInfo?.dateTimeOriginal ?: b.fileCreatedAt ?: return false
        val calendarA = Calendar.getInstance().apply { time = timeA }
        val calendarB = Calendar.getInstance().apply { time = timeB }
        return calendarA.get(Calendar.YEAR) == calendarB.get(Calendar.YEAR) &&
                calendarA.get(Calendar.DAY_OF_YEAR) == calendarB.get(Calendar.DAY_OF_YEAR)
    }

    private fun referenceSamePlace(a: Asset, b: Asset): Boolean {
        val cityA = a.exifInfo?.city?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return false
        val cityB = b.exifInfo?.city?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return false
        if (cityA != cityB) return false
        val countryA = a.exifInfo?.country?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val countryB = b.exifInfo?.country?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        return countryA == null || countryB == null || countryA == countryB
    }

    private fun referenceDistance(a: Asset, b: Asset): Long {
        val timeA = (a.exifInfo?.dateTimeOriginal ?: a.fileCreatedAt)?.time ?: return Long.MAX_VALUE
        val timeB = (b.exifInfo?.dateTimeOriginal ?: b.fileCreatedAt)?.time ?: return Long.MAX_VALUE
        return abs(timeA - timeB)
    }

    @Test
    fun `bucketed pairing matches the original rule on random sets`() {
        val random = Random(42)
        repeat(5) { round ->
            val assets = randomPortraits(random, count = 300, days = 20 + round * 10)
            for (pairing in listOf(PortraitPairing.SAME_DAY, PortraitPairing.SAME_DAY_OR_PLACE)) {
                assertThat(pairingOf(assets, pairing)).isEqualTo(referenceGroups(assets, pairing))
            }
        }
    }

    @Test
    fun `landscape photos and videos stay singles and are not part of the pairing`() {
        val random = Random(7)
        val portraits = randomPortraits(random, count = 200, days = 15)
        val landscape = portraits.take(50).map { it.copy(id = "l" + it.id, exifInfo = it.exifInfo?.copy(exifImageWidth = 4000, exifImageHeight = 3000)) }
        val assets = portraits + landscape

        val groups = pairingOf(assets, PortraitPairing.SAME_DAY_OR_PLACE)

        val landscapeIds = landscape.map { it.id }.toSet()
        groups.filter { page -> page.any { it in landscapeIds } }.forEach { assertThat(it).hasSize(1) }
        assertThat(groups.flatten().toSet()).containsExactlyElementsIn(assets.map { it.id }.toSet())
    }

    @Test
    fun `5000 portraits are paired correctly and quickly`() {
        val random = Random(2024)
        val assets = randomPortraits(random, count = 5000, days = 400)

        val started = System.nanoTime()
        val groups = pairingOf(assets, PortraitPairing.SAME_DAY_OR_PLACE)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        // every photo exactly once, pages of one or two
        assertThat(groups.flatten().sorted()).isEqualTo(assets.map { it.id }.sorted())
        assertThat(groups.all { it.size in 1..2 }).isTrue()
        // the set is paired, not left as singles
        assertThat(groups.count { it.size == 2 }).isGreaterThan(1000)
        // generous bound; the old quadratic version with Calendars took far longer
        assertThat(elapsedMs).isLessThan(2_000L)
    }

    @Test
    fun `5000 portraits on one day are paired without a quadratic blow-up`() {
        val random = Random(99)
        val assets = randomPortraits(random, count = 5000, days = 1).map { it.copy(exifInfo = it.exifInfo?.copy(city = null, country = null)) }

        val started = System.nanoTime()
        val groups = pairingOf(assets, PortraitPairing.SAME_DAY)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertThat(groups.flatten().sorted()).isEqualTo(assets.map { it.id }.sorted())
        assertThat(groups.count { it.size == 2 }).isGreaterThan(2000)
        assertThat(elapsedMs).isLessThan(2_000L)
    }
}
