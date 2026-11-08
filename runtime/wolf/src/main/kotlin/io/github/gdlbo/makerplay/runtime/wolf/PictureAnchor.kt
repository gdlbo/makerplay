package io.github.gdlbo.makerplay.runtime.wolf

/**
 * Where a picture's `(x, y)` sits relative to the image, i.e. which corner or
 * edge of the picture is pinned to that point.
 *
 * The manual (`04ev_picture.html`) documents **seven** choices:
 * 左上 / 左下 / 中心 / 中央上 / 中央下 / 右上 / 右下.
 *
 * Values 0..4 are confirmed two ways: `wolfrpg-map-parser`'s `Anchor` enum names
 * them `TopLeft, Center, BottomLeft, TopRight, BottomRight`, and this
 * deployment's own title menu confirms two of them independently —
 *
 * - the menu rows use nibble `0` and the recorded draw rectangle's top-left is
 *   exactly the command's `(x, y)` (x=157, y=379 as set by CSelf 1600023), so
 *   `0` is top-left; and
 * - the "PUSH ANY KEY" text uses nibble `1` at `(640, 500)` on a 1280x960
 *   screen, i.e. centred horizontally, so `1` is centre.
 *
 * Values **5 and 6 are inferred**, not proven: they are the two names the
 * manual lists that the Rust enum omits (centre-top and centre-bottom), and the
 * nibble histogram over this deployment shows exactly 0..6 in use
 * (`0:574 1:297 2:13 3:51 4:7 5:15 6:3`). The assignment below follows the
 * manual's own listing order after removing the five confirmed entries.
 * Recorded as an open item in docs/wolf-task3-evidence.md rather than presented
 * as verified.
 */
enum class PictureAnchor(val code: Int) {
    TOP_LEFT(0),
    CENTER(1),
    BOTTOM_LEFT(2),
    TOP_RIGHT(3),
    BOTTOM_RIGHT(4),
    /** 中央上 — inferred; see the class note. */
    CENTER_TOP(5),
    /** 中央下 — inferred; see the class note. */
    CENTER_BOTTOM(6),
    ;

    /** Horizontal fraction of the picture that sits left of `x`, 0..1. */
    val xFactor: Double
        get() = when (this) {
            TOP_LEFT, BOTTOM_LEFT -> 0.0
            CENTER, CENTER_TOP, CENTER_BOTTOM -> 0.5
            TOP_RIGHT, BOTTOM_RIGHT -> 1.0
        }

    /** Vertical fraction of the picture that sits above `y`, 0..1. */
    val yFactor: Double
        get() = when (this) {
            TOP_LEFT, TOP_RIGHT, CENTER_TOP -> 0.0
            CENTER, BOTTOM_LEFT, BOTTOM_RIGHT -> 0.5
            CENTER_BOTTOM -> 1.0
        }

    companion object {
        fun fromCode(code: Int): PictureAnchor =
            entries.firstOrNull { it.code == code } ?: TOP_LEFT

        /** True when [code] is one of the documented values. */
        fun isKnown(code: Int): Boolean = entries.any { it.code == code }
    }
}

/**
 * Resolved top-left draw origin for a picture.
 *
 * `x`/`y` are the command's coordinates in screen pixels; [anchor] decides which
 * part of the picture those coordinates refer to. Kept separate from the
 * renderer so the arithmetic is testable without a Canvas.
 */
object PicturePlacement {

    /**
     * Top-left corner at which a [width] x [height] picture must be drawn so that
     * the anchor point lands on `(x, y)`.
     */
    fun topLeft(x: Int, y: Int, width: Int, height: Int, anchor: PictureAnchor): Pair<Int, Int> {
        val left = x - (width * anchor.xFactor).toInt()
        val top = y - (height * anchor.yFactor).toInt()
        return left to top
    }

    /**
     * Applies 相対モード: when the option word marks the position as relative,
     * `x`/`y` are an offset from the picture's current position rather than an
     * absolute screen coordinate.
     */
    fun resolvePosition(
        x: Int,
        y: Int,
        relative: Boolean,
        currentX: Int,
        currentY: Int,
    ): Pair<Int, Int> = if (relative) currentX + x to currentY + y else x to y
}
