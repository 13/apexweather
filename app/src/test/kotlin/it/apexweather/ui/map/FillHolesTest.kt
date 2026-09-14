package it.apexweather.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `fillHoles` is what closes the dimples a rotated native grid leaves in an axis-aligned raster:
 * a bitmap pixel nothing landed on, sitting inside territory that is otherwise filled. The cases
 * here pin the one distinction that matters — "obviously surrounded" (at least three of the up to
 * four orthogonal neighbours already coloured) gets filled with their average, anything less
 * stays transparent, so a real edge or a thin outline is never smeared into something it isn't.
 */
class FillHolesTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `a transparent pixel with all four orthogonal neighbours filled becomes their average`() {
        val top = argb(255, 100, 0, 0)
        val bottom = argb(255, 0, 100, 0)
        val left = argb(255, 0, 0, 100)
        val right = argb(255, 40, 40, 40)
        // 3x3, row-major: centre (index 4) is transparent with all four orthogonal neighbours set.
        val pixels = intArrayOf(
            0, top, 0,
            left, 0, right,
            0, bottom, 0,
        )

        val filled = fillHoles(pixels, cols = 3, rows = 3)

        assertEquals(1, filled)
        // A: (255+255+255+255)/4 = 255. R: (100+0+0+40)/4 = 35. G: (0+100+0+40)/4 = 35. B: (0+0+100+40)/4 = 35.
        assertEquals(argb(255, 35, 35, 35), pixels[4])
    }

    @Test
    fun `a transparent pixel with only two filled neighbours stays transparent`() {
        val colour = argb(255, 10, 20, 30)
        // Centre's orthogonal neighbours are top and bottom only (left and right stay transparent).
        val pixels = intArrayOf(
            0, colour, 0,
            0, 0, 0,
            0, colour, 0,
        )

        val filled = fillHoles(pixels, cols = 3, rows = 3)

        assertEquals(0, filled)
        assertEquals(0, pixels[4])
    }

    @Test
    fun `the transparent border around one filled centre stays transparent`() {
        val centre = argb(255, 5, 6, 7)
        val pixels = intArrayOf(
            0, 0, 0,
            0, centre, 0,
            0, 0, 0,
        )

        val filled = fillHoles(pixels, cols = 3, rows = 3)

        assertEquals(0, filled)
        pixels.forEachIndexed { i, value -> if (i != 4) assertEquals("border pixel $i", 0, value) }
        assertEquals(centre, pixels[4])
    }

    @Test
    fun `the return value counts every pixel filled, not only whether any were`() {
        fun grey(v: Int) = argb(255, v, v, v)
        val c00 = grey(5); val c01 = grey(10); val c02 = grey(99); val c03 = grey(50); val c04 = grey(7)
        val c10 = grey(30); val c12 = grey(40); val c14 = grey(70)
        val c20 = grey(9); val c21 = grey(20); val c22 = grey(11); val c23 = grey(60); val c24 = grey(13)
        // 5x3, row-major: two holes, at (row 1, col 1) and (row 1, col 3), each with four filled
        // orthogonal neighbours; nothing else in the grid is transparent.
        val pixels = intArrayOf(
            c00, c01, c02, c03, c04,
            c10, 0, c12, 0, c14,
            c20, c21, c22, c23, c24,
        )

        val filled = fillHoles(pixels, cols = 5, rows = 3)

        assertEquals(2, filled)
        assertEquals(grey((10 + 20 + 30 + 40) / 4), pixels[6])
        assertEquals(grey((50 + 60 + 40 + 70) / 4), pixels[8])
    }
}
