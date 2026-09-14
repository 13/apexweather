package it.apexweather.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `bitmapLayout` is what stands between a cell's screen pixel and its slot in the small bitmap
 * [NowcastOverlay] paints and scales. The one thing that has to be true of it, always, is that a
 * cell never lands in the border: `NowcastOverlay.draw` counts on a transparent ring one cell wide
 * on every side to be what the bilinear filter fades into, and a placed cell sharing that ring's
 * pixel would print a hard edge exactly where the softening was supposed to be.
 */
class BitmapLayoutTest {

    @Test
    fun `a single point gives a 3x3 layout and sits dead centre, not in a border cell`() {
        val layout = bitmapLayout(listOf(100f), listOf(100f), side = 20f)
        assertEquals(3, layout.cols)
        assertEquals(3, layout.rows)
        assertEquals(1, layout.column(100f))
        assertEquals(1, layout.row(100f))
    }

    @Test
    fun `every point lands strictly inside, never in the first or last column or row`() {
        val side = 15f
        val xs = listOf(50f, 65f, 80f, 95f, 110f)
        val ys = listOf(200f, 215f, 230f, 245f)
        val layout = bitmapLayout(xs, ys, side)

        xs.forEach { x ->
            val col = layout.column(x)
            assertTrue("column $col out of [1, ${layout.cols - 2}] for x=$x", col in 1..(layout.cols - 2))
        }
        ys.forEach { y ->
            val row = layout.row(y)
            assertTrue("row $row out of [1, ${layout.rows - 2}] for y=$y", row in 1..(layout.rows - 2))
        }
    }

    /** Same check with points that are not grid-aligned, so the property isn't an artefact of tidy spacing. */
    @Test
    fun `unevenly spaced points still land strictly inside`() {
        val side = 12f
        val xs = listOf(1000f, 1003.7f, 1031f, 1040.2f, 1071.9f)
        val ys = listOf(-40f, -37.5f, -12f, -0.4f)
        val layout = bitmapLayout(xs, ys, side)

        xs.forEach { x -> assertTrue(layout.column(x) in 1..(layout.cols - 2)) }
        ys.forEach { y -> assertTrue(layout.row(y) in 1..(layout.rows - 2)) }
    }

    /** Points sharing one row must not collapse the row count below what a border on both sides needs. */
    @Test
    fun `points on one row give three rows`() {
        val side = 10f
        val xs = listOf(0f, 10f, 20f, 30f)
        val ys = listOf(500f, 500f, 500f, 500f)
        val layout = bitmapLayout(xs, ys, side)
        assertEquals(3, layout.rows)
        assertEquals(1, layout.row(500f))
    }

    /** Points sharing one column, the transposed case. */
    @Test
    fun `points on one column give three columns`() {
        val side = 10f
        val xs = listOf(80f, 80f, 80f)
        val ys = listOf(0f, 10f, 20f)
        val layout = bitmapLayout(xs, ys, side)
        assertEquals(3, layout.cols)
        assertEquals(1, layout.column(80f))
    }
}
