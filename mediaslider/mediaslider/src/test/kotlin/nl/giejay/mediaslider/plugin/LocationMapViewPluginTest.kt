package nl.giejay.mediaslider.plugin

import nl.giejay.mediaslider.map.LocationMapRenderer
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationMapViewPluginTest {

    @Test
    fun `dot ends up just above the text block`() {
        val mapHeight = 540
        val textTop = 250
        val gap = 28f

        val translation = LocationMapViewPlugin.mapTranslationY(mapHeight, textTop, gap)

        // bottom-aligned map moved by translation: dot distance from screen bottom
        val dotFromBottom = (1 - LocationMapRenderer.ANCHOR_Y) * mapHeight - translation
        assertEquals(textTop + gap, dotFromBottom, 1e-3f)
    }

    @Test
    fun `tall text blocks move the map up`() {
        val translation = LocationMapViewPlugin.mapTranslationY(540, 600, 28f)
        assert(translation < 0f)
    }
}
