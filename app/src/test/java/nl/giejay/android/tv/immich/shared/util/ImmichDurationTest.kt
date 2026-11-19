package nl.giejay.android.tv.immich.shared.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImmichDurationTest {
    @Test
    fun parsesImmichFormat() {
        assertEquals(12_345L, parseImmichDurationMs("0:00:12.345000"))
        assertEquals(3_723_500L, parseImmichDurationMs("1:02:03.500000"))
        assertEquals(5_000L, parseImmichDurationMs("0:00:05"))
    }

    @Test
    fun rejectsMissingOrBroken() {
        assertNull(parseImmichDurationMs(null))
        assertNull(parseImmichDurationMs(""))
        assertNull(parseImmichDurationMs("abc"))
        assertNull(parseImmichDurationMs("0:00:00.000000"))
    }
}
