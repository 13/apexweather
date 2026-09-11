package it.apexweather.domain

import it.apexweather.domain.ConsensusBlender.Companion.meanDirectionDeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A bearing is the one quantity in this app that cannot be averaged the way the others are, and the
 * failure is not subtle: the arithmetic mean of 350° and 10° is 180°, which is the opposite of the
 * answer.
 */
class CompassTest {

    @Test
    fun `each point covers the 45 degrees centred on its own bearing`() {
        assertEquals(CompassPoint.N, CompassPoint.of(0))
        assertEquals(CompassPoint.N, CompassPoint.of(22))
        assertEquals(CompassPoint.NE, CompassPoint.of(23))
        assertEquals(CompassPoint.E, CompassPoint.of(90))
        assertEquals(CompassPoint.SW, CompassPoint.of(225))
        assertEquals(CompassPoint.NW, CompassPoint.of(315))
        assertEquals(CompassPoint.N, CompassPoint.of(350))
    }

    @Test
    fun `a bearing outside a single turn still names a point`() {
        assertEquals(CompassPoint.E, CompassPoint.of(450))
        assertEquals(CompassPoint.W, CompassPoint.of(-90))
    }

    @Test
    fun `the mean of bearings either side of north is north, not south`() {
        assertEquals(0, meanDirectionDeg(listOf(350, 10)))
        assertEquals(CompassPoint.N, CompassPoint.of(meanDirectionDeg(listOf(350, 354, 6, 10))!!))
    }

    @Test
    fun `models pointing the same way keep that direction`() {
        assertEquals(315, meanDirectionDeg(listOf(310, 315, 320)))
    }

    /** Two halves of the valley cancel, and the honest answer is nothing rather than a midpoint. */
    @Test
    fun `models pointing opposite ways have no mean worth printing`() {
        assertNull(meanDirectionDeg(listOf(0, 180)))
        assertNull(meanDirectionDeg(listOf(0, 90, 180, 270)))
    }

    @Test
    fun `no model publishing a direction means no direction`() {
        assertNull(meanDirectionDeg(emptyList()))
    }

    /** A quarter-circle of disagreement is still a direction; a half-circle is not. */
    @Test
    fun `the agreement floor sits between a quarter-circle and a half-circle of spread`() {
        assertEquals(315, meanDirectionDeg(listOf(270, 315, 360)))
        assertNull(meanDirectionDeg(listOf(180, 315, 90)))
    }
}
