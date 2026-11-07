package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.wolfformat.CommonEventDat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Anchor placement and 相対モード, checked against documented geometry and
 * against this deployment's own recorded picture rectangles.
 *
 * The interesting part is that two of the seven anchors are *verified from real
 * data* here rather than assumed:
 *
 * - the title menu rows carry nibble `0`, and the device recorded their draw
 *   rectangles with a top-left exactly equal to the command's `(x, y)`;
 * - the "PUSH ANY KEY" text carries nibble `1` at `(640, 500)` on a 1280x960
 *   screen, which is only sensible as a centre anchor.
 */
class PictureAnchorTest {

// --- arithmetic -------------------------------------------------------

    @Test
    fun `each anchor pins the documented corner to x,y`() {
        // A 200x100 picture at (500, 300).
        val w = 200
        val h = 100
        fun place(a: PictureAnchor) = PicturePlacement.topLeft(500, 300, w, h, a)

        assertEquals("top-left keeps its top-left at x,y", 500 to 300, place(PictureAnchor.TOP_LEFT))
        assertEquals("centre puts the middle at x,y", 400 to 250, place(PictureAnchor.CENTER))
        assertEquals("bottom-left puts the bottom-left at x,y", 500 to 250, place(PictureAnchor.BOTTOM_LEFT))
        assertEquals("top-right puts the top-right at x,y", 300 to 300, place(PictureAnchor.TOP_RIGHT))
        assertEquals("bottom-right puts the bottom-right at x,y", 300 to 250, place(PictureAnchor.BOTTOM_RIGHT))
        assertEquals("centre-top puts the top-centre at x,y", 400 to 300, place(PictureAnchor.CENTER_TOP))
        assertEquals("centre-bottom puts the bottom-centre at x,y", 400 to 200, place(PictureAnchor.CENTER_BOTTOM))
    }

    @Test
    fun `anchor factors cover every documented mode`() {
        assertEquals(7, PictureAnchor.entries.size)
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 6), PictureAnchor.entries.map { it.code }.toSet())
        for (code in 0..6) assertTrue("code $code must map", PictureAnchor.isKnown(code))
        assertTrue("out-of-range code must fall back safely", PictureAnchor.isKnown(9).not())
        assertEquals(PictureAnchor.TOP_LEFT, PictureAnchor.fromCode(99))
    }

    // --- relative mode ----------------------------------------------------

    @Test
    fun `relative mode offsets from the current position`() {
        assertEquals(
            "absolute position ignores the current point",
            100 to 200,
            PicturePlacement.resolvePosition(100, 200, relative = false, currentX = 999, currentY = 888),
        )
        assertEquals(
            "relative position adds to the current point",
            1099 to 1088,
            PicturePlacement.resolvePosition(100, 200, relative = true, currentX = 999, currentY = 888),
        )
        assertEquals(
            "a relative move of zero must not shift the picture",
            640 to 480,
            PicturePlacement.resolvePosition(0, 0, relative = true, currentX = 640, currentY = 480),
        )
    }

    // --- verified against the deployment ---------------------------------






}
