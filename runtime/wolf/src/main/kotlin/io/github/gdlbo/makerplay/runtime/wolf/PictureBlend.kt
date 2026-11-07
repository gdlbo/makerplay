package io.github.gdlbo.makerplay.runtime.wolf

/**
 * Picture blend modes and per-picture colour correction.
 *
 * Modes and values come from `wolfrpg-map-parser`'s `BlendingMethod` enum, which
 * agrees with the manual's four documented modes:
 *
 * ```
 * Normal = 0x00, Add = 0x01, Subtract = 0x02, Multiply = 0x03, Same = 0x0F
 * ```
 *
 * All four occur in this deployment, so none of them is dead code — the
 * histogram over literal picture option words is
 * `0:849  1:18  2:4  3:7  15:82`.
 *
 * The blend arithmetic lives here as pure functions over ARGB ints so it can be
 * asserted exactly, pixel by pixel, instead of only being "visible on screen".
 */
object PictureBlend {

    const val NORMAL = 0
    const val ADD = 1
    const val SUBTRACT = 2
    const val MULTIPLY = 3
    const val SAME = 0x0F

    /**
     * Observed range for the per-channel colour correction parameters.
     *
     * The engine's own encoding is `(v - 100) * 2.55` on a percent scale (see
     * [engineColourChannel]), so the useful domain is 0..200 around a neutral 100.
     */
    const val COLOUR_CORRECTION_MIN = -200
    const val COLOUR_CORRECTION_MAX = 200

    /** Neutral value in the engine's percent scale. */
    const val COLOUR_NEUTRAL = 100

    /**
     * Largest delta the engine's own endpoints produce.
     *
     * `(200 - 100) * 2.55` is 254.999... in IEEE754 and the engine truncates with
     * `(int)`, so the practical maximum is 254, not 255.
     */
    const val COLOUR_DELTA_MAX = 254

    /**
     * Blend [src] over [dst].
     *
     * Channel math is applied to straight (non-premultiplied) colour, then the
     * result is clamped to `0..255` per channel. [SAME] means "keep whatever the
     * previous mode was", so the caller passes the already-resolved mode; if it
     * reaches here it behaves as [NORMAL].
     */
    fun blend(dst: Int, src: Int, mode: Int): Int {
        val srcAlpha = (src ushr 24) and 0xFF
        if (srcAlpha == 0) return dst
        val dstAlpha = (dst ushr 24) and 0xFF

        val sr = (src ushr 16) and 0xFF
        val sg = (src ushr 8) and 0xFF
        val sb = src and 0xFF
        val dr = (dst ushr 16) and 0xFF
        val dg = (dst ushr 8) and 0xFF
        val db = dst and 0xFF

        val blended = when (mode) {
            ADD -> Triple(clamp(dr + sr), clamp(dg + sg), clamp(db + sb))
            SUBTRACT -> Triple(clamp(dr - sr), clamp(dg - sg), clamp(db - sb))
            MULTIPLY -> Triple(
                (dr * sr) / 255,
                (dg * sg) / 255,
                (db * sb) / 255,
            )
            else -> Triple(sr, sg, sb)
        }

        // The blended colour is then composited over the destination by source
        // alpha, so a half-transparent additive picture only half-adds.
        if (srcAlpha == 255) {
            return (0xFF shl 24) or (blended.first shl 16) or (blended.second shl 8) or blended.third
        }
        val a = srcAlpha
        val inv = 255 - a
        val r = (blended.first * a + dr * inv) / 255
        val g = (blended.second * a + dg * inv) / 255
        val b = (blended.third * a + db * inv) / 255
        val outAlpha = (a + dstAlpha * inv / 255).coerceIn(0, 255)
        return (outAlpha shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
    }

    /**
     * One engine colour channel: `(value - 100) * 2.55`, clamped to `0..255`.
     *
     * Decompiled from `sub_990570`:
     * ```
     * v14[144] = (int)((double)(a12 / 0x10000       - 100) * 2.55);
     * v14[145] = (int)((double)(v135 % 0x10000 / 256 - 100) * 2.55);
     * v14[146] = (int)((double)(v135 % 256          - 100) * 2.55);
     * ```
     * So the packed word carries three bytes each offset by 100, and the renderer
     * adds the result to the channel. 100 is neutral.
     *
     * The result is a **signed delta** in `-255..255`, not a clamped channel: it is
     * added to the source channel and clamped there, so a value below 100 must be
     * allowed to go negative.
     */
    fun engineColourChannel(value: Int): Int =
        // Truncation toward zero, matching C's `(int)` cast -- NOT floor. The
        // difference is visible at the endpoints: floor(-254.999...) is -255 while
        // the cast gives -254, which is what the engine actually stores.
        // Bound to the range the engine's own endpoints produce (254/-254), so an
        // out-of-range input cannot exceed what a legal 0 or 200 yields.
        ((value - COLOUR_NEUTRAL) * 2.55).toInt().coerceIn(-COLOUR_DELTA_MAX, COLOUR_DELTA_MAX)

    /**
     * Applies the picture's R/G/B correction to [argb].
     *
     * Each argument is a **percent value with 100 neutral**, as the deployment
     * passes it (`params[15..17]`, where 100 dominates). The engine converts with
     * [engineColourChannel] and then *adds* the result to the channel — confirmed
     * by `v14[147] = v14[144] + v157 * (v14[147] - v14[144]) / v59`, i.e. the
     * tint is a delta added to the base channel, not a replacement.
     *
     * Values outside the percent range are clamped rather than wrapped.
     */
    fun applyColourCorrection(argb: Int, red: Int, green: Int, blue: Int): Int {
        val a = (argb ushr 24) and 0xFF
        val r = clamp(((argb ushr 16) and 0xFF) + engineColourChannel(red))
        val g = clamp(((argb ushr 8) and 0xFF) + engineColourChannel(green))
        val b = clamp((argb and 0xFF) + engineColourChannel(blue))
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Resolves [SAME] against the mode previously in effect. */
    fun resolveMode(mode: Int, previous: Int): Int = if (mode == SAME) previous else mode

    /** True when [mode] is one the engine documents. */
    fun isKnown(mode: Int): Boolean =
        mode == NORMAL || mode == ADD || mode == SUBTRACT || mode == MULTIPLY || mode == SAME

    private fun clamp(v: Int): Int = v.coerceIn(0, 255)
}
