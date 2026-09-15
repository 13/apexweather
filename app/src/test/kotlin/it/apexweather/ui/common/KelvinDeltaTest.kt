package it.apexweather.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class KelvinDeltaTest {
    private val de = Formats(Locale.GERMANY, true)
    private val en = Formats(Locale.US, true)

    @Test
    fun `a difference always carries its sign`() {
        assertEquals("+1,4 K", Format.kelvinDelta(1.4, de))
        assertEquals("-0,8 K", Format.kelvinDelta(-0.8, de))
        assertEquals("+1.4 K", Format.kelvinDelta(1.4, en))
    }

    /** "-0,0" is a rounding artefact; spot on reads as spot on. */
    @Test
    fun `anything that rounds to zero is plus-minus zero`() {
        assertEquals("±0,0 K", Format.kelvinDelta(0.0, de))
        assertEquals("±0,0 K", Format.kelvinDelta(-0.04, de))
        assertEquals("±0,0 K", Format.kelvinDelta(0.04, de))
    }
}
