package it.apexweather.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [ribbonIndexAt] alone: the pure arithmetic behind the ribbon's tap and drag handlers. */
class RainRibbonIndexTest {

    @Test
    fun `no bars or no width gives null`() {
        assertNull(ribbonIndexAt(0f, 320, count = 0))
        assertNull(ribbonIndexAt(0f, 0, count = 5))
    }

    @Test
    fun `the first and last bars are reachable`() {
        assertEquals(0, ribbonIndexAt(0f, 320, count = 5))
        assertEquals(4, ribbonIndexAt(320f - 0.001f, 320, count = 5))
    }

    @Test
    fun `x beyond the width clamps to the last bar`() {
        assertEquals(4, ribbonIndexAt(1000f, 320, count = 5))
    }
}
