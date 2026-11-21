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

    private fun ids(signature: String) = pages.load(signature).map { page -> page.map { it.id } }

    @Test fun `empty without a queue`() {
        assertThat(pages.load("a")).isEmpty()
    }

    @Test fun `keeps the order, shown slide first`() {
        pages.save("a", listOf(listOf(asset("shown")), listOf(asset("next1")), listOf(asset("next2"))))
        assertThat(ids("a")).containsExactly(listOf("shown"), listOf("next1"), listOf("next2")).inOrder()
    }

    @Test fun `saving replaces the previous queue and keeps at most the shown slide plus three`() {
        pages.save("a", listOf(listOf(asset("old"))))
        pages.save("a", (1..6).map { listOf(asset("$it")) })
        assertThat(ids("a")).containsExactly(listOf("1"), listOf("2"), listOf("3"), listOf("4")).inOrder()
    }

    @Test fun `a portrait pair is one slide`() {
        pages.save("a", listOf(listOf(asset("1"), asset("2"))))
        assertThat(pages.load("a").single().map { it.id }).containsExactly("1", "2").inOrder()
    }

    @Test fun `queue of another source is ignored and replaced`() {
        pages.save("a", listOf(listOf(asset("1"))))
        assertThat(pages.load("b")).isEmpty()
        pages.save("b", listOf(listOf(asset("2"))))
        assertThat(pages.load("a")).isEmpty()
        assertThat(ids("b")).containsExactly(listOf("2"))
    }

    @Test fun `assets survive the round trip including dates`() {
        val taken = Date(1_700_000_000_000L)
        pages.save("a", listOf(listOf(asset("1", taken))))
        val back = pages.load("a").single().single()
        assertThat(back.exifInfo?.dateTimeOriginal).isEqualTo(taken)
        assertThat(back.exifInfo?.city).isEqualTo("Berlin")
    }

    @Test fun `unreadable queue is treated as empty`() {
        stored = "{not json"
        assertThat(pages.load("a")).isEmpty()
        pages.save("a", listOf(listOf(asset("1"))))
        assertThat(pages.load("a")).hasSize(1)
    }

    @Test fun `an empty queue is not stored`() {
        pages.save("a", listOf(listOf(asset("1"))))
        pages.save("a", emptyList())
        assertThat(ids("a")).containsExactly(listOf("1"))
    }
}
