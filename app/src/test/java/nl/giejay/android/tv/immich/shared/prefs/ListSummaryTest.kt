package nl.giejay.android.tv.immich.shared.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ListPreference formats its summary with String.format; escaped texts must come out unchanged. */
class ListSummaryTest {
    @Test
    fun percentTextsSurviveListPreferenceFormatting() {
        listOf("30%", "4:3 needs 25%, 1:1 needs 44%", "30 fps plays at 80% speed", "Unlimited").forEach { text ->
            assertEquals(text, String.format(listSummary(text)!!, "entry"))
        }
    }

    @Test
    fun nullStaysNull() {
        assertNull(listSummary(null))
    }
}
