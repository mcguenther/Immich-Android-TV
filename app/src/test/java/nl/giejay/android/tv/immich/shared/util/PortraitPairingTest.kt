package nl.giejay.android.tv.immich.shared.util

import com.google.common.truth.Truth.assertThat
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.AssetExifInfo
import nl.giejay.android.tv.immich.shared.prefs.PortraitPairing
import org.junit.Test
import java.util.Calendar
import java.util.Date

/**
 * Covers which portrait photos are paired on one slider page and which portrait videos are dropped.
 * Pure JVM: exercises [groupForSlider] and [isPortraitVideo] directly.
 */
class PortraitPairingTest {

    private val portraitW = 3000
    private val portraitH = 4000
    private val landscapeW = 4000
    private val landscapeH = 3000

    /** Local time on the given day, so the calendar-day checks use the device time zone like production. */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Date =
        Calendar.getInstance().apply { clear(); set(year, month - 1, day, hour, 0, 0) }.time

    private fun photo(
        id: String,
        taken: Date?,
        city: String? = null,
        country: String? = null,
        portrait: Boolean = true
    ) = Asset(
        id = id,
        type = "IMAGE",
        deviceAssetId = null,
        exifInfo = exif(
            orientation = 1,
            width = if (portrait) portraitW else landscapeW,
            height = if (portrait) portraitH else landscapeH,
            city = city,
            country = country,
            taken = taken
        ),
        fileModifiedAt = null,
        albumName = null,
        people = null,
        tags = null,
        originalPath = null,
        originalFileName = null
    )

    private fun video(
        id: String,
        width: Int?,
        height: Int?,
        orientation: Int? = 1
    ) = Asset(
        id = id,
        type = "VIDEO",
        deviceAssetId = null,
        exifInfo = exif(orientation = orientation, width = width, height = height, city = null, country = null, taken = at(2024, 5, 1)),
        fileModifiedAt = null,
        albumName = null,
        people = null,
        tags = null,
        originalPath = null,
        originalFileName = null
    )

    private fun exif(orientation: Int?, width: Int?, height: Int?, city: String?, country: String?, taken: Date?) =
        AssetExifInfo(
            description = null,
            orientation = orientation,
            exifImageWidth = width,
            exifImageHeight = height,
            city = city,
            country = country,
            dateTimeOriginal = taken,
            make = null,
            model = null
        )

    private fun group(
        assets: List<Asset>,
        pairing: PortraitPairing,
        keepOrder: Boolean = true,
        showPortraitVideos: Boolean = true,
        keepAssetId: String? = null
    ): List<List<String>> = groupForSlider(
        assets, keepOrder, SliderItemOptions(pairing, showPortraitVideos), keepAssetId
    ).map { page -> page.map { it.id } }

    @Test
    fun `portrait photos taken on the same day are paired`() {
        val a = photo("a", at(2024, 5, 1, 9))
        val b = photo("b", at(2024, 5, 1, 18))

        assertThat(group(listOf(a, b), PortraitPairing.SAME_DAY, keepOrder = false))
            .containsExactly(listOf("a", "b"))
    }

    @Test
    fun `portrait photos on different days are not paired with SAME_DAY`() {
        val a = photo("a", at(2024, 5, 1))
        val b = photo("b", at(2024, 5, 2))

        assertThat(group(listOf(a, b), PortraitPairing.SAME_DAY, keepOrder = false))
            .containsExactly(listOf("a"), listOf("b")).inOrder()
    }

    @Test
    fun `same city on different days is paired only with SAME_DAY_OR_PLACE`() {
        val a = photo("a", at(2024, 5, 1), city = "Amsterdam", country = "Netherlands")
        val b = photo("b", at(2023, 1, 10), city = "Amsterdam", country = "Netherlands")

        assertThat(group(listOf(a, b), PortraitPairing.SAME_DAY, keepOrder = false))
            .containsExactly(listOf("a"), listOf("b")).inOrder()
        assertThat(group(listOf(a, b), PortraitPairing.SAME_DAY_OR_PLACE, keepOrder = false))
            .containsExactly(listOf("a", "b"))
    }

    @Test
    fun `city comparison ignores case and surrounding whitespace`() {
        val a = photo("a", at(2024, 5, 1), city = " Amsterdam ", country = "NL")
        val b = photo("b", at(2023, 1, 10), city = "amsterdam", country = "nl")

        assertThat(group(listOf(a, b), PortraitPairing.SAME_DAY_OR_PLACE, keepOrder = false))
            .containsExactly(listOf("a", "b"))
    }

    @Test
    fun `same city in a different country is not paired`() {
        val a = photo("a", at(2024, 5, 1), city = "Springfield", country = "United States")
        val b = photo("b", at(2023, 1, 10), city = "Springfield", country = "Canada")

        assertThat(group(listOf(a, b), PortraitPairing.SAME_DAY_OR_PLACE, keepOrder = false))
            .containsExactly(listOf("a"), listOf("b")).inOrder()
    }

    @Test
    fun `same city without a country on one side is paired`() {
        val a = photo("a", at(2024, 5, 1), city = "Utrecht", country = "Netherlands")
        val b = photo("b", at(2023, 1, 10), city = "Utrecht", country = null)

        assertThat(group(listOf(a, b), PortraitPairing.SAME_DAY_OR_PLACE, keepOrder = false))
            .containsExactly(listOf("a", "b"))
    }

