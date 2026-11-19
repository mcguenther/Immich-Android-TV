package nl.giejay.mediaslider.animation

import nl.giejay.mediaslider.config.MotionOptions
import nl.giejay.mediaslider.config.MotionStyle
import nl.giejay.mediaslider.view.CrossfadePageTransformer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class KenBurnsPlannerTest {
    private val eps = 0.01f

    // slotW, slotH, displayW, displayH
    private val sizes = listOf(
        listOf(1920f, 1080f, 1920f, 1080f),  // exact cover
        listOf(1920f, 1080f, 2400f, 1080f),  // landscape overflowing horizontally
        listOf(1920f, 1080f, 1920f, 1500f),  // overflowing vertically
        listOf(960f, 1080f, 960f, 1400f),    // portrait half slot
        listOf(960f, 1080f, 1200f, 1080f),
        listOf(1920f, 1080f, 1440f, 1080f),  // fitted, smaller than slot
        listOf(1920f, 1080f, 1000f, 600f)
    )

    private fun assertValid(p: MotionPlan, s: List<Float>) {
        val (sw, sh, dw, dh) = s
        listOf(
            Triple(p.startScale, p.startTranslationX, p.startTranslationY),
            Triple(p.endScale, p.endTranslationX, p.endTranslationY)
        ).forEach { (sc, tx, ty) ->
            assertTrue(abs(tx) <= KenBurnsPlanner.maxTranslation(dw, sw, sc) + eps)
            assertTrue(abs(ty) <= KenBurnsPlanner.maxTranslation(dh, sh, sc) + eps)
        }
    }

    @Test
    fun noEdgeEverShownForManySeedsAndSizes() {
        for (style in MotionStyle.values()) for (s in sizes) for (seed in 0L..300L) {
            val opts = MotionOptions(style = style, zoomPercent = 12, panPercent = 8)
            val plan = KenBurnsPlanner.plan(s[0], s[1], s[2], s[3], opts, Random(seed))
            assertNotNull(plan)
            assertValid(plan!!, s)
        }
    }

    @Test
    fun disabledOrZeroGivesNull() {
        assertNull(KenBurnsPlanner.plan(1920f, 1080f, 1920f, 1080f, MotionOptions(enabled = false), Random(1)))
        assertNull(KenBurnsPlanner.plan(1920f, 1080f, 1920f, 1080f, MotionOptions(zoomPercent = 0, panPercent = 0), Random(1)))
        assertNull(KenBurnsPlanner.plan(1920f, 1080f, 1920f, 1080f, MotionOptions(zoomPercent = 0, style = MotionStyle.ZOOM_ONLY), Random(1)))
        assertNull(KenBurnsPlanner.plan(1920f, 1080f, 1920f, 1080f, MotionOptions(panPercent = 0, style = MotionStyle.PAN_ONLY), Random(1)))
    }

    @Test
    fun panOnlyKeepsScaleConstantAndTravelsAsConfigured() {
        val opts = MotionOptions(style = MotionStyle.PAN_ONLY, zoomPercent = 10, panPercent = 5)
        for (seed in 0L..200L) {
            val p = KenBurnsPlanner.plan(1920f, 1080f, 3000f, 1700f, opts, Random(seed))!!
            assertEquals(1.05f, p.startScale, 1e-5f)
            assertEquals(1.05f, p.endScale, 1e-5f)
            val dx = p.endTranslationX - p.startTranslationX
            val dy = p.endTranslationY - p.startTranslationY
            assertEquals(0.05f * 1920f, kotlin.math.sqrt(dx * dx + dy * dy), 0.5f)
        }
    }

    @Test
    fun zoomStaysWithinBaseAndBasePlusZoom() {
        val opts = MotionOptions(style = MotionStyle.ZOOM_ONLY, zoomPercent = 8, panPercent = 3)
        for (seed in 0L..100L) {
            val p = KenBurnsPlanner.plan(1920f, 1080f, 1920f, 1080f, opts, Random(seed))!!
            assertTrue(minOf(p.startScale, p.endScale) >= 1.03f - 1e-5f)
            assertEquals(0.08f, abs(p.endScale - p.startScale), 1e-4f)
            assertEquals(0f, KenBurnsPlanner.travel(p), eps)
        }
    }

    @Test
    fun sameSeedGivesSamePlan() {
        val a = KenBurnsPlanner.plan(960f, 1080f, 960f, 1400f, MotionOptions(), Random(42))
        val b = KenBurnsPlanner.plan(960f, 1080f, 960f, 1400f, MotionOptions(), Random(42))
        assertEquals(a, b)
    }

    @Test
    fun mixedStyleProducesAllKinds() {
        val kinds = (0L..60L).map {
            val p = KenBurnsPlanner.plan(1920f, 1080f, 2400f, 1300f, MotionOptions(), Random(it))!!
            when {
                p.endScale > p.startScale -> "in"
                p.endScale < p.startScale -> "out"
                else -> "pan"
            }
        }.toSet()
        assertEquals(setOf("in", "out", "pan"), kinds)
    }

    @Test
    fun crossfadeMath() {
        assertEquals(-500f, CrossfadePageTransformer.pinnedTranslationX(0.5f, 1000), 1e-4f)
        assertEquals(0.6f, CrossfadePageTransformer.alphaFor(0.4f, true), 1e-5f)
        assertEquals(1f, CrossfadePageTransformer.alphaFor(-0.4f, false), 1e-5f)
        assertEquals(0f, CrossfadePageTransformer.alphaFor(1f, true), 1e-5f)
        assertEquals(0f, CrossfadePageTransformer.alphaFor(-1.2f, false), 1e-5f)
        assertEquals(1f, CrossfadePageTransformer.alphaFor(0f, true), 1e-5f)
    }
}
