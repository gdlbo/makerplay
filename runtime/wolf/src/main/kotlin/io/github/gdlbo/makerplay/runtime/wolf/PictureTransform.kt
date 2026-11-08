package io.github.gdlbo.makerplay.runtime.wolf

/**
 * Picture scaling and rotation, in the units WOLF actually stores.
 *
 * Units, from the manual (`04ev_picture.html`) and confirmed against this
 * deployment's own commands:
 *
 * - **Zoom** is a percentage, 100 = native size.
 * - **Angle** is in tenths of a degree: 45 degrees is stored as `450`, so
 *   dividing by 10 gives degrees. Negative values and values beyond 3600 are
 *   explicitly allowed and simply wrap.
 *
 * Where the parameters live, measured over `CommonEvent.dat`:
 *
 * - `params[9]` is zoom: the literal values are 200, 60, 50, 100 … on real
 *   picture commands (`Picture/frameeffects003m.png`, `SystemFile/Text_Pause.png`).
 * - `params[10]` is angle: the only non-zero literals in the deployment are
 *   `900` (90.0°) on `Picture/btl_mnw1_arrow.png`, which is an arrow icon that
 *   would be meaningless unrotated.
 * - **Correction:** an earlier version of this comment claimed `params[15..17]`
 *   were separate X/Y zoom. They are not. Measured over the 18-parameter form,
 *   those three values are bounded 0..200 and vary independently — the observed
 *   triples include `(200,200,200)`, `(200,0,0)` and `(200,150,150)` — which is
 *   the per-channel R/G/B colour correction, matching the manual's ±200 range.
 *   Separate zoom is not used by this deployment, so only the single zoom
 *   percentage is populated from commands today.
 *
 * The documented rule that separate X/Y zoom is ignored when the angle is
 * non-zero is implemented in [effectiveZoom].
 */
data class PictureTransform(
    /** Horizontal zoom percent, 100 = native. */
    val zoomX: Int = 100,
    /** Vertical zoom percent, 100 = native. */
    val zoomY: Int = 100,
    /** Rotation in tenths of a degree; 450 means 45 degrees. */
    val angleTenths: Int = 0,
) {
    /** Rotation in degrees, normalised to [0, 360). */
    val angleDegrees: Double
        get() {
            val wrapped = ((angleTenths % ANGLE_TENTHS_PER_TURN) + ANGLE_TENTHS_PER_TURN) %
                ANGLE_TENTHS_PER_TURN
            return wrapped / 10.0
        }

    /** True when the picture is rotated at all. */
    val isRotated: Boolean get() = normaliseAngleTenths(angleTenths) != 0

    /**
     * The zoom actually applied to each axis.
     *
     * The manual states that when the angle is not 0 the "separate" setting is
     * disabled, so a rotated picture scales uniformly. The uniform value used is
     * the horizontal one, which is also what the engine's own single-zoom
     * commands imply.
     */
    fun effectiveZoom(): Pair<Int, Int> =
        if (isRotated) zoomX to zoomX else zoomX to zoomY

    companion object {
        const val ANGLE_TENTHS_PER_TURN = 3600

        /** Normalises a tenths-of-a-degree value into [0, 3600). */
        fun normaliseAngleTenths(raw: Int): Int =
            ((raw % ANGLE_TENTHS_PER_TURN) + ANGLE_TENTHS_PER_TURN) % ANGLE_TENTHS_PER_TURN

        /** Builds a transform from the command's zoom and angle parameters. */
        fun fromCommand(zoomPercent: Int?, angleTenths: Int?, zoomYPercent: Int? = null): PictureTransform {
            val zx = (zoomPercent ?: 100).let { if (it in MIN_ZOOM..MAX_ZOOM) it else 100 }
            val zy = (zoomYPercent ?: zx).let { if (it in MIN_ZOOM..MAX_ZOOM) it else zx }
            return PictureTransform(zoomX = zx, zoomY = zy, angleTenths = angleTenths ?: 0)
        }

        const val MIN_ZOOM = 0
        const val MAX_ZOOM = 1000
    }
}

/**
 * Geometry for drawing a rotated, scaled picture.
 *
 * Rotation is about the anchor point, which is what the manual implies: the
 * anchor decides where the picture sits, and zoom/angle are then computed about
 * it. Kept free of Canvas so the arithmetic is unit-testable.
 */
object PictureRotation {

    /**
     * The four corners of a [width] x [height] picture rotated by [angleDegrees]
     * about the anchor point that sits at `(x, y)`.
     *
     * Returned in draw order: top-left, top-right, bottom-right, bottom-left.
     */
    fun rotatedCorners(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        anchor: PictureAnchor,
        angleDegrees: Double,
    ): List<Pair<Double, Double>> {
        // The anchor point in the picture's own space.
        val ax = width * anchor.xFactor
        val ay = height * anchor.yFactor
        val radians = Math.toRadians(angleDegrees)
        val cos = Math.cos(radians)
        val sin = Math.sin(radians)

        // Each corner is expressed relative to the anchor, rotated, then
        // translated so the anchor itself lands exactly on (x, y). Note the
        // translation is just (x, y): adding ax/ay back would double-count the
        // anchor and shift every rotated picture by half its size.
        val local = listOf(
            0.0 to 0.0,
            width.toDouble() to 0.0,
            width.toDouble() to height.toDouble(),
            0.0 to height.toDouble(),
        )
        return local.map { (lx, ly) ->
            val dx = lx - ax
            val dy = ly - ay
            val rx = dx * cos - dy * sin
            val ry = dx * sin + dy * cos
            (x + rx) to (y + ry)
        }
    }

    /** Axis-aligned bounds of a rotated picture, for culling and hit testing. */
    fun rotatedBounds(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        anchor: PictureAnchor,
        angleDegrees: Double,
    ): IntArray {
        val corners = rotatedCorners(x, y, width, height, anchor, angleDegrees)
        val minX = corners.minOf { it.first }
        val minY = corners.minOf { it.second }
        val maxX = corners.maxOf { it.first }
        val maxY = corners.maxOf { it.second }
        return intArrayOf(
            kotlin.math.floor(minX).toInt(),
            kotlin.math.floor(minY).toInt(),
            kotlin.math.ceil(maxX).toInt(),
            kotlin.math.ceil(maxY).toInt(),
        )
    }
}
