package io.github.gdlbo.makerplay.runtime.wolf

import org.junit.Assert.*
import org.junit.Test

class PictureOptionsTest {
    @Test
    fun `bit layout matches the published decode`() {
        // Anchor sits in bits 12..15, blend in bits 8..11. This matches both
        // wolfrpg-map-parser's options.rs and GamePro.exe's sub_A1CD40, which
        // reads the word as `(w >> 12) & 0xF`.
        val anchored = PictureOptions.decode(0x0000_3000) // anchor 3, blend 0
        assertEquals(PictureOptions.ANCHOR_TOP_RIGHT, anchored.anchor)
        assertEquals(PictureOptions.BLEND_NORMAL, anchored.blendMode)

        val centred = PictureOptions.decode(0x0000_1000) // anchor 1, blend 0
        assertEquals(PictureOptions.ANCHOR_CENTER, centred.anchor)

        val adding = PictureOptions.decode(0x0000_0100) // blend 1, anchor 0
        assertEquals(PictureOptions.BLEND_ADD, adding.blendMode)
        assertEquals(PictureOptions.ANCHOR_TOP_LEFT, adding.anchor)

        // The low byte is the display type, not a blend mode.
        assertEquals(1, PictureOptions.decode(0x0000_0001).displayType)
        assertEquals(PictureOptions.BLEND_NORMAL, PictureOptions.decode(0x0000_0001).blendMode)

        // byte 2 low bit = 相対モード, high nibble = zoom mode.
        val relative = PictureOptions.decode(0x0001_0000)
        assertTrue("bit 16 must be position_relative", relative.positionRelative)

        val perAxis = PictureOptions.decode(0x0030_0000)
        assertEquals(PictureOptions.ZOOM_DIFFERENT, perAxis.zoomMode)

        // byte 3: 1 range, 2 link-to-scroll, 4 free transform.
        assertEquals(true, PictureOptions.decode(0x0100_0000).range)
        assertEquals(true, PictureOptions.decode(0x0200_0000).linkToScroll)
        assertEquals(true, PictureOptions.decode(0x0400_0000).freeTransform)
        assertEquals(false, PictureOptions.decode(0).freeTransform)
    }

    @Test
    fun `blend and anchor constants match the engine values`() {
        // Values published in wolfrpg-map-parser and consistent with the manual's
        // four blend modes and seven anchors.
        assertEquals(0, PictureOptions.BLEND_NORMAL)
        assertEquals(1, PictureOptions.BLEND_ADD)
        assertEquals(2, PictureOptions.BLEND_SUBTRACT)
        assertEquals(3, PictureOptions.BLEND_MULTIPLY)
        assertEquals(0x0F, PictureOptions.BLEND_SAME)
        assertEquals(0, PictureOptions.ANCHOR_TOP_LEFT)
        assertEquals(1, PictureOptions.ANCHOR_CENTER)
        assertEquals(2, PictureOptions.ANCHOR_BOTTOM_LEFT)
        assertEquals(3, PictureOptions.ANCHOR_TOP_RIGHT)
        assertEquals(4, PictureOptions.ANCHOR_BOTTOM_RIGHT)
        assertEquals(0, PictureOptions.ZOOM_NORMAL)
        assertEquals(3, PictureOptions.ZOOM_DIFFERENT)
        assertEquals(4, PictureOptions.ZOOM_SAME)
    }
}
