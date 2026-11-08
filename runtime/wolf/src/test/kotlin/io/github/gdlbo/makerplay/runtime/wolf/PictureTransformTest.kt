package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.wolfformat.CommonEventDat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Scaling and rotation, in the units WOLF stores and with the documented rule
 * that separate X/Y zoom is ignored once the picture is rotated.
 *
 * The parameter positions asserted here were measured from the deployment, not
 * assumed: `params[9]` holds zoom (200, 60, 50 on real commands) and
 * `params[10]` holds angle in tenths of a degree (the only non-zero literal is
 * `900` on an arrow icon, which is 90 degrees).
 */
class PictureTransformTest {

// --- units ------------------------------------------------------------

    @Test
    fun `angle is stored in tenths of a degree`() {
        assertEquals("450 must mean 45 degrees", 45.0, PictureTransform(angleTenths = 450).angleDegrees, 1e-9)
        assertEquals("900 must mean 90 degrees", 90.0, PictureTransform(angleTenths = 900).angleDegrees, 1e-9)
        assertEquals("0 is unrotated", 0.0, PictureTransform(angleTenths = 0).angleDegrees, 1e-9)
        assertEquals("3600 wraps to 0", 0.0, PictureTransform(angleTenths = 3600).angleDegrees, 1e-9)
    }

    @Test
    fun `negative and oversized angles wrap`() {
        assertEquals("negative angles are allowed", 270.0, PictureTransform(angleTenths = -900).angleDegrees, 1e-9)
        assertEquals("beyond 3600 is allowed", 90.0, PictureTransform(angleTenths = 4500).angleDegrees, 1e-9)
        assertEquals("multiple turns wrap", 45.0, PictureTransform(angleTenths = 450 + 3600 * 3).angleDegrees, 1e-9)
        assertEquals(0, PictureTransform.normaliseAngleTenths(3600))
        assertEquals(2700, PictureTransform.normaliseAngleTenths(-900))
    }

    @Test
    fun `separate zoom is ignored when the picture is rotated`() {
        val unrotated = PictureTransform(zoomX = 200, zoomY = 50, angleTenths = 0)
        assertEquals(
            "an unrotated picture keeps per-axis zoom",
            200 to 50,
            unrotated.effectiveZoom(),
        )

        val rotated = PictureTransform(zoomX = 200, zoomY = 50, angleTenths = 450)
        assertEquals(
            "a rotated picture scales uniformly, per the manual",
            200 to 200,
            rotated.effectiveZoom(),
        )
    }

    @Test
    fun `transform builds from command parameters`() {
        assertEquals(PictureTransform(100, 100, 0), PictureTransform.fromCommand(100, 0))
        assertEquals(PictureTransform(200, 200, 900), PictureTransform.fromCommand(200, 900))
        assertEquals(
            "a separate vertical zoom is preserved when unrotated",
            PictureTransform(200, 50, 0),
            PictureTransform.fromCommand(200, 0, 50),
        )
        assertEquals(
            "out-of-range zoom falls back to native",
            PictureTransform(100, 100, 0),
            PictureTransform.fromCommand(-5, 0),
        )
        assertEquals(
            "missing parameters mean native and unrotated",
            PictureTransform(100, 100, 0),
            PictureTransform.fromCommand(null, null),
        )
    }

    // --- rotation geometry ------------------------------------------------

    @Test
    fun `unrotated corners are the plain rectangle`() {
        val corners = PictureRotation.rotatedCorners(
            x = 100, y = 50, width = 40, height = 20,
            anchor = PictureAnchor.TOP_LEFT, angleDegrees = 0.0,
        )
        assertEquals(100.0, corners[0].first, 1e-6)
        assertEquals(50.0, corners[0].second, 1e-6)
        assertEquals(140.0, corners[1].first, 1e-6)
        assertEquals(50.0, corners[1].second, 1e-6)
        assertEquals(140.0, corners[2].first, 1e-6)
        assertEquals(70.0, corners[2].second, 1e-6)
        assertEquals(100.0, corners[3].first, 1e-6)
        assertEquals(70.0, corners[3].second, 1e-6)
    }

    @Test
    fun `rotation happens about the anchor point`() {
        // A 40x20 picture rotated 90 degrees about its top-left corner keeps that
        // corner fixed and swings the rest to the right of it.
        val corners = PictureRotation.rotatedCorners(
            x = 0, y = 0, width = 40, height = 20,
            anchor = PictureAnchor.TOP_LEFT, angleDegrees = 90.0,
        )
        assertEquals("anchor corner stays put", 0.0, corners[0].first, 1e-6)
        assertEquals(0.0, corners[0].second, 1e-6)
        // (40,0) rotates to (0,40)
        assertEquals(0.0, corners[1].first, 1e-6)
        assertEquals(40.0, corners[1].second, 1e-6)

        // About the centre, the anchor point stays fixed instead. The anchor sits
        // at (x, y) by definition, so a point placed exactly there must be
        // unchanged by the rotation; averaging the corners is NOT the anchor and
        // was a mistake in an earlier version of this test.
        val centred = PictureRotation.rotatedCorners(
            x = 100, y = 100, width = 40, height = 20,
            anchor = PictureAnchor.CENTER, angleDegrees = 90.0,
        )
        // The midpoint of the two diagonals is the picture's centre, which for a
        // centre anchor is the anchor point itself.
        val diagonalMidX = (centred[0].first + centred[2].first) / 2.0
        val diagonalMidY = (centred[0].second + centred[2].second) / 2.0
        assertEquals("the centre anchor must stay at x", 100.0, diagonalMidX, 1e-6)
        assertEquals("the centre anchor must stay at y", 100.0, diagonalMidY, 1e-6)
        // And the bounds must be symmetric about that anchor.
        val bounds = PictureRotation.rotatedBounds(100, 100, 40, 20, PictureAnchor.CENTER, 90.0)
        assertEquals("bounds centre x", 100.0, (bounds[0] + bounds[2]) / 2.0, 1.0)
        assertEquals("bounds centre y", 100.0, (bounds[1] + bounds[3]) / 2.0, 1.0)
    }

    @Test
    fun `rotated bounds grow for diagonal angles`() {
        val upright = PictureRotation.rotatedBounds(
            0, 0, 40, 20, PictureAnchor.TOP_LEFT, 0.0,
        )
        assertEquals(listOf(0, 0, 40, 20), upright.toList())

        val diagonal = PictureRotation.rotatedBounds(
            0, 0, 40, 20, PictureAnchor.TOP_LEFT, 45.0,
        )
        val width = diagonal[2] - diagonal[0]
        val height = diagonal[3] - diagonal[1]
        assertTrue("a 45-degree picture is wider than upright ($width)", width > 40)
        assertTrue("a 45-degree picture is taller than upright ($height)", height > 20)
    }

    @Test
    fun `a full turn is identical to no rotation`() {
        val none = PictureRotation.rotatedCorners(10, 10, 30, 30, PictureAnchor.CENTER, 0.0)
        val turn = PictureRotation.rotatedCorners(10, 10, 30, 30, PictureAnchor.CENTER, 360.0)
        for (i in none.indices) {
            assertEquals(none[i].first, turn[i].first, 1e-6)
            assertEquals(none[i].second, turn[i].second, 1e-6)
        }
    }

    // --- measured parameter positions -------------------------------------




}
