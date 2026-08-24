package io.github.gdlbo.makerplay.wolfformat

/**
 * Parser for `TileSetData.dat`: the tileset definitions referenced by maps.
 *
 * Two record revisions exist: 209 (v2, Shift-JIS, 15 autotile slots) and
 * 210 (v3+, UTF-8, 31 autotile slots). Passability is a bitfield per tile.
 */
data class TileSetData(
    val v3: Boolean,
    val tilesets: List<Tileset>,
) {
    data class Tileset(
        val title: String,
        val baseTilesetFile: String,
        val autoTileFiles: List<String>,
        val tagNumbers: List<Int>,
        val tilePassability: List<Passability>,
    )

    /** Per-tile movement/counter flags from the passability bit field. */
    data class Passability(
        val counterEnabled: Boolean,
        val square: Boolean,
        val quarterTile: Boolean,
        val star: Boolean,
        /** Quarter-tile corner passability (valid when [quarterTile]). */
        val topLeftPassable: Boolean,
        val topRightPassable: Boolean,
        val bottomLeftPassable: Boolean,
        val bottomRightPassable: Boolean,
        /** Whole-edge blocking for standard tiles (valid when !quarterTile). */
        val upwardsNotPassable: Boolean,
        val rightwardsNotPassable: Boolean,
        val leftwardsNotPassable: Boolean,
        val downwardsNotPassable: Boolean,
        val downArrow: Boolean,
        val triangle: Boolean,
        val raw: Int,
    )

    companion object {
        private const val REVISION_V2 = 209
        private const val REVISION_V3 = 210

        fun parse(data: ByteArray): TileSetData {
            val reader = BoundedReader(data)
            val v3 = WolfContainer.readCore(reader)
            val tag = reader.readBytes(3, "tileset file tag")
            if (!tag.contentEquals(byteArrayOf('F'.code.toByte(), 'M'.code.toByte(), 0))) {
                throw WolfFormatException("Not a TileSetData.dat file")
            }
            val revision = reader.readU1()
            // Revisions beyond 211 exist in newer editors; they keep the
            // v3 record layout. Only the slot count differs for v2.
            val autoTileSlots = if (revision <= REVISION_V2) 15 else 31
            val count = reader.readCount("tileset")

            fun readTileset(): Tileset {
                val title = reader.readString(v3 && revision != REVISION_V2)
                val baseFile = reader.readString(v3)
                val autos = ArrayList<String>(autoTileSlots)
                repeat(autoTileSlots) { autos.add(reader.readString(v3)) }
                if (reader.readU1() != 0xFF) throw WolfFormatException("Missing tileset separator 1")
                val tagCount = reader.readCount("tag number")
                val tags = ArrayList<Int>(tagCount)
                repeat(tagCount) { tags.add(reader.readU1()) }
                if (reader.readU1() != 0xFF) throw WolfFormatException("Missing tileset separator 2")
                val passableCount = reader.readCount("passability entry")
                val passability = ArrayList<Passability>(passableCount)
                repeat(passableCount) { passability.add(readPassability(reader)) }
                return Tileset(title, baseFile, autos, tags, passability)
            }

            val tilesets = ArrayList<Tileset>(count)
            repeat(count) { tilesets.add(readTileset()) }
            WolfContainer.readFooter(reader, setOf(WolfContainer.FOOTER_TILESET))
            return TileSetData(v3, tilesets)
        }

        private fun readPassability(reader: BoundedReader): Passability {
            // Bit-level layout per tilesetdata_dat.ksy: two flag bytes + reserved word.
            val b0 = reader.readU1()
            val b1 = reader.readU1()
            reader.readU2() // reserved
            return Passability(
                counterEnabled = b0 and (1 shl 7) != 0,
                square = b0 and (1 shl 6) != 0,
                quarterTile = b0 and (1 shl 5) != 0,
                star = b0 and (1 shl 4) != 0,
                topLeftPassable = b0 and (1 shl 3) != 0,
                topRightPassable = b0 and (1 shl 2) != 0,
                bottomLeftPassable = b0 and (1 shl 1) != 0,
                bottomRightPassable = b0 and 1 != 0,
                upwardsNotPassable = b0 and (1 shl 3) != 0,
                rightwardsNotPassable = b0 and (1 shl 2) != 0,
                leftwardsNotPassable = b0 and (1 shl 1) != 0,
                downwardsNotPassable = b0 and 1 != 0,
                downArrow = b1 and (1 shl 1) != 0,
                triangle = b1 and 1 != 0,
                raw = (b1 shl 8) or b0,
            )
        }
    }
}
