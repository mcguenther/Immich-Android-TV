package nl.giejay.android.tv.immich.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileAnchorDayStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `days are written and read back, including missing values and odd characters`() {
        val store = FileAnchorDayStore { folder.root }
        val day = CachedAnchorDay(
            1_700_000_000_000L,
            listOf(
                LocationAnchor("a1", "me", 1_749_902_700_000L, 47.2692, 11.4041, "Innsbruck", "Österreich"),
                LocationAnchor("a2", null, 1_749_902_800_000L, -33.86, 151.21, "Sydney\twith tab", null)
            )
        )
        store.save(20_253L, day)

        val read = FileAnchorDayStore { folder.root }.load(20_253L)!!
        assertEquals(day.fetchedAtMs, read.fetchedAtMs)
        assertEquals(day.anchors[0], read.anchors[0])
        assertEquals(null, read.anchors[1].ownerId)
        assertEquals("Sydney with tab", read.anchors[1].city)
        assertNull(store.load(20_254L))
    }

    @Test
    fun `unreadable files are ignored`() {
        folder.root.resolve("5.tsv").writeText("1700000000000\nbroken line\n")
        assertNull(FileAnchorDayStore { folder.root }.load(5L))
    }
}
