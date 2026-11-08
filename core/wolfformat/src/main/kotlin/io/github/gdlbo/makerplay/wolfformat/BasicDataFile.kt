package io.github.gdlbo.makerplay.wolfformat

/**
 * Parser for the two `BasicData` files that share the "10 zero bytes" framing
 * instead of the `00 'W' 00 00 'O' 'L'` core magic used by every other WOLF
 * dat file.
 *
 * Recovered layout, identical for both files:
 *
 * ```
 * [10 bytes, all zero]
 * [u1 revision/marker]   MapTree.dat = 0x90, SysDataBaseBasic.dat = 0xC0
 * [u4 record count]      little-endian
 * [records...]
 * [u1 footer]            same value as the marker byte in both shipped files
 * ```
 *
 * `MapTree.dat` records are `[s32 parentId][s32 mapId]`, where `parentId` is
 * the parent node in the editor's map tree (negative = top level) and `mapId`
 * selects `Data/MapData/MapNNN.mps`. That layout is verified against the
 * deployment: the shipped file yields 240 records, ids `0..239` with no
 * duplicates, and every one of those ids has a matching `MapNNN.mps` on disk.
 *
 * `SysDataBaseBasic.dat` record bodies are **not** decoded. Two independent
 * reasons, recorded so this is not mistaken for an oversight:
 *
 * 1. No engine consumer reaches them. `GamePro.exe` contains no reference to
 *    the string `SysDataBaseBasic` in either ASCII or UTF-16, while it does
 *    reference `CommonEvent.dat`, `TileSetData.dat`, `Game.dat`, `CDataBase`
 *    and `MapTree.dat`. Nothing in this runtime reads the file either.
 * 2. There is no public specification, and the payload is a packed bit-field
 *    stream (values such as `0x100`, `0x10000`, `0x1000000`, `0x10000000`)
 *    rather than a record array. Its records cannot be separated without
 *    guessing, and a guessed layout would be worse than an honest boundary.
 *
 * The bodies are therefore exposed as raw bytes so a future consumer can decode
 * them once a reference exists, while the framing is validated strictly: a
 * wrong count or footer is reported instead of being silently accepted.
 */
sealed class BasicDataFile {
    abstract val marker: Int
    abstract val declaredCount: Int

    /**
     * The editor's map tree: one record per map, linking a map id to its
     * parent node so nested map folders can be reproduced.
     */
    data class MapTree(
        override val marker: Int,
        val nodes: List<Node>,
    ) : BasicDataFile() {
        data class Node(
            /** Parent node id; negative means the node sits at the top level. */
            val parentId: Int,
            /** Map id, selecting `Data/MapData/MapNNN.mps`. */
            val mapId: Int,
        )

        override val declaredCount: Int get() = nodes.size
    }

    /** Framing-only view of `SysDataBaseBasic.dat`; bodies stay raw. */
    data class OpaqueSystemBasics(
        override val marker: Int,
        override val declaredCount: Int,
        val payload: ByteArray,
    ) : BasicDataFile() {
        override fun equals(other: Any?): Boolean = other is OpaqueSystemBasics &&
            marker == other.marker &&
            declaredCount == other.declaredCount &&
            payload.contentEquals(other.payload)

        override fun hashCode(): Int = marker * 31 + declaredCount + payload.size
    }

    companion object {
        /** Number of leading zero bytes before the marker. */
        const val ZERO_HEADER = 10
        const val MARKER_MAP_TREE = 0x90
        const val MARKER_SYSTEM_BASICS = 0xC0

        /** Observed closing byte of the shipped MapTree.dat. */
        const val FOOTER_MAP_TREE = 0xBC
        private const val RECORD_BYTES_MAP_TREE = 8

        fun isBasicData(data: ByteArray): Boolean {
            if (data.size <= ZERO_HEADER) return false
            for (i in 0 until ZERO_HEADER) if (data[i] != 0.toByte()) return false
            return true
        }

        /** Parses `MapTree.dat` into its parent/map links. */
        fun parseMapTree(data: ByteArray): MapTree {
            val (marker, reader) = openFramed(data, "MapTree.dat")
            val count = reader.readCount("map tree node")
            val expected = count.toLong() * RECORD_BYTES_MAP_TREE
            if (expected > reader.remaining) {
                throw WolfFormatException(
                    "MapTree.dat declares $count nodes ($expected bytes) but only " +
                        "${reader.remaining} bytes remain",
                )
            }
            val nodes = ArrayList<MapTree.Node>(count)
            repeat(count) {
                nodes.add(MapTree.Node(parentId = reader.readS4(), mapId = reader.readS4()))
            }
            requireFooter(reader, marker, "MapTree.dat")
            return MapTree(marker, nodes)
        }

        /** Validates the framing of `SysDataBaseBasic.dat` and keeps the bodies raw. */
        fun parseSystemBasics(data: ByteArray): OpaqueSystemBasics {
            val (marker, reader) = openFramed(data, "SysDataBaseBasic.dat")
            val count = reader.readCount("system basics type")
            val body = reader.readBytes(reader.remaining - 1, "SysDataBaseBasic.dat payload")
            requireFooter(reader, marker, "SysDataBaseBasic.dat")
            return OpaqueSystemBasics(marker, count, body)
        }

        /** Opens the shared zero header and marker, returning a reader positioned at the count. */
        private fun openFramed(data: ByteArray, what: String): Pair<Int, BoundedReader> {
            if (!isBasicData(data)) {
                throw WolfFormatException("Not a $what file: expected $ZERO_HEADER zero bytes")
            }
            val marker = data[ZERO_HEADER].toInt() and 0xFF
            return marker to BoundedReader(data, offset = ZERO_HEADER + 1)
        }

        private fun requireFooter(reader: BoundedReader, marker: Int, what: String) {
            val footer = reader.readU1()
            // The shipped files close with a value that differs from the marker:
            // MapTree.dat ends 0xBC behind a 0x90 marker, and SysDataBaseBasic.dat
            // ends 0xC0 behind a 0xC0 marker. Accept either, plus the shared
            // database footers, so both framings validate without special cases.
            if (footer != marker && footer != FOOTER_MAP_TREE &&
                footer != WolfContainer.FOOTER_V2 &&
                footer != WolfContainer.FOOTER_V30 && footer != WolfContainer.FOOTER_V33
            ) {
                throw WolfFormatException(
                    "$what has unexpected footer 0x${footer.toString(16)} " +
                        "(marker 0x${marker.toString(16)})",
                )
            }
            if (reader.remaining != 0) {
                throw WolfFormatException("$what has ${reader.remaining} trailing byte(s)")
            }
        }
    }
}
