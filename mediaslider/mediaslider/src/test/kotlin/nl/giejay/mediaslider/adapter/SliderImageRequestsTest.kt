package nl.giejay.mediaslider.adapter

import nl.giejay.mediaslider.view.FitResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SliderImageRequestsTest {
    @Test fun fittedPanoramaIsDecodedAtCoverSize() {
        // 4.8:1 panorama fitted into 1920x1080 -> 1920x400; the animator scales it to 1080 high.
        val size = SliderImageRequests.panoramaDecodeSize(FitResult(1920, 400, false, 0.5f), 1920, 1080)
        assertEquals(5184 to 1080, size)
    }

    @Test fun coveringResultIsLeftAlone() =
        assertNull(SliderImageRequests.panoramaDecodeSize(FitResult(2000, 1100, true, 0f), 1920, 1080))

    @Test fun panoramaCapKeepsAspect() {
        assertEquals(4096 to 853, SliderImageRequests.capLongEdge(5184, 1080, SliderImageRequests.PANORAMA_MAX_DECODE_LONG_EDGE))
    }
}
