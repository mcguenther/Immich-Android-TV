package nl.giejay.mediaslider.animation

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionPlanTest {
    private val plan = MotionPlan(1.1f, 1.3f, -40f, 10f, 60f, -30f)

    @Test
    fun fromZeroIsThePlanItself() = assertEquals(plan, plan.from(0f))

    @Test
    fun fromMidwayStartsWhereTheMotionIsAndKeepsTheEnd() {
        val rest = plan.from(0.25f)
        assertEquals(1.15f, rest.startScale, 1e-5f)
        assertEquals(-15f, rest.startTranslationX, 1e-4f)
        assertEquals(0f, rest.startTranslationY, 1e-4f)
        assertEquals(plan.endScale, rest.endScale, 0f)
        assertEquals(plan.endTranslationX, rest.endTranslationX, 0f)
        assertEquals(plan.endTranslationY, rest.endTranslationY, 0f)
    }

    @Test
    fun progressIsClamped() {
        assertEquals(plan, plan.from(-1f))
        val end = plan.from(2f)
        assertEquals(plan.endScale, end.startScale, 0f)
        assertEquals(plan.endTranslationX, end.startTranslationX, 0f)
        assertEquals(plan.endTranslationY, end.startTranslationY, 0f)
    }
}
