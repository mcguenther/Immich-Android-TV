package nl.giejay.android.tv.immich.screensaver

import arrow.core.Either
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.AssetExifInfo
import nl.giejay.android.tv.immich.shared.prefs.PortraitPairing
import nl.giejay.android.tv.immich.shared.util.SliderItemOptions
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.UUID
import kotlin.random.Random

/** Covers the screensaver's portrait partner lookup: [PortraitPartnerFinder] and [ServerPartnerCandidateSource]. */
class PortraitPartnerFinderTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Date =
        Calendar.getInstance().apply { clear(); set(year, month - 1, day, hour, minute, 0) }.time

    private fun photo(id: String, taken: Date?, portrait: Boolean = true, city: String? = null, country: String? = null) = Asset(
        id = id,
        type = "IMAGE",
        deviceAssetId = null,
        exifInfo = AssetExifInfo(
            description = null,
            orientation = 1,
            exifImageWidth = if (portrait) 3000 else 4000,
            exifImageHeight = if (portrait) 4000 else 3000,
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
        originalFileName = null
    )

    /** Source answering every lookup from a fixed library and recording which portraits were looked up. */
    private class FakeSource(private val library: List<Asset>) : PartnerCandidateSource {
        val lookedUp = mutableListOf<String>()
        override suspend fun candidatesFor(portrait: Asset, pairing: PortraitPairing): List<Asset> {
            synchronized(lookedUp) { lookedUp.add(portrait.id) }
            return library
        }
    }

    private fun finder(source: PartnerCandidateSource, isAllowed: (Asset) -> Boolean = { true }, lookupBatchLimit: Int = 200) =
        PortraitPartnerFinder(source, isAllowed, lookupBatchLimit = lookupBatchLimit, random = Random(1))

    private suspend fun PortraitPartnerFinder.pages(assets: List<Asset>, pairing: PortraitPairing = PortraitPairing.SAME_DAY) =
        group(assets, SliderItemOptions(pairing)).map { page -> page.map { it.id } }.toSet()

    @Test
    fun `lonely portrait gets the closest matching photo from the server`() = runTest {
        val portrait = photo("p", at(2024, 5, 1, 12))
        val source = FakeSource(listOf(
            photo("far", at(2024, 5, 1, 20)),
            photo("near", at(2024, 5, 1, 12, 5)),
            photo("landscape", at(2024, 5, 1, 12, 1), portrait = false),
            photo("otherDay", at(2024, 5, 2, 12))
        ))

        assertThat(finder(source).pages(listOf(portrait))).containsExactly(listOf("p", "near"))
    }

    @Test
    fun `portraits paired within the batch need no lookup`() = runTest {
        val a = photo("a", at(2024, 5, 1, 9))
        val b = photo("b", at(2024, 5, 1, 10))
        val landscape = photo("l", at(2024, 5, 1), portrait = false)
        val source = FakeSource(listOf(photo("x", at(2024, 5, 1, 9, 1))))

        assertThat(finder(source).pages(listOf(a, landscape, b))).containsExactly(listOf("a", "b"), listOf("l"))
        assertThat(source.lookedUp).isEmpty()
    }

    @Test
    fun `partner is never an asset of the batch or one shown before`() = runTest {
        val first = photo("first", at(2024, 5, 1, 12))
        val alsoInBatch = photo("paired1", at(2024, 6, 1, 8))
        val alsoInBatch2 = photo("paired2", at(2024, 6, 1, 9))
        // "paired1" is closest to "first" in time on another day: not pairable; "near" is the valid one
        val source = FakeSource(listOf(alsoInBatch, photo("near", at(2024, 5, 1, 12, 30)), photo("later", at(2024, 5, 1, 18))))
        val finder = finder(source)

        assertThat(finder.pages(listOf(first, alsoInBatch, alsoInBatch2)))
            .containsExactly(listOf("first", "near"), listOf("paired1", "paired2"))

        // "near" was shown already, so the next batch's portrait of that day gets "later"
        val second = photo("second", at(2024, 5, 1, 12, 31))
        assertThat(finder.pages(listOf(second))).containsExactly(listOf("second", "later"))
    }

    @Test
    fun `a candidate matching two lonely portraits is given to only one of them`() = runTest {
        // a and b do not match each other (different countries), but both match the country-less candidate
        val a = photo("a", at(2024, 5, 1), city = "Utrecht", country = "Netherlands")
        val b = photo("b", at(2023, 5, 1), city = "Utrecht", country = "Belgium")
        val source = FakeSource(listOf(photo("shared", at(2022, 1, 1), city = "Utrecht", country = null)))

        val pages = finder(source).pages(listOf(a, b), PortraitPairing.SAME_DAY_OR_PLACE)

        assertThat(pages).containsExactly(listOf("a", "shared"), listOf("b"))
        assertThat(source.lookedUp).containsExactly("a", "b")
    }

    @Test
    fun `excluded candidates are skipped`() = runTest {
        val portrait = photo("p", at(2024, 5, 1, 12))
        val source = FakeSource(listOf(photo("excluded", at(2024, 5, 1, 12, 1)), photo("ok", at(2024, 5, 1, 15))))

        assertThat(finder(source, isAllowed = { it.id != "excluded" }).pages(listOf(portrait)))
            .containsExactly(listOf("p", "ok"))
    }

    @Test
    fun `portrait stays alone when the lookup fails or finds nothing`() = runTest {
        val portrait = photo("p", at(2024, 5, 1, 12))
        val failing = PartnerCandidateSource { _, _ -> throw IllegalStateException("offline") }

        assertThat(finder(failing).pages(listOf(portrait))).containsExactly(listOf("p"))
        assertThat(finder(FakeSource(emptyList())).pages(listOf(portrait))).containsExactly(listOf("p"))
    }

    @Test
    fun `pairing OFF never looks up and keeps the order`() = runTest {
        val source = FakeSource(listOf(photo("x", at(2024, 5, 1, 12, 1))))
        val assets = listOf(photo("c", at(2024, 5, 1)), photo("a", at(2024, 6, 1)), photo("b", at(2024, 7, 1)))

        val pages = finder(source).group(assets, SliderItemOptions(PortraitPairing.OFF)).map { page -> page.map { it.id } }

        assertThat(pages).containsExactly(listOf("c"), listOf("a"), listOf("b")).inOrder()
        assertThat(source.lookedUp).isEmpty()
    }

    @Test
    fun `batches above the limit are only paired within the batch`() = runTest {
        val source = FakeSource(listOf(photo("x", at(2024, 5, 1, 12, 1))))
        val assets = listOf(photo("a", at(2024, 5, 1)), photo("b", at(2024, 6, 1)), photo("c", at(2024, 7, 1)))

        assertThat(finder(source, lookupBatchLimit = 2).pages(assets)).containsExactly(listOf("a"), listOf("b"), listOf("c"))
        assertThat(source.lookedUp).isEmpty()
    }

    @Test
    fun `SAME_DAY_OR_PLACE accepts a partner from the same city on another day`() = runTest {
        val portrait = photo("p", at(2024, 5, 1, 12), city = "Utrecht", country = "Netherlands")
        val source = FakeSource(listOf(
            photo("otherCity", at(2023, 1, 1), city = "Paris", country = "France"),
            photo("sameCity", at(2022, 3, 3), city = "utrecht", country = "Netherlands")
        ))

        assertThat(finder(source).pages(listOf(portrait), PortraitPairing.SAME_DAY)).containsExactly(listOf("p"))
        assertThat(finder(source).pages(listOf(portrait), PortraitPairing.SAME_DAY_OR_PLACE))
            .containsExactly(listOf("p", "sameCity"))
    }

    @Test
    fun `server source asks for the nearest photos before and after on the same day`() = runTest {
        val zone = ZoneId.systemDefault()
        val apiClient = mock<ApiClient>()
        whenever(apiClient.searchPhotos(any(), anyOrNull(), anyOrNull(), any(), any(), anyOrNull(), any(), any()))
            .thenReturn(Either.Right(listOf(photo("x", at(2024, 5, 1, 13)))))
        val source = ServerPartnerCandidateSource(apiClient, pageSize = 20, zone = zone)

        val candidates = source.candidatesFor(photo("p", at(2024, 5, 1, 12, 30), city = "Utrecht"), PortraitPairing.SAME_DAY)

        assertThat(candidates.map { it.id }).containsExactly("x", "x")
        val from = argumentCaptor<LocalDateTime>()
        val to = argumentCaptor<LocalDateTime>()
        val order = argumentCaptor<String>()
        verify(apiClient, times(2)).searchPhotos(eq(20), from.capture(), to.capture(), order.capture(), eq(false), anyOrNull(), eq(emptyList()), eq(emptyList()))
        val photoTime = LocalDateTime.of(2024, 5, 1, 12, 30)
        assertThat(from.allValues).containsExactly(LocalDateTime.of(2024, 5, 1, 0, 0), photoTime).inOrder()
        assertThat(to.allValues).containsExactly(photoTime, LocalDateTime.of(2024, 5, 2, 0, 0)).inOrder()
        assertThat(order.allValues).containsExactly("desc", "asc").inOrder()
    }

    @Test
    fun `server source adds a city search for SAME_DAY_OR_PLACE and searches each album`() = runTest {
        val apiClient = mock<ApiClient>()
        whenever(apiClient.searchPhotos(any(), anyOrNull(), anyOrNull(), any(), any(), anyOrNull(), any(), any()))
            .thenReturn(Either.Right(emptyList()))
        val source = ServerPartnerCandidateSource(apiClient, albumIds = listOf("a1", "a2"))

        source.candidatesFor(photo("p", at(2024, 5, 1), city = " Utrecht "), PortraitPairing.SAME_DAY_OR_PLACE)

        for (album in listOf("a1", "a2")) {
            verify(apiClient, times(2)).searchPhotos(any(), anyOrNull(), anyOrNull(), any(), eq(false), anyOrNull(), eq(listOf(album)), eq(emptyList()))
            verify(apiClient).searchPhotos(any(), anyOrNull(), anyOrNull(), any(), eq(true), eq("Utrecht"), eq(listOf(album)), eq(emptyList()))
        }
    }

    @Test
    fun `server source searches each person on its own`() = runTest {
        val apiClient = mock<ApiClient>()
        whenever(apiClient.searchPhotos(any(), anyOrNull(), anyOrNull(), any(), any(), anyOrNull(), any(), any()))
            .thenReturn(Either.Right(emptyList()))
        val people = listOf(UUID.randomUUID(), UUID.randomUUID())
        val source = ServerPartnerCandidateSource(apiClient, personIds = people)

        source.candidatesFor(photo("p", at(2024, 5, 1)), PortraitPairing.SAME_DAY)

        for (person in people) {
            verify(apiClient, times(2)).searchPhotos(any(), anyOrNull(), anyOrNull(), any(), eq(false), anyOrNull(), eq(emptyList()), eq(listOf(person)))
        }
    }

    @Test
    fun `server source skips photos without a capture time`() = runTest {
        val apiClient = mock<ApiClient>()

        assertThat(ServerPartnerCandidateSource(apiClient).candidatesFor(photo("p", null), PortraitPairing.SAME_DAY)).isEmpty()
    }
}
