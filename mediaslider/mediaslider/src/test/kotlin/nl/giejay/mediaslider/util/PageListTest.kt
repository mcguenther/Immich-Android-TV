package nl.giejay.mediaslider.util

import nl.giejay.mediaslider.model.SliderItem
import nl.giejay.mediaslider.model.SliderItemType
import nl.giejay.mediaslider.model.SliderItemViewHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PageListTest {
    private fun item(id: String) =
        SliderItem(id, "https://immich/$id", SliderItemType.IMAGE, 0, emptyMap(), null, false)

    private fun page(id: String) = SliderItemViewHolder(item(id))
    private fun pair(a: String, b: String) = SliderItemViewHolder(item(a), item(b))
    private fun pages(vararg ids: String) = ids.map { page(it) }
    private fun range(prefix: String, count: Int) = List(count) { page("$prefix$it") }

    private fun ids(pages: List<SliderItemViewHolder>) = pages.map { it.ids().joinToString("+") }

    /** Smallest number of slides between two showings of the same asset (Int.MAX_VALUE without repeats). */
    private fun minRepeatDistance(pages: List<SliderItemViewHolder>): Int {
        val last = HashMap<String, Int>()
        var min = Int.MAX_VALUE
        pages.forEachIndexed { index, p ->
            p.ids().forEach { id ->
                last[id]?.let { min = minOf(min, index - it) }
                last[id] = index
            }
        }
        return min
    }

    @Test
    fun appendsNewPagesAndKeepsExistingObjects() {
        val existing = pages("a", "b")
        val merged = PageList.appendNew(existing, pages("c", "d"))
        assertEquals(listOf("a", "b", "c", "d"), ids(merged))
        assertSame(existing[0], merged[0])
        assertSame(existing[1], merged[1])
    }

    @Test
    fun dropsAPageThatShowsTheLastPhotoAgain() {
        // Random sources sample with replacement: the batch may start with the photo on screen.
        val merged = PageList.appendNew(pages("a", "b"), pages("b", "c"))
        assertEquals(listOf("a", "b", "c"), ids(merged))
    }

    @Test
    fun aPairKeepsItsNewPhotoWhenTheOtherOneRepeats() {
        val merged = PageList.appendNew(listOf(pair("a", "b")), listOf(page("b"), pair("c", "a"), pair("d", "e")))
        assertEquals(listOf("a+b", "c", "d+e"), ids(merged))
    }

    @Test
    fun dropsRepeatsWithinTheNewBatch() {
        val merged = PageList.appendNew(pages("a"), listOf(page("c"), page("c"), pair("d", "c")))
        assertEquals(listOf("a", "c", "d"), ids(merged))
    }

    @Test
    fun firstBatchOnlyLosesItsOwnRepeats() {
        assertEquals(listOf("a", "b"), ids(PageList.appendNew(emptyList(), pages("a", "b", "a"))))
    }

    @Test
    fun noRepeatAtAllWhileTheSourceDeliversEnoughNewAssets() {
        // 30 of 40 per batch are new: repeats are dropped even when they were shown long ago.
        var list = range("old", 300)
        val batch = range("new", 30) + range("old", 10)
        list = PageList.appendNew(list, batch)
        assertEquals(330, list.size)
        assertEquals(Int.MAX_VALUE, minRepeatDistance(list))
    }

    @Test
    fun exhaustedSourceRepeatsOnlyWhatWasNotShownInTheLast100Slides() {
        val existing = range("p", 150)
        // Only repeats: p0..p49 are older than 100 slides, p100..p149 are recent.
        val merged = PageList.appendNew(existing, pages("p10", "p120", "p49", "p50", "p149"))
        assertEquals(listOf("p10", "p49"), ids(merged.drop(150)))
    }

    @Test
    fun exhaustedSmallLibraryAddsNothing() {
        val existing = range("p", 60)
        assertSame(existing, PageList.appendNew(existing, pages("p0", "p30", "p59")))
    }

    @Test
    fun randomSourceNeverRepeatsWithin100Slides() {
        // A random source with replacement over a library of 180 assets, many batches.
        val random = Random(42)
        var list = emptyList<SliderItemViewHolder>()
        repeat(200) {
            val batch = List(30) {
                if (random.nextInt(4) == 0) pair("x${random.nextInt(180)}", "x${random.nextInt(180)}")
                else page("x${random.nextInt(180)}")
            }
            list = PageList.appendNew(list, batch)
        }
        assertTrue(list.size > 400)
        assertTrue(minRepeatDistance(list) > PageList.NO_REPEAT_WINDOW)
    }

    @Test
    fun wrapTargetIsTheStartForAListOfDistinctAssets() {
        assertEquals(0, PageList.wrapTarget(range("a", 250)))
    }

    @Test
    fun wrapTargetIsTheStartForAShortList() {
        // Fewer pages than the window: repeating is unavoidable, start over.
        assertEquals(0, PageList.wrapTarget(range("a", 30)))
    }

    @Test
    fun wrapTargetSkipsPagesThatWereJustShown() {
        // The list ends with assets that are also near its start.
        val items = range("a", 200) + pages("a3", "b1", "a7")
        val target = PageList.wrapTarget(items)
        assertEquals(8, target)
        // Wrapping there keeps the distance: end of list followed by the list from the target.
        assertTrue(minRepeatDistance(items + items.drop(target)) > PageList.NO_REPEAT_WINDOW)
    }

    @Test
    fun emptyLoadBackoffGrowsAndIsCapped() {
        assertEquals(0L, PageList.emptyLoadBackoffMs(0))
        assertEquals(15_000L, PageList.emptyLoadBackoffMs(1))
        assertEquals(30_000L, PageList.emptyLoadBackoffMs(2))
        assertEquals(240_000L, PageList.emptyLoadBackoffMs(5))
        assertEquals(240_000L, PageList.emptyLoadBackoffMs(50))
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
