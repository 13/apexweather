package it.apexweather.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

/** [visibleLabelSlots] alone: which labels survive the collision check, and in what order. */
class RibbonLabelSlotsTest {

    @Test
    fun `the priority label survives a collision and the neighbour is dropped`() {
        val kept = visibleLabelSlots(
            centres = listOf(100f, 110f), widths = listOf(40, 40),
            priority = 0, gapPx = 6, totalWidth = 1000,
        )
        assertEquals(listOf(0), kept)
    }

    @Test
    fun `labels far apart all survive`() {
        val kept = visibleLabelSlots(
            centres = listOf(50f, 500f, 950f), widths = listOf(40, 40, 40),
            priority = null, gapPx = 6, totalWidth = 1000,
        )
        assertEquals(listOf(0, 1, 2), kept)
    }

    @Test
    fun `left-to-right skipping keeps the first of two colliding non-priority labels`() {
        val kept = visibleLabelSlots(
            centres = listOf(100f, 110f), widths = listOf(40, 40),
            priority = null, gapPx = 6, totalWidth = 1000,
        )
        assertEquals(listOf(0), kept)
    }

    @Test
    fun `empty input returns empty`() {
        val kept = visibleLabelSlots(
            centres = emptyList(), widths = emptyList(),
            priority = null, gapPx = 6, totalWidth = 1000,
        )
        assertEquals(emptyList<Int>(), kept)
    }
}