    @Test
    fun `partner closest in time is chosen`() {
        val a = photo("a", at(2024, 5, 1, 10))
        val far = photo("far", at(2024, 5, 1, 20))
        val near = photo("near", at(2024, 5, 1, 11))

        assertThat(group(listOf(a, far, near), PortraitPairing.SAME_DAY, keepOrder = false))
            .containsExactly(listOf("a", "near"), listOf("far")).inOrder()
    }

    @Test
    fun `portrait without a matching partner is shown alone`() {
        val a = photo("a", at(2024, 5, 1))
        val b = photo("b", at(2024, 6, 1))
        val landscape = photo("l", at(2024, 5, 1), portrait = false)

        assertThat(group(listOf(a, b, landscape), PortraitPairing.SAME_DAY, keepOrder = false))
            .containsExactly(listOf("l"), listOf("a"), listOf("b")).inOrder()
    }

    @Test
    fun `keepOrder pairs only consecutive matching portraits`() {
        val p1 = photo("p1", at(2024, 5, 1))
        val p2 = photo("p2", at(2024, 5, 2))
        val p3 = photo("p3", at(2024, 5, 2))

        assertThat(group(listOf(p1, p2, p3), PortraitPairing.SAME_DAY))
            .containsExactly(listOf("p1"), listOf("p2", "p3")).inOrder()
    }

    @Test
    fun `keepOrder does not pair portraits separated by another asset`() {
        val p1 = photo("p1", at(2024, 5, 1))
        val landscape = photo("l", at(2024, 5, 1), portrait = false)
        val p2 = photo("p2", at(2024, 5, 1))

        assertThat(group(listOf(p1, landscape, p2), PortraitPairing.SAME_DAY))
            .containsExactly(listOf("p1"), listOf("l"), listOf("p2")).inOrder()
    }

    @Test
    fun `landscape photos are never paired`() {
        val l1 = photo("l1", at(2024, 5, 1), city = "Paris", country = "France", portrait = false)
        val l2 = photo("l2", at(2024, 5, 1), city = "Paris", country = "France", portrait = false)
        val p = photo("p", at(2024, 5, 1), city = "Paris", country = "France")

        assertThat(group(listOf(l1, l2), PortraitPairing.SAME_DAY_OR_PLACE, keepOrder = false))
            .containsExactly(listOf("l1"), listOf("l2")).inOrder()
        assertThat(group(listOf(l1, p), PortraitPairing.SAME_DAY_OR_PLACE, keepOrder = false))
            .containsExactly(listOf("l1"), listOf("p")).inOrder()
    }

    @Test
    fun `OFF never pairs and keeps the given order`() {
        val a = photo("a", at(2024, 5, 1))
        val b = photo("b", at(2024, 5, 1))
        val c = photo("c", at(2024, 5, 1))

        assertThat(group(listOf(c, a, b), PortraitPairing.OFF, keepOrder = false))
            .containsExactly(listOf("c"), listOf("a"), listOf("b")).inOrder()
    }

    @Test
    fun `portrait video is dropped when portrait videos are hidden`() {
        val portraitVideo = video("v", width = 1080, height = 1920)
        val photo = photo("p", at(2024, 5, 1))

        assertThat(group(listOf(portraitVideo, photo), PortraitPairing.OFF, showPortraitVideos = false))
            .containsExactly(listOf("p"))
    }

    @Test
    fun `portrait video is kept when it is the keepAssetId`() {
        val portraitVideo = video("v", width = 1080, height = 1920)

        assertThat(group(listOf(portraitVideo), PortraitPairing.OFF, showPortraitVideos = false, keepAssetId = "v"))
            .containsExactly(listOf("v"))
    }

    @Test
    fun `portrait video is kept when portrait videos are shown`() {
        val portraitVideo = video("v", width = 1080, height = 1920)

        assertThat(group(listOf(portraitVideo), PortraitPairing.OFF, showPortraitVideos = true))
            .containsExactly(listOf("v"))
    }

    @Test
    fun `landscape video is never dropped`() {
        val landscapeVideo = video("v", width = 1920, height = 1080)

        assertThat(group(listOf(landscapeVideo), PortraitPairing.OFF, showPortraitVideos = false))
            .containsExactly(listOf("v"))
    }

    @Test
    fun `video with orientation 6 and landscape dimensions counts as portrait`() {
        val rotated = video("v", width = 1920, height = 1080, orientation = 6)

        assertThat(rotated.isPortraitVideo()).isTrue()
        assertThat(group(listOf(rotated), PortraitPairing.OFF, showPortraitVideos = false)).isEmpty()
    }

    @Test
    fun `video with orientation 1 and landscape dimensions is not portrait`() {
        assertThat(video("v", width = 1920, height = 1080, orientation = 1).isPortraitVideo()).isFalse()
    }

    @Test
    fun `video with unknown dimensions is kept`() {
        val noDims = video("a", width = null, height = null)
        val zeroHeight = video("b", width = 1080, height = 0)
        val noExif = noDims.copy(id = "c", exifInfo = null)

        assertThat(noDims.isPortraitVideo()).isFalse()
        assertThat(zeroHeight.isPortraitVideo()).isFalse()
        assertThat(noExif.isPortraitVideo()).isFalse()
        assertThat(group(listOf(noDims, zeroHeight, noExif), PortraitPairing.OFF, showPortraitVideos = false))
            .containsExactly(listOf("a"), listOf("b"), listOf("c")).inOrder()
    }

    @Test
    fun `photo is never a portrait video`() {
        assertThat(photo("p", at(2024, 5, 1)).isPortraitVideo()).isFalse()
    }
}
