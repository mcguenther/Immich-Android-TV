package nl.giejay.android.tv.immich.screensaver

import com.google.common.truth.Truth.assertThat
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.AssetExifInfo
import org.junit.Test
import java.util.Date

class RecentScreenSaverPagesTest {
    private var stored: String? = null
    private val pages = RecentScreenSaverPages(read = { stored }, write = { stored = it })

    private fun asset(id: String, taken: Date? = null) = Asset(
        id = id, type = "IMAGE", deviceAssetId = null,
        exifInfo = AssetExifInfo(null, 1, 100, 200, "Berlin", "Germany", taken, null, null),
        fileModifiedAt = null, albumName = null, people = null, tags = null,
        originalPath = null, originalFileName = null
    )

    @Test fun `empty without history`() {
        assertThat(pages.load("a")).isEmpty()
    }

    @Test fun `newest slide first and at most three`() {
        listOf("1", "2", "3", "4").forEach { pages.record("a", listOf(asset(it))) }
        assertThat(pages.load("a").map { page -> page.map { it.id } })
            .containsExactly(listOf("4"), listOf("3"), listOf("2")).inOrder()
    }

    @Test fun `showing a slide again moves it to the front instead of duplicating it`() {
        pages.record("a", listOf(asset("1")))
        pages.record("a", listOf(asset("2")))
        pages.record("a", listOf(asset("1")))
        assertThat(pages.load("a").map { page -> page.map { it.id } })
            .containsExactly(listOf("1"), listOf("2")).inOrder()
    }

    @Test fun `a portrait pair is one slide`() {
        pages.record("a", listOf(asset("1"), asset("2")))
        assertThat(pages.load("a").single().map { it.id }).containsExactly("1", "2").inOrder()
    }

    @Test fun `history of another source is ignored and replaced`() {
        pages.record("a", listOf(asset("1")))
        assertThat(pages.load("b")).isEmpty()
        pages.record("b", listOf(asset("2")))
        assertThat(pages.load("a")).isEmpty()
        assertThat(pages.load("b").single().single().id).isEqualTo("2")
    }

    @Test fun `assets survive the round trip including dates`() {
        val taken = Date(1_700_000_000_000L)
        pages.record("a", listOf(asset("1", taken)))
        val back = pages.load("a").single().single()
        assertThat(back.exifInfo?.dateTimeOriginal).isEqualTo(taken)
        assertThat(back.exifInfo?.city).isEqualTo("Berlin")
    }

    @Test fun `unreadable history is treated as empty`() {
        stored = "{not json"
        assertThat(pages.load("a")).isEmpty()
        pages.record("a", listOf(asset("1")))
        assertThat(pages.load("a")).hasSize(1)
    }
}
