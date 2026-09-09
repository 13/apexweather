package it.apexweather.ui.compare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class ChartGeometryTest {
    private val from: Instant = Instant.parse("2026-09-09T00:00:00Z")
    private fun geometry(hours: Long = 24, left: Float = 40f, right: Float = 340f) =
        ChartGeometry(left = left, right = right, top = 10f, bottom = 210f, from = from, hours = hours, lo = 0.0, hi = 20.0)

    @Test fun `the window fills the plot from edge to edge`() {
        val g = geometry()
        assertEquals(40f, g.x(from), 0.01f)
        assertEquals(340f, g.x(g.until), 0.01f)
        assertEquals(190f, g.x(from.plus(12, ChronoUnit.HOURS)), 0.01f)
    }

    @Test fun `the value range fills the plot from bottom to top`() {
        val g = geometry()
        assertEquals(210f, g.y(0.0), 0.01f)
        assertEquals(10f, g.y(20.0), 0.01f)
        assertEquals(110f, g.y(10.0), 0.01f)
    }

    /** The round trip is what makes a touch mean an hour. */
    @Test fun `every hour in the window maps back to itself`() {
        val g = geometry()
        (0..24).forEach { h ->
            val t = from.plus(h.toLong(), ChronoUnit.HOURS)
            assertEquals("hour $h", t, g.hourAt(g.x(t)))
        }
    }

    @Test fun `a touch between two hours snaps to the nearer one`() {
        val g = geometry()
        val oneOClock = from.plus(1, ChronoUnit.HOURS)
        val quarterPast = g.x(from.plus(75, ChronoUnit.MINUTES))
        assertEquals(oneOClock, g.hourAt(quarterPast))
        assertEquals(from.plus(2, ChronoUnit.HOURS), g.hourAt(g.x(from.plus(105, ChronoUnit.MINUTES))))
    }

    @Test fun `a touch outside the plot is clamped to the window`() {
        val g = geometry()
        assertEquals(from, g.hourAt(-500f))
        assertEquals(g.until, g.hourAt(9_000f))
    }

    @Test fun `an instant outside the window is not contained`() {
        val g = geometry()
        assertTrue(g.contains(from))
        assertTrue(g.contains(g.until))
        assertFalse(g.contains(from.minusSeconds(1)))
        assertFalse(g.contains(g.until.plusSeconds(1)))
    }

    /** A zero-width canvas and a one-hour window must not divide by zero. */
    @Test fun `degenerate sizes do not produce infinities or crashes`() {
        val flat = ChartGeometry(left = 20f, right = 20f, top = 5f, bottom = 5f, from = from, hours = 0, lo = 3.0, hi = 3.0)
        assertTrue(flat.x(from).isFinite())
        assertTrue(flat.y(3.0).isFinite())
        assertEquals(from, flat.hourAt(100f))
    }

    /** Twelve labels across a single day would crowd it; three across three days would not say enough. */
    @Test fun `the tick interval follows the span`() {
        assertEquals(3L, geometry(hours = 24).tickHours)
        assertEquals(3L, geometry(hours = 25).tickHours)
        assertEquals(12L, geometry(hours = 72).tickHours)
    }
}
