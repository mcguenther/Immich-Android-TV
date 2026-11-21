package nl.giejay.mediaslider.util

import nl.giejay.mediaslider.model.SliderItem
import nl.giejay.mediaslider.model.SliderItemType
import nl.giejay.mediaslider.model.SliderItemViewHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PageListTest {
    private fun item(id: String) =
        SliderItem(id, "https://immich/$id", SliderItemType.IMAGE, 0, emptyMap(), null, false)

    private fun page(id: String) = SliderItemViewHolder(item(id))
    private fun pair(a: String, b: String) = SliderItemViewHolder(item(a), item(b))

    private fun ids(pages: List<SliderItemViewHolder>) = pages.map { it.ids().joinToString("+") }

    @Test
    fun appendsNewPagesAndKeepsExistingObjects() {
        val existing = listOf(page("a"), page("b"))
        val merged = PageList.appendNew(existing, listOf(page("c"), page("d")))
        assertEquals(listOf("a", "b", "c", "d"), ids(merged))
        assertSame(existing[0], merged[0])
        assertSame(existing[1], merged[1])
    }

    @Test
    fun dropsAPageThatShowsTheLastPhotoAgain() {
        // Random sources sample with replacement: the batch may start with the photo on screen.
        val merged = PageList.appendNew(listOf(page("a"), page("b")), listOf(page("b"), page("c")))
        assertEquals(listOf("a", "b", "c"), ids(merged))
    }

    @Test
    fun dropsAPageWhoseAssetComesBackInAnotherPairing() {
        val merged = PageList.appendNew(listOf(pair("a", "b")), listOf(page("b"), pair("c", "a"), pair("d", "e")))
        assertEquals(listOf("a+b", "d+e"), ids(merged))
    }

    @Test
    fun dropsRepeatsWithinTheNewBatch() {
        val merged = PageList.appendNew(listOf(page("a")), listOf(page("c"), page("c"), pair("d", "c")))
        assertEquals(listOf("a", "c"), ids(merged))
    }

    @Test
    fun neverTwoPagesInARowWithTheSameAsset() {
        val batches = List(30) { b -> List(10) { i -> page("x${(b * 7 + i * 3) % 25}") } }
        var pages = emptyList<SliderItemViewHolder>()
        batches.forEach { pages = PageList.appendNew(pages, it) }
        pages.zipWithNext().forEach { (prev, next) ->
            assertTrue(prev.ids().intersect(next.ids().toSet()).isEmpty())
        }
    }

    @Test
    fun keepsAPageOnlyWhenTheSameObjectIsStillAtItsPosition() {
        val a = page("a")
        val b = page("b")
        assertTrue(PageList.canKeepPage(b, 1, listOf(a, b, page("c"))))
        assertFalse(PageList.canKeepPage(b, 1, listOf(a, page("b"))))
        assertFalse(PageList.canKeepPage(b, 1, listOf(b, a)))
        assertFalse(PageList.canKeepPage(b, 3, listOf(a, b)))
        assertFalse(PageList.canKeepPage(null, 0, listOf(a)))
    }
}
