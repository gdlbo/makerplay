package io.github.gdlbo.makerplay.runtime.wolf

/**
 * Decoded form of the packed picture option word carried in `params[0]` of an
 * opcode-150 (picture) command.
 *
 * The bit layout is cross-checked two ways:
 *
 * - `wolfrpg-map-parser` (MIT) decodes the same word in
 *   `command/picture_command/options.rs`, and
 * - `GamePro.exe`'s picture handler `sub_A1CD40` reads the word with
 *   `& 0xF0000` for the name-presence nibble, `(w >> 12) & 0xF` for the
 *   anchor-ish selector, and `& 0x4000000` for the free-transform flag.
 *
 * Both agree on the byte boundaries below, so this is format fact rather than a
 * guess. See docs/wolf-prior-art.md for the licence and provenance note.
 *
 * Verified against this deployment: across 579 eleven-parameter picture
 * commands in `CommonEvent.dat`, `params[0]` decodes to a valid anchor for
 * 551 of 564 literal values and to a valid blend for all 564, while every other
 * parameter index fails that test — which is what identifies the slot.
 */
data class PictureOptions(
    /** Display operation / display type, low byte. */
    val displayType: Int,
    /** 0 normal, 1 add, 2 subtract, 3 multiply, 0x0F "same as before". */
    val blendMode: Int,
    /** 0 top-left, 1 centre, 2 bottom-left, 3 top-right, 4 bottom-right. */
    val anchor: Int,
    /** 相対モード: treat x/y as an offset from the current position. */
    val positionRelative: Boolean,
    /** 0 normal, 3 per-axis ("different"), 4 same as before. */
    val zoomMode: Int,
    val range: Boolean,
    val linkToScroll: Boolean,
    /** 自由変形: four corners positioned independently. */
    val freeTransform: Boolean,
) {
    companion object {
        const val BLEND_NORMAL = 0
        const val BLEND_ADD = 1
        const val BLEND_SUBTRACT = 2
        const val BLEND_MULTIPLY = 3
        const val BLEND_SAME = 0x0F

        const val ANCHOR_TOP_LEFT = 0
        const val ANCHOR_CENTER = 1
        const val ANCHOR_BOTTOM_LEFT = 2
        const val ANCHOR_TOP_RIGHT = 3
        const val ANCHOR_BOTTOM_RIGHT = 4

        const val ZOOM_NORMAL = 0
        const val ZOOM_DIFFERENT = 3
        const val ZOOM_SAME = 4

        fun decode(raw: Int): PictureOptions = PictureOptions(
            displayType = raw and 0xFF,
            blendMode = (raw ushr 8) and 0x0F,
            anchor = ((raw ushr 8) and 0xF0) ushr 4,
            positionRelative = ((raw ushr 16) and 0x01) != 0,
            zoomMode = ((raw ushr 16) and 0xF0) ushr 4,
            range = ((raw ushr 24) and 0x01) != 0,
            linkToScroll = ((raw ushr 24) and 0x02) != 0,
            freeTransform = ((raw ushr 24) and 0x04) != 0,
        )

        /** True when [anchor] is one of the documented values. */
        fun isKnownAnchor(anchor: Int): Boolean = anchor in 0..4

        /** True when [blendMode] is one of the documented values. */
        fun isKnownBlend(blend: Int): Boolean =
            blend == BLEND_NORMAL || blend == BLEND_ADD || blend == BLEND_SUBTRACT ||
                blend == BLEND_MULTIPLY || blend == BLEND_SAME
    }
}
