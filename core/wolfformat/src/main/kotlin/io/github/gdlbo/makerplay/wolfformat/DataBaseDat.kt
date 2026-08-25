package io.github.gdlbo.makerplay.wolfformat

/**
 * Parser for `DataBase.dat` (also the layout used by SysDatabase content
 * records): database types with property layouts and per-entry data blocks.
 *
 * Entries keep raw property values; the interpreter resolves them lazily via
 * [DbType.propertyPositions] (block 0 = string pool, block 1 = number pool).
 */
data class DataBaseDat(
    val v3: Boolean,
    val types: List<DbType>,
) {
    data class DbType(
        val dataIdMethod: Int,
        /** Per-property encoding: value / 1000 selects the block, % 1000 the slot. */
        val propertyPositions: IntArray,
        val entries: List<Entry>,
    ) {
        override fun equals(other: Any?): Boolean = other is DbType &&
            dataIdMethod == other.dataIdMethod &&
            propertyPositions.contentEquals(other.propertyPositions) &&
            entries == other.entries

        override fun hashCode(): Int = dataIdMethod * 31 + entries.size
    }

    data class Entry(val numbers: IntArray, val strings: List<String>) {
        override fun equals(other: Any?): Boolean = other is Entry &&
            numbers.contentEquals(other.numbers) && strings == other.strings

        override fun hashCode(): Int = numbers.size * 31 + strings.size
    }

    companion object {
        /** Little-endian `FE FF FF FF` read unsigned. */
        private const val TYPE_HEADER = 0xFFFFFFFEL

        fun parse(data: ByteArray): DataBaseDat {
            // v3.5 databases (revision 0xC4 at offset 10) LZ4-pack the body
            // from offset 11 onward, keeping the standard header prefix.
            val working = if (data.size > 10 && (data[10].toInt() and 0xFF) == 0xC4) {
                val head = BoundedReader(data, offset = 11)
                val decompressedSize = head.readU4().toInt()
                val compressedSize = head.readU4().toInt()
                if (decompressedSize < 0 || compressedSize < 0 || compressedSize > head.remaining) {
                    throw WolfFormatException("Invalid v3.5 database compression header")
                }
                val compressed = head.readBytes(compressedSize, "v3.5 database payload")
                data.copyOfRange(0, 11) + WolfLz4.decompress(compressed, decompressedSize)
            } else {
                data
            }
            val reader = BoundedReader(working)
            val v3 = WolfContainer.readStandardDatHeader(reader)
            val count = reader.readCount("database type")
            val types = ArrayList<DbType>(count)
            repeat(count) { types.add(readType(reader, v3)) }
            WolfContainer.readFooter(reader)
            return DataBaseDat(v3, types)
        }

        private fun readType(reader: BoundedReader, v3: Boolean): DbType {
            if (reader.readU4() != TYPE_HEADER) {
                throw WolfFormatException("Database type missing sub-header")
            }
            reader.readU4() // data id method
            val propertyCount = reader.readCount("property position")
            if (propertyCount > BoundedReader.Limits.DEFAULT.maxStringBytes / 4) {
                throw WolfFormatException("Property count too large")
            }
            val positions = IntArray(propertyCount) { reader.readU4().toInt() }
            val entryCount = reader.readCount("database entry")

            // Property pools: block 1 (raw >= 1000) entries are numbers, the
            // rest strings. Entries carry no length prefixes of their own:
            // each holds exactly block1-count numbers then block2-count strings.
            val numbersEnd = positions.count { it / 1000 == 1 }
            val stringsCount = propertyCount - numbersEnd

            val entries = ArrayList<Entry>(entryCount)
            repeat(entryCount) {
                val numbers = IntArray(numbersEnd) { reader.readS4() }
                val strings = ArrayList<String>(stringsCount)
                repeat(stringsCount) { strings.add(reader.readString(v3)) }
                entries.add(Entry(numbers, strings))
            }
            return DbType(dataIdMethod = 0, propertyPositions = positions, entries = entries)
        }
    }
}
