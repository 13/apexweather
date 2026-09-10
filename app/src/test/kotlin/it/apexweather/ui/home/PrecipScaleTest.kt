package it.apexweather.ui.home

import it.apexweather.domain.model.Condition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bar used to be millimetres on a fixed 0-5 mm scale, so an hour certain to bring 0,4 mm drew
 * under two pixels of bar beneath a caption reading 100 %. Height is probability now, and these are
 * the rules that make that readable.
 */
class PrecipScaleTest {

    @Test
    fun `the fill is the probability`() {
        assertEquals(0f, PrecipScale.fillFraction(0), 1e-6f)
        assertEquals(0.64f, PrecipScale.fillFraction(64), 1e-6f)
        assertEquals(1f, PrecipScale.fillFraction(100), 1e-6f)
    }

    /** Upstreams are not trusted to stay inside 0..100; a bar taller than its track would overdraw. */
    @Test
    fun `a probability outside the scale is clamped`() {
        assertEquals(0f, PrecipScale.fillFraction(-5), 1e-6f)
        assertEquals(1f, PrecipScale.fillFraction(140), 1e-6f)
    }

    @Test
    fun `a chance too small to mean anything leaves the track empty`() {
        assertFalse(PrecipScale.isDrawn(0))
        assertFalse(PrecipScale.isDrawn(4))
        assertTrue(PrecipScale.isDrawn(5))
    }

    /** Absence must never read as zero: below a tenth of a millimetre nothing is printed at all. */
    @Test
    fun `an amount is printed only once there is one`() {
        assertFalse(PrecipScale.hasAmount(0.0))
        assertFalse(PrecipScale.hasAmount(0.04))
        assertTrue(PrecipScale.hasAmount(0.1))
    }

    @Test
    fun `the colour is the intensity`() {
        assertEquals(PrecipScale.LIGHT, PrecipScale.fillColor(0.2, Condition.DRIZZLE))
        assertEquals(PrecipScale.MODERATE, PrecipScale.fillColor(1.4, Condition.RAIN))
        assertEquals(PrecipScale.HEAVY, PrecipScale.fillColor(6.0, Condition.HEAVY_RAIN))
    }

    /** Frozen precipitation is never painted as rain, however much of it there is. */
    @Test
    fun `snow and sleet keep their own colour at every amount`() {
        listOf(Condition.SNOW, Condition.HEAVY_SNOW, Condition.SLEET).forEach {
            assertEquals("$it", PrecipScale.FROZEN, PrecipScale.fillColor(0.2, it))
            assertEquals("$it", PrecipScale.FROZEN, PrecipScale.fillColor(9.0, it))
        }
    }

    /** A thunderstorm's rain is rain; only the frozen three are special-cased. */
    @Test
    fun `a thunderstorm is coloured by its amount like any other rain`() {
        assertEquals(PrecipScale.HEAVY, PrecipScale.fillColor(6.0, Condition.THUNDERSTORM))
    }
}
