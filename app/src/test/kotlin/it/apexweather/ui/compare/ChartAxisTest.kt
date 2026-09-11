package it.apexweather.ui.compare

import it.apexweather.ui.common.Formats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ChartAxisTest {
    private val german = Formats(Locale.GERMAN, use24Hour = true)
    private val english = Formats(Locale.UK, use24Hour = true)

    /**
     * The bug this class exists for: a day on which no model forecasts a drop of rain drew an axis
     * from 0 to 2 in four steps and rounded every tick to a whole number, so two gridlines were
     * labelled "0" and the flat line at zero ran between them.
     */
    @Test
    fun `a dry day labels every tick differently`() {
        val axis = ChartAxis.of(List(24) { 0.0 }, nonNegative = true)
        val labels = axis.ticks().map { axis.label(it, german) }
        assertEquals(listOf("0,0", "0,5", "1,0", "1,5", "2,0"), labels)
        assertEquals(labels.size, labels.toSet().size)
    }

    /**
     * A trace of drizzle is not a reason to draw a tenth-of-a-millimetre axis: the step would be
     * finer than the labels can write, and every tick read "0,0" again.
     */
    @Test
    fun `a trace amount gets the same axis as a dry day`() {
        assertEquals(ChartAxis.of(List(4) { 0.0 }, nonNegative = true), ChartAxis.of(listOf(0.0, 0.04), nonNegative = true))
    }

    /** No range may produce two ticks reading the same, whatever the models forecast. */
    @Test
    fun `no axis repeats a label`() {
        val maxima = listOf(0.0, 0.04, 0.2, 0.4, 0.9, 1.0, 1.6, 2.0, 3.0, 7.5, 12.0, 48.0, 130.0)
        for (max in maxima) {
            val axis = ChartAxis.of(listOf(0.0, max), nonNegative = true)
            val labels = axis.ticks().map { axis.label(it, german) }
            assertEquals("ticks repeat for a maximum of $max: $labels", labels.size, labels.toSet().size)
        }
    }

    /** An axis that does not reach the data would cut a line off at the top of the chart. */
    @Test
    fun `the axis holds every value`() {
        val values = listOf(-3.2, 0.0, 4.1, 13.9)
        val axis = ChartAxis.of(values, nonNegative = false)
        assertTrue("$axis excludes ${values.min()}", axis.lo <= values.min())
        assertTrue("$axis excludes ${values.max()}", axis.hi >= values.max())
        val ticks = axis.ticks()
        assertEquals(axis.lo, ticks.first(), 1e-9)
        assertEquals(axis.hi, ticks.last(), 1e-9)
    }

    /** Precipitation and wind cannot go below zero, and an axis that says they can is a lie. */
    @Test
    fun `a non-negative variable starts at zero`() {
        assertEquals(0.0, ChartAxis.of(listOf(0.3, 1.2), nonNegative = true).lo, 1e-9)
        assertTrue(ChartAxis.of(listOf(2.0, 5.0), nonNegative = false).lo < 2.0)
    }

    /** The ticks are numbers on screen, so they are the reader's numbers. */
    @Test
    fun `labels follow the reader's language`() {
        val axis = ChartAxis.of(List(3) { 0.0 }, nonNegative = true)
        assertEquals("0,5", axis.label(0.5, german))
        assertEquals("0.5", axis.label(0.5, english))
    }

    /** Nothing to plot still needs an axis to draw, and it must not be a single flat line. */
    @Test
    fun `an empty series still has a usable axis`() {
        val axis = ChartAxis.of(emptyList(), nonNegative = true)
        assertTrue(axis.hi > axis.lo)
        assertEquals(5, axis.ticks().size)
    }
}
