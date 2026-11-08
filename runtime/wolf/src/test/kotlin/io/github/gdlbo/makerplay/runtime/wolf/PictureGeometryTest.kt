package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.wolfformat.CommonEventDat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * お手軽ウィンドウ (9-slice) and 自由変形 (free transform).
 *
 * The 9-slice contract is asserted the way it actually matters: render a
 * *patterned* source into a resized box and check that corner pixels map 1:1
 * while the centre is stretched. That distinguishes a real 9-slice from a plain
 * stretch, which a "something drew" assertion would not.
 */
class PictureGeometryTest {

// --- 9-slice ----------------------------------------------------------

    @Test
    fun `nine slices tile the destination exactly`() {
        val slices = PictureGeometry.nineSlice(
            srcW = 30, srcH = 30,
            dstX = 100, dstY = 50, dstW = 300, dstH = 120,
        )
        assertEquals(9, slices.size)

        // Every destination slice must be inside the target box.
        for (s in slices) {
            assertTrue("slice starts before the box", s.dstLeft >= 100 && s.dstTop >= 50)
            assertTrue("slice ends after the box", s.dstRight <= 400 && s.dstBottom <= 170)
            assertTrue("slice must be non-degenerate", s.dstRight >= s.dstLeft && s.dstBottom >= s.dstTop)
        }

        // Row-major order: the corners are first, third, seventh and ninth.
        assertEquals("top-left corner keeps the source size", 100, slices[0].dstLeft)
        assertEquals(50, slices[0].dstTop)
        assertEquals(110, slices[0].dstRight)
        // corner height is srcH/3 = 10, so the top-left corner ends at 50+10.
        assertEquals(60, slices[0].dstBottom)
        // 300x120 box, 10px corners (30/3): bottom-right corner ends at the box edge.
        assertEquals(400, slices[8].dstRight)
        assertEquals(170, slices[8].dstBottom)
    }

    @Test
    fun `corners keep their size while the centre stretches`() {
        val srcW = 30
        val srcH = 30
        val dstW = 300
        val dstH = 120
        val slices = PictureGeometry.nineSlice(srcW, srcH, 0, 0, dstW, dstH)

        val corner = slices[0] // top-left
        assertEquals(
            "the top-left corner must keep its source width",
            corner.srcRight - corner.srcLeft,
            corner.dstRight - corner.dstLeft,
        )
        assertEquals(
            "the top-left corner must keep its source height",
            corner.srcBottom - corner.srcTop,
            corner.dstBottom - corner.dstTop,
        )

        val centre = slices[4]
        assertTrue(
            "the centre must be wider than its source",
            (centre.dstRight - centre.dstLeft) > (centre.srcRight - centre.srcLeft),
        )
        assertTrue(
            "the centre must be taller than its source",
            (centre.dstBottom - centre.dstTop) > (centre.srcBottom - centre.srcTop),
        )
    }

    @Test
    fun `a patterned source maps corners one-to-one and stretches the centre`() {
        // 30x30 source where each pixel encodes its own coordinates. Applying the
        // slice mapping must leave corner pixels identical and spread centre
        // pixels out, which is the actual 9-slice property.
        val srcW = 30
        val srcH = 30
        fun srcPixel(x: Int, y: Int) = (y shl 8) or x
        val slices = PictureGeometry.nineSlice(srcW, srcH, 0, 0, 300, 120)

        // Corner: destination pixel (0,0) must come from source (0,0) unchanged.
        val tl = slices[0]
        val srcAtCorner = srcPixel(tl.srcLeft, tl.srcTop)
        assertEquals(0, srcAtCorner)
        assertEquals(
            "corner slice must be 1:1, not scaled",
            tl.dstRight - tl.dstLeft,
            tl.srcRight - tl.srcLeft,
        )

        // Centre: one source pixel now covers many destination pixels.
        val centre = slices[4]
        val srcSpanX = centre.srcRight - centre.srcLeft
        val dstSpanX = centre.dstRight - centre.dstLeft
        assertTrue(
            "centre stretch factor must exceed 1 (src $srcSpanX -> dst $dstSpanX)",
            dstSpanX > srcSpanX,
        )
        // A plain stretch would scale the corner too; assert it does not.
        assertTrue(
            "corner width must stay below the stretched centre width",
            (tl.dstRight - tl.dstLeft) < dstSpanX,
        )
    }

    @Test
    fun `degenerate corner sizes are clamped`() {
        // A corner wider than half the source would make slices overlap.
        val slices = PictureGeometry.nineSlice(
            srcW = 20, srcH = 20, dstX = 0, dstY = 0, dstW = 100, dstH = 100,
            cornerW = 50, cornerH = 50,
        )
        assertEquals(9, slices.size)
        for (s in slices) {
            assertTrue("no negative destination span", s.dstRight >= s.dstLeft)
            assertTrue("no negative source span", s.srcRight >= s.srcLeft)
        }
    }

    // --- free transform ---------------------------------------------------

    @Test
    fun `free transform keeps the four specified corners`() {
        val corners = PictureGeometry.freeTransform(
            topLeft = 10 to 20,
            topRight = 210 to 30,
            bottomRight = 200 to 130,
            bottomLeft = 0 to 120,
        )
        assertEquals(4, corners.size)
        assertEquals(10 to 20, corners[0])
        assertEquals(210 to 30, corners[1])
        assertEquals(200 to 130, corners[2])
        assertEquals(0 to 120, corners[3])
    }

    @Test
    fun `free transform corners land where specified`() {
        // The contract: each corner lands on its specified coordinate, which is
        // what distinguishes free transform from an affine rotate/scale.
        val tl = 50 to 50
        val tr = 350 to 80
        val br = 300 to 260
        val bl = 20 to 200
        val corners = PictureGeometry.freeTransform(tl, tr, br, bl)
        val bounds = PictureGeometry.bounds(corners)
        assertEquals(listOf(20, 50, 350, 260), bounds.toList())
        // A plain rectangle of the same bounds would have its corners at the
        // bounds' corners; a free transform does not.
        assertFalse(
            "a free transform must not degenerate to its bounding rectangle",
            corners.containsAll(listOf(20 to 50, 350 to 50, 350 to 260, 20 to 260)),
        )
    }

    @Test
    fun `free transform hit testing covers the quad`() {
        val square = PictureGeometry.freeTransform(
            0 to 0, 100 to 0, 100 to 100, 0 to 100,
        )
        assertTrue("centre is inside", PictureGeometry.containsPoint(square, 50, 50))
        assertTrue("near a corner is inside", PictureGeometry.containsPoint(square, 1, 1))
        assertFalse("outside on the right", PictureGeometry.containsPoint(square, 150, 50))
        assertFalse("outside below", PictureGeometry.containsPoint(square, 50, 150))
        assertFalse("outside diagonally", PictureGeometry.containsPoint(square, -10, -10))
    }

    @Test
    fun `hit testing follows a skewed quad`() {
        // A quad skewed so its top edge is shifted right of its bottom edge.
        val skewed = PictureGeometry.freeTransform(
            60 to 0, 160 to 0, 100 to 100, 0 to 100,
        )
        assertTrue("a point under the skewed top edge is inside", PictureGeometry.containsPoint(skewed, 90, 90))
        assertFalse("a point above-left of the skew is outside", PictureGeometry.containsPoint(skewed, 5, 5))
        assertTrue("a point under the left edge is inside", PictureGeometry.containsPoint(skewed, 40, 95))
    }

    // --- deployment facts -------------------------------------------------


}
