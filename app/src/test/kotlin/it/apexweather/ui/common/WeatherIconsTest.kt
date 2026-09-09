package it.apexweather.ui.common

import it.apexweather.domain.SunPhase
import it.apexweather.domain.model.Condition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherIconsTest {

    /**
     * The whole point of drawing a set instead of borrowing stock glyphs: three weights of rain
     * and two of snow have to be told apart at a glance, and clear must not look like overcast.
     */
    @Test
    fun `every condition has an icon of its own`() {
        val icons = Condition.entries.map { it.iconRes(SunPhase.DAY) }
        assertEquals("two conditions share a drawing: $icons", Condition.entries.size, icons.toSet().size)
    }

    @Test
    fun `every condition has a label of its own`() {
        assertEquals(Condition.entries.size, Condition.entries.map { it.labelRes() }.toSet().size)
    }

    /** A clear night must not be drawn as a sunny day. */
    @Test
    fun `the sky conditions differ between day and night`() {
        listOf(Condition.CLEAR, Condition.MOSTLY_CLEAR, Condition.PARTLY_CLOUDY).forEach {
            assertNotEquals("$it looks the same by night as by day", it.iconRes(SunPhase.DAY), it.iconRes(SunPhase.NIGHT))
        }
    }

    /** Below the clouds there is no sun to draw, so those icons must not change with the hour. */
    @Test
    fun `weather below the cloud looks the same whatever the hour`() {
        val unaffected = Condition.entries - setOf(Condition.CLEAR, Condition.MOSTLY_CLEAR, Condition.PARTLY_CLOUDY)
        unaffected.forEach { assertEquals(it.iconRes(SunPhase.DAY), it.iconRes(SunPhase.NIGHT)) }
    }

    /** Day is what a caller gets without asking, which is what the daily rows rely on. */
    @Test
    fun `the default phase is day`() {
        assertTrue(Condition.entries.all { it.iconRes() == it.iconRes(SunPhase.DAY) })
    }
}
