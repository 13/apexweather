package it.apexweather.ui.home

import it.apexweather.domain.model.Condition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bar is millimetres on a fixed square-root scale. It used to be millimetres on a fixed *linear*
 * 0-5 mm scale, which drew under two pixels for an hour certain to bring 0,4 mm.
 */
class PrecipScaleTest {

    @Test
    fun `a full bar is the top of the scale`() {
        assertEquals(1f, PrecipScale.fillFraction(PrecipScale.FULL_SCALE_MM), 1e-6f)
    }

    /** Past the cap the bar stays full; the millimetres printed under it say the rest. */
    @Test
    fun `more than the scale holds is still one bar`() {
        assertEquals(1f, PrecipScale.fillFraction(48.0), 1e-6f)
    }

    @Test
    fun `no rain is no bar`() {
        assertEquals(0f, PrecipScale.fillFraction(0.0), 1e-6f)
    }

    /**
     * The whole point of the square root: on a linear 0-10 mm scale these would be 2 %, 10 % and
     * 25 % of the track, and the first two would be invisible.
     */
    @Test
    fun `the bottom of the range is stretched enough to see`() {
        assertEquals(0.141f, PrecipScale.fillFraction(0.2), 1e-3f)
        assertEquals(0.316f, PrecipScale.fillFraction(1.0), 1e-3f)
        assertEquals(0.500f, PrecipScale.fillFraction(2.5), 1e-3f)
    }

    /** A height always means the same rain, whatever else is on screen. */
    @Test
    fun `the scale does not depend on the other hours`() {
        assertEquals(PrecipScale.fillFraction(1.0), PrecipScale.fillFraction(1.0), 0f)
        assertTrue(PrecipScale.fillFraction(2.0) > PrecipScale.fillFraction(1.0))
    }

    @Test
    fun `an amount is printed only once there is one`() {
        assertFalse(PrecipScale.hasAmount(0.0))
        assertFalse(PrecipScale.hasAmount(0.04))
        assertTrue(PrecipScale.hasAmount(0.1))
    }

    /** The smallest amount worth printing is still worth drawing. */
    @Test
    fun `the smallest printed amount draws a visible bar`() {
        assertTrue(PrecipScale.fillFraction(PrecipScale.MIN_PRINTED_MM) >= 0.09f)
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
