package it.apexweather.domain

import it.apexweather.domain.model.Condition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodeTablesTest {
    @Test fun `wmo codes`() {
        assertEquals(Condition.CLEAR, WmoCodes.toCondition(0))
        assertEquals(Condition.PARTLY_CLOUDY, WmoCodes.toCondition(2))
        assertEquals(Condition.FOG, WmoCodes.toCondition(45))
        assertEquals(Condition.DRIZZLE, WmoCodes.toCondition(53))
        assertEquals(Condition.RAIN, WmoCodes.toCondition(61))
        assertEquals(Condition.HEAVY_RAIN, WmoCodes.toCondition(65))
        assertEquals(Condition.HEAVY_RAIN, WmoCodes.toCondition(82))
        assertEquals(Condition.SLEET, WmoCodes.toCondition(66))
        assertEquals(Condition.SNOW, WmoCodes.toCondition(71))
        assertEquals(Condition.HEAVY_SNOW, WmoCodes.toCondition(75))
        assertEquals(Condition.THUNDERSTORM, WmoCodes.toCondition(95))
        assertEquals(Condition.CLOUDY, WmoCodes.toCondition(null))
    }

    @Test fun `siag letters`() {
        assertEquals(Condition.CLEAR, SiagCodes.toCondition("a"))
        assertEquals(Condition.MOSTLY_CLEAR, SiagCodes.toCondition("b"))
        assertEquals(Condition.CLOUDY, SiagCodes.toCondition("e"))
        assertEquals(Condition.RAIN, SiagCodes.toCondition("f"))
        assertEquals(Condition.HEAVY_RAIN, SiagCodes.toCondition("i"))
        assertEquals(Condition.DRIZZLE, SiagCodes.toCondition("j"))
        assertEquals(Condition.SNOW, SiagCodes.toCondition("n"))
        assertEquals(Condition.HEAVY_SNOW, SiagCodes.toCondition("p"))
        assertEquals(Condition.SLEET, SiagCodes.toCondition("r"))
        assertEquals(Condition.FOG, SiagCodes.toCondition("t"))
        assertEquals(Condition.THUNDERSTORM, SiagCodes.toCondition("u"))
        assertEquals(Condition.THUNDERSTORM, SiagCodes.toCondition("z"))
        assertEquals(Condition.CLEAR, SiagCodes.toCondition("a_n")) // KMOS day/night suffix
        assertEquals(Condition.CLOUDY, SiagCodes.toCondition(null))
        assertEquals("https://api-weather.services.siag.it/api/v2/graphics/icons/HDimgsource/wetter/icon_7.png", SiagCodes.iconUrl("g"))
    }

    /** An empty or junk code used to throw here, and the exception failed the entire bulletin. */
    @Test
    fun `iconUrl returns null rather than throwing on a code that is not a letter`() {
        assertNull(SiagCodes.iconUrl(""))
        assertNull(SiagCodes.iconUrl("   "))
        assertNull(SiagCodes.iconUrl("1"))
        assertNull(SiagCodes.iconUrl("-"))
    }
}
