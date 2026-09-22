package it.apexweather.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeelsLikeTest {
    @Test
    fun `cold and colder is shown`() {
        assertEquals(3.0, FeelsLike.shown(8.0, -5.0)!!, 0.0)
    }

    @Test
    fun `hot and hotter is shown`() {
        assertEquals(33.0, FeelsLike.shown(30.0, 3.0)!!, 0.0)
    }

    /** The boundaries are inclusive on both axes, so exactly 10,0 with exactly -2,0 counts. */
    @Test
    fun `boundaries are inclusive`() {
        assertEquals(8.0, FeelsLike.shown(10.0, -2.0)!!, 0.0)
        assertEquals(28.0, FeelsLike.shown(26.0, 2.0)!!, 0.0)
    }

    @Test
    fun `just inside the boundary is not shown`() {
        assertNull(FeelsLike.shown(10.1, -5.0))
        assertNull(FeelsLike.shown(25.9, 5.0))
    }

    @Test
    fun `a separation under the threshold is not shown`() {
        assertNull(FeelsLike.shown(5.0, -1.9))
        assertNull(FeelsLike.shown(30.0, 1.9))
    }

    /**
     * A breeze at 30 degrees is relief, not news, and a humid morning at 2 degrees feeling warmer
     * is not a fact anybody acts on. Each side fires in one direction only.
     */
    @Test
    fun `the wrong sign on either side is not shown`() {
        assertNull(FeelsLike.shown(30.0, -5.0))
        assertNull(FeelsLike.shown(2.0, 5.0))
    }

    @Test
    fun `the mild middle is silent whatever the offset`() {
        assertNull(FeelsLike.shown(18.0, 5.0))
        assertNull(FeelsLike.shown(18.0, -5.0))
    }

    @Test
    fun `nulls in, null out`() {
        assertNull(FeelsLike.shown(null, -5.0))
        assertNull(FeelsLike.shown(2.0, null))
        assertNull(FeelsLike.shown(null, null))
    }
}
