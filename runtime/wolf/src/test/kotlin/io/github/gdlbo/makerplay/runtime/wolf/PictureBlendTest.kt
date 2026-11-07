package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.wolfformat.CommonEventDat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Blend modes and colour correction, asserted on exact pixel values.
 *
 * Each mode composites a known source pixel over a known destination and the
 * exact output is compared, so "implemented" means the arithmetic is right
 * rather than merely that something was drawn.
 *
 * The mode values come from `wolfrpg-map-parser`'s `BlendingMethod` and the
 * manual's four documented modes; all four appear in this deployment
 * (`0:849 1:18 2:4 3:7 15:82`), so none of this is speculative.
 */
class PictureBlendTest {

private fun argb(a: Int, r: Int, g: Int, b: Int) =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun channels(argb: Int): List<Int> =
        listOf((argb ushr 24) and 0xFF, (argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF)

    // --- each mode, exact output -----------------------------------------

    @Test
    fun `normal replaces the destination`() {
        val dst = argb(255, 10, 20, 30)
        val src = argb(255, 200, 100, 50)
        assertEquals(
            listOf(255, 200, 100, 50),
            channels(PictureBlend.blend(dst, src, PictureBlend.NORMAL)),
        )
    }

    @Test
    fun `add sums the channels`() {
        val dst = argb(255, 10, 20, 30)
        val src = argb(255, 5, 6, 7)
        assertEquals(
            listOf(255, 15, 26, 37),
            channels(PictureBlend.blend(dst, src, PictureBlend.ADD)),
        )
    }

    @Test
    fun `add clamps at white`() {
        val dst = argb(255, 250, 250, 250)
        val src = argb(255, 20, 20, 20)
        assertEquals(
            "adding past 255 must clamp, not wrap",
            listOf(255, 255, 255, 255),
            channels(PictureBlend.blend(dst, src, PictureBlend.ADD)),
        )
    }

    @Test
    fun `subtract takes the source away`() {
        val dst = argb(255, 100, 80, 60)
        val src = argb(255, 30, 20, 10)
        assertEquals(
            listOf(255, 70, 60, 50),
            channels(PictureBlend.blend(dst, src, PictureBlend.SUBTRACT)),
        )
    }

    @Test
    fun `subtract clamps at black`() {
        val dst = argb(255, 10, 10, 10)
        val src = argb(255, 50, 50, 50)
        assertEquals(
            "subtracting past 0 must clamp, not wrap",
            listOf(255, 0, 0, 0),
            channels(PictureBlend.blend(dst, src, PictureBlend.SUBTRACT)),
        )
    }

    @Test
    fun `multiply darkens proportionally`() {
        val dst = argb(255, 255, 128, 0)
        val src = argb(255, 128, 128, 255)
        assertEquals(
            listOf(255, 128, 64, 0),
            channels(PictureBlend.blend(dst, src, PictureBlend.MULTIPLY)),
        )
        // Multiplying by white is the identity; by black it is black.
        assertEquals(
            channels(dst),
            channels(PictureBlend.blend(dst, argb(255, 255, 255, 255), PictureBlend.MULTIPLY)),
        )
        assertEquals(
            listOf(255, 0, 0, 0),
            channels(PictureBlend.blend(dst, argb(255, 0, 0, 0), PictureBlend.MULTIPLY)),
        )
    }

    @Test
    fun `each mode gives a different result for the same inputs`() {
        val dst = argb(255, 100, 100, 100)
        val src = argb(255, 60, 60, 60)
        val results = listOf(
            PictureBlend.NORMAL,
            PictureBlend.ADD,
            PictureBlend.SUBTRACT,
            PictureBlend.MULTIPLY,
        ).map { channels(PictureBlend.blend(dst, src, it)) }
        assertEquals(
            "the four modes must be distinguishable, got $results",
            results.size,
            results.distinct().size,
        )
    }

    // --- alpha -----------------------------------------------------------

    @Test
    fun `a fully transparent source leaves the destination alone`() {
        val dst = argb(255, 11, 22, 33)
        assertEquals(
            channels(dst),
            channels(PictureBlend.blend(dst, argb(0, 255, 255, 255), PictureBlend.ADD)),
        )
    }

    @Test
    fun `half alpha add applies half the addition`() {
        val dst = argb(255, 100, 100, 100)
        val src = argb(128, 20, 20, 20)
        // blended = 120, then 50% over 100 => ~110
        val out = channels(PictureBlend.blend(dst, src, PictureBlend.ADD))
        assertEquals(255, out[0])
        assertTrue("half-alpha add should land near 110, got ${out[1]}", out[1] in 108..112)
    }

    // --- SAME resolution -------------------------------------------------

    @Test
    fun `same reuses the previous mode`() {
        assertEquals(PictureBlend.MULTIPLY, PictureBlend.resolveMode(PictureBlend.SAME, PictureBlend.MULTIPLY))
        assertEquals(PictureBlend.ADD, PictureBlend.resolveMode(PictureBlend.ADD, PictureBlend.MULTIPLY))
        assertEquals(PictureBlend.NORMAL, PictureBlend.resolveMode(PictureBlend.SAME, PictureBlend.NORMAL))
    }

    @Test
    fun `all documented modes are known`() {
        for (m in listOf(0, 1, 2, 3, 0x0F)) assertTrue("mode $m", PictureBlend.isKnown(m))
        assertTrue("mode 4 is not documented", !PictureBlend.isKnown(4))
    }

    // --- colour correction ----------------------------------------------

    @Test
    fun `colour correction uses the engine's percent scale`() {
        // Decompiled from sub_990570: channel = (v - 100) * 2.55, added to the source.
        // 100 is neutral, so it must be a no-op.
        val src = argb(255, 100, 100, 100)
        assertEquals(
            "100 is neutral",
            listOf(255, 100, 100, 100),
            channels(PictureBlend.applyColourCorrection(src, 100, 100, 100)),
        )
        // 120 -> (120-100)*2.55 = 51 added.
        assertEquals(
            listOf(255, 151, 151, 151),
            channels(PictureBlend.applyColourCorrection(src, 120, 120, 120)),
        )
        // 80 -> (80-100)*2.55 = -51.
        assertEquals(
            listOf(255, 49, 49, 49),
            channels(PictureBlend.applyColourCorrection(src, 80, 80, 80)),
        )
        // 0 -> -255, which clamps the channel to 0.
        assertEquals(
            listOf(255, 0, 0, 0),
            channels(PictureBlend.applyColourCorrection(src, 0, 0, 0)),
        )
        // 200 -> (200-100)*2.55 = 254.999... in IEEE754, truncated to 254 by the
        // engine's `(int)` cast. 100 + 254 = 354, clamped to 255.
        assertEquals(
            listOf(255, 255, 255, 255),
            channels(PictureBlend.applyColourCorrection(src, 200, 200, 200)),
        )
    }

    @Test
    fun `colour correction is per channel and clamps at the ends`() {
        val src = argb(255, 250, 5, 128)
        // Only red corrected: red clamps at 255, others untouched.
        assertEquals(
            listOf(255, 255, 5, 128),
            channels(PictureBlend.applyColourCorrection(src, 200, 100, 100)),
        )
        // Only green corrected downward: green clamps at 0.
        assertEquals(
            listOf(255, 250, 0, 128),
            channels(PictureBlend.applyColourCorrection(src, 100, 0, 100)),
        )
        // Only blue corrected upward by (150-100)*2.55 = 127 -> 128+127 clamps.
        assertEquals(
            listOf(255, 250, 5, 255),
            channels(PictureBlend.applyColourCorrection(src, 100, 100, 150)),
        )
    }

    @Test
    fun `the engine channel conversion matches the decompiled arithmetic`() {
        // (v - 100) * 2.55, floored, clamped to 0..255.
        assertEquals(0, PictureBlend.engineColourChannel(100))
        assertEquals(51, PictureBlend.engineColourChannel(120))
        assertEquals(-51, PictureBlend.engineColourChannel(80))
        // The endpoints are 254/-254, not 255/-255: `(200-100)*2.55` is
        // 254.999... in IEEE754 and the engine truncates with `(int)`. This is the
        // engine's actual arithmetic, not an approximation of it.
        assertEquals(-254, PictureBlend.engineColourChannel(0))
        assertEquals(254, PictureBlend.engineColourChannel(200))
        assertEquals(127, PictureBlend.engineColourChannel(150))
        // Beyond the percent range still stays inside the delta bound.
        assertEquals(254, PictureBlend.engineColourChannel(1000))
        assertEquals(-254, PictureBlend.engineColourChannel(-1000))
    }

    @Test
    fun `colour correction preserves alpha`() {
        val src = argb(77, 100, 100, 100)
        assertEquals(77, (PictureBlend.applyColourCorrection(src, 50, 50, 50) ushr 24) and 0xFF)
    }

    // --- deployment evidence --------------------------------------------


}
