package io.github.gdlbo.makerplay.runtime.wolf

/**
 * お手軽ウィンドウ (easy window) — a 9-slice stretch — and 自由変形 (free
 * transform), the two non-rectangular picture forms the manual documents.
 *
 * **お手軽ウィンドウ.** The manual describes it as a three-part split: the four
 * corners keep their scale while the edges and centre stretch to fill the target
 * box. That is the classic 9-slice. The corner size is taken from the source
 * cell's own corner region, so a 30x30 source used to draw a 300x120 box keeps
 * 30x30 corners and stretches the middle.
 *
 * **自由変形.** The manual lets all four corners be positioned independently,
 * which is a general quadrilateral map rather than an affine one. [freeTransform]
 * therefore produces the four destination points and leaves the rasterisation to
 * the caller; [containsPoint] answers hit testing for the resulting quad.
 *
 * Both are pure geometry so they can be asserted without a Canvas.
 */
object PictureGeometry {

    /** One 9-slice source/destination pair: which source region maps to which box. */
    data class Slice(
        val srcLeft: Int,
        val srcTop: Int,
        val srcRight: Int,
        val srcBottom: Int,
        val dstLeft: Int,
        val dstTop: Int,
        val dstRight: Int,
        val dstBottom: Int,
    )

    /**
     * Splits a [srcW] x [srcH] source into nine slices that fill a
     * [dstW] x [dstH] box whose top-left is at ([dstX], [dstY]).
     *
     * [cornerW] / [cornerH] default to the source corner size, which is what the
     * manual's "corners at 1x" behaviour means. The corner slices keep their
     * source dimensions; the edge and centre slices stretch.
     *
     * The result is ordered row-major (top-left, top, top-right, left, centre,
     * right, bottom-left, bottom, bottom-right) so callers can iterate it
     * directly.
     */
    fun nineSlice(
        srcW: Int,
        srcH: Int,
        dstX: Int,
        dstY: Int,
        dstW: Int,
        dstH: Int,
        cornerW: Int = srcW / 3,
        cornerH: Int = srcH / 3,
    ): List<Slice> {
        require(srcW > 0 && srcH > 0) { "source must be non-empty" }
        // Corners may not exceed half the source or the slices would overlap.
        val cw = cornerW.coerceIn(0, srcW / 2)
        val ch = cornerH.coerceIn(0, srcH / 2)
        // Destination corners keep the source corner size (the manual's "equal
        // scale"), so they are clamped to the target box as well.
        val dcw = cw.coerceAtMost(dstW / 2)
        val dch = ch.coerceAtMost(dstH / 2)

        val srcXs = intArrayOf(0, cw, srcW - cw, srcW)
        val srcYs = intArrayOf(0, ch, srcH - ch, srcH)
        val dstXs = intArrayOf(dstX, dstX + dcw, dstX + dstW - dcw, dstX + dstW)
        val dstYs = intArrayOf(dstY, dstY + dch, dstY + dstH - dch, dstY + dstH)

        val out = ArrayList<Slice>(9)
        for (row in 0..2) {
            for (col in 0..2) {
                out.add(
                    Slice(
                        srcLeft = srcXs[col],
                        srcTop = srcYs[row],
                        srcRight = srcXs[col + 1],
                        srcBottom = srcYs[row + 1],
                        dstLeft = dstXs[col],
                        dstTop = dstYs[row],
                        dstRight = dstXs[col + 1],
                        dstBottom = dstYs[row + 1],
                    ),
                )
            }
        }
        return out
    }

    /**
     * 自由変形: the four destination corners of a free-transform picture.
     *
     * The manual exposes four corner coordinates. They are accepted as
     * `(topLeft, topRight, bottomRight, bottomLeft)` in that order, matching the
     * manual's 左上・右上・左下・右下 listing and the draw order used elsewhere in
     * this renderer.
     */
    fun freeTransform(
        topLeft: Pair<Int, Int>,
        topRight: Pair<Int, Int>,
        bottomRight: Pair<Int, Int>,
        bottomLeft: Pair<Int, Int>,
    ): List<Pair<Int, Int>> = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /**
     * True when `(x, y)` lies inside the quadrilateral formed by [corners].
     *
     * Uses the winding/inside test: the point is inside when it is on the same
     * side of every edge. Works for convex quads, which is what a picture
     * transform produces in practice.
     */
    fun containsPoint(corners: List<Pair<Int, Int>>, x: Int, y: Int): Boolean {
        if (corners.size != 4) return false
        var positive = false
        var negative = false
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            val cross = (b.first - a.first).toLong() * (y - a.second) -
                (b.second - a.second).toLong() * (x - a.first)
            if (cross > 0) positive = true
            if (cross < 0) negative = true
            if (positive && negative) return false
        }
        return true
    }

    /** Axis-aligned bounds of a quadrilateral, for culling. */
    fun bounds(corners: List<Pair<Int, Int>>): IntArray {
        if (corners.isEmpty()) return intArrayOf(0, 0, 0, 0)
        return intArrayOf(
            corners.minOf { it.first },
            corners.minOf { it.second },
            corners.maxOf { it.first },
            corners.maxOf { it.second },
        )
    }
}
