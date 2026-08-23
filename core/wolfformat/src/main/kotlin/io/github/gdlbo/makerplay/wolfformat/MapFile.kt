package io.github.gdlbo.makerplay.wolfformat

/**
 * Parser for WOLF RPG map files (Data/MapXXX/xxx.mps).
 *
 * Maps hold three tile layers (lower/upper + animation planes encoded as u4
 * pixels with autotile packing), the tileset reference, and the events with
 * their pages, triggers, move routes, and command lists.
 */
data class MapFile(
    val v3: Boolean,
    /** 0x65 = editor v2, 0x66 = v3. */
    val revision: Int,
    val title: String,
    val tilesetId: Int,
    val width: Int,
    val height: Int,
    /**
     * Three layers of width*height raw pixel words. Values >= 100000 encode
     * autotiles (id = value / 100000); otherwise they are base tile ids.
     */
    val layers: List<IntArray>,
    val events: List<MapEvent>,
) {
    data class MapEvent(
        val eventId: Int,
        val title: String,
        val x: Int,
        val y: Int,
        val pages: List<Page>,
    )

    data class Page(
        val graphicChipId: Int,
        val graphicFile: String,
        val graphicRow: Int,
        val graphicCol: Int,
        val graphicOpacity: Int,
        /** 0 confirm key, 1 autorun, 2 parallel, 3 player touch, 4 event touch. */
        val triggerCondition: Int,
        /** Four packed switch-condition bytes (operator + enabled nibbles). */
        val triggerSwitchesRaw: IntArray,
        /** Four raw trigger variable references ("magic numbers"). */
        val triggerVariables: IntArray,
        /** Four signed trigger comparison values. */
        val triggerValues: IntArray,
        val route: MoveRoute,
        val commands: List<EventCommand>,
        val shadowGraphicId: Int,
        val rangeExtensionX: Int,
        val rangeExtensionY: Int,
    ) {
        override fun equals(other: Any?): Boolean = other is Page && other === this
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    companion object {
        private const val EVENT_HEADER = 0x6F
        private const val EVENT_FOOTER = 0x70
        private const val PAGE_HEADER = 0x79
        private const val PAGE_FOOTER = 0x7A
        private const val MAP_FOOTER = 0x66
        private const val EMPTY_MAP_MARKER = -1

        fun parse(data: ByteArray): MapFile {
            val reader = BoundedReader(data)
            // Magic: ten zero bytes, 'WOLFM', one zero.
            val magic = reader.readBytes(16, "mps magic")
            val expectedTail = "WOLFM".map { it.code.toByte() }.toByteArray()
            if (!magic.copyOfRange(10, 15).contentEquals(expectedTail) ||
                magic.anyIndexed(0, 10) { b -> b != 0.toByte() } ||
                magic[15] != 0.toByte()
            ) {
                throw WolfFormatException("Not a WOLF map file")
            }
            val v3 = when (val version = reader.readU1()) {
                WolfContainer.VERSION_V2 -> false
                WolfContainer.VERSION_V3 -> true
                else -> throw WolfFormatException("Unknown mps version 0x${version.toString(16)}")
            }
            reader.skip(3, "mps version padding")
            if (reader.readU4() != 0x64L) throw WolfFormatException("Unexpected mps unknown1")
            val revision = reader.readU1()
            if (revision != 0x65 && revision != 0x66) {
                throw WolfFormatException("Unknown mps revision 0x${revision.toString(16)}")
            }
            val title = reader.readString(v3)
            val tilesetId = reader.readS4()
            val width = reader.readCount("map width")
            val height = reader.readCount("map height")
            if (width <= 0 || height <= 0 || width.toLong() * height > BoundedReader.Limits.DEFAULT.maxCount) {
                throw WolfFormatException("Unreasonable map size ${width}x$height")
            }
            val eventCount = reader.readCount("map event")

            // Map layers: three blocks of width*height*3? No — the layer area is
            // width*height pixels per plane across three planes; each block starts
            // at its recorded offset. We read linearly after validating the first
            // word of the block (0xFFFFFFFF marks an empty map).
            val totalPixels = width * height
            // One contiguous layer area of width*height*3 pixel words; a leading
            // 0xFFFFFFFF marks an empty (all-default) map.
            val firstPixel = reader.readU4()
            val layers: List<IntArray> = if (firstPixel == 0xFFFFFFFFL) {
                // Empty map: only the marker word is present.
                listOf(IntArray(totalPixels), IntArray(totalPixels), IntArray(totalPixels))
            } else {
                val all = IntArray(totalPixels * 3)
                all[0] = firstPixel.toInt()
                for (i in 1 until totalPixels * 3) all[i] = reader.readU4().toInt()
                listOf(
                    all.copyOfRange(0, totalPixels),
                    all.copyOfRange(totalPixels, totalPixels * 2),
                    all.copyOfRange(totalPixels * 2, totalPixels * 3),
                )
            }

            val events = ArrayList<MapEvent>(eventCount)
            repeat(eventCount) { events.add(readEvent(reader, v3)) }
            val footer = reader.readU1()
            if (footer != MAP_FOOTER) throw WolfFormatException("Missing map footer")

            return MapFile(v3, revision, title, tilesetId, width, height, layers, events)
        }

        private fun readEvent(reader: BoundedReader, v3: Boolean): MapEvent {
            if (reader.readU1() != EVENT_HEADER) throw WolfFormatException("Event missing header")
            if (reader.readU4() != 0x3039L) throw WolfFormatException("Event missing header2")
            val eventId = reader.readS4()
            val title = reader.readString(v3)
            val x = reader.readS4()
            val y = reader.readS4()
            val pageCount = reader.readCount("event page")
            if (reader.readU4() != 0L) throw WolfFormatException("Event separator must be zero")
            val pages = ArrayList<Page>(pageCount)
            repeat(pageCount) { pages.add(readPage(reader, v3)) }
            if (reader.readU1() != EVENT_FOOTER) throw WolfFormatException("Event missing footer")
            return MapEvent(eventId, title, x, y, pages)
        }

        private fun readPage(reader: BoundedReader, v3: Boolean): Page {
            if (reader.readU1() != PAGE_HEADER) throw WolfFormatException("Page missing header")
            val chipId = reader.readS4()
            val file = reader.readString(v3)
            val row = reader.readU1()
            val col = reader.readU1()
            val opacity = reader.readU1()
            reader.readU1() // render mode
            val triggerCondition = reader.readU1()
            if (triggerCondition !in 0..4) {
                throw WolfFormatException("Invalid page trigger $triggerCondition")
            }
            val triggerSwitches = IntArray(4) { reader.readU1() }
            val triggerVariables = IntArray(4) { reader.readU4().toInt() }
            val triggerValues = IntArray(4) { reader.readS4() }
            val route = MoveRoute.parse(reader)
            val commands = EventCommand.parseList(reader, v3)
            if (reader.readU4() != 3L) throw WolfFormatException("Page unknown3 must be 3")
            val shadowGraphicId = reader.readU1()
            val rangeX = reader.readU1()
            val rangeY = reader.readU1()
            if (reader.readU1() != PAGE_FOOTER) throw WolfFormatException("Page missing footer")
            return Page(
                graphicChipId = chipId,
                graphicFile = file,
                graphicRow = row,
                graphicCol = col,
                graphicOpacity = opacity,
                triggerCondition = triggerCondition,
                triggerSwitchesRaw = triggerSwitches,
                triggerVariables = triggerVariables,
                triggerValues = triggerValues,
                route = route,
                commands = commands,
                shadowGraphicId = shadowGraphicId,
                rangeExtensionX = rangeX,
                rangeExtensionY = rangeY,
            )
        }

        private inline fun ByteArray.anyIndexed(from: Int, to: Int, predicate: (Byte) -> Boolean): Boolean {
            for (i in from until to) if (predicate(this[i])) return true
            return false
        }
    }
}
