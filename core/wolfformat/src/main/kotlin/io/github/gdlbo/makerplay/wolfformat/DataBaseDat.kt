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
        /** Optional type-level string present when [dataIdMethod] is [STRING_INDICATOR]. */
        val dataIdString: String?,
        /** Per-property encoding: 1000+ = number slot, 2000+ = string slot. */
        val propertyPositions: IntArray,
        val entries: List<Entry>,
    ) {
        override fun equals(other: Any?): Boolean = other is DbType &&
            dataIdMethod == other.dataIdMethod &&
            dataIdString == other.dataIdString &&
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

        /** When [DbType.dataIdMethod] equals this, a type string follows. */
        const val STRING_INDICATOR = 0x0001D4C0

        const val INT_START = 1_000
        const val STRING_START = 2_000

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
            val dataIdMethod = reader.readU4().toInt()
            val dataIdString = if (dataIdMethod == STRING_INDICATOR) {
                reader.readString(v3)
            } else {
                null
            }
            val propertyCount = reader.readCount("property position")
            if (propertyCount > BoundedReader.Limits.DEFAULT.maxStringBytes / 4) {
                throw WolfFormatException("Property count too large")
            }
            val positions = IntArray(propertyCount) { reader.readU4().toInt() }
            val entryCount = reader.readCount("database entry")

            // Number pool = positions in [1000, 2000); string pool = >= 2000.
            // Invalid slots (< 1000) contribute to neither pool.
            val numberCount = positions.count { it in INT_START until STRING_START }
            val stringCount = positions.count { it >= STRING_START }

            val entries = ArrayList<Entry>(entryCount)
            repeat(entryCount) {
                val numbers = IntArray(numberCount) { reader.readS4() }
                val strings = ArrayList<String>(stringCount)
                repeat(stringCount) { strings.add(reader.readString(v3)) }
                entries.add(Entry(numbers, strings))
            }
            return DbType(
                dataIdMethod = dataIdMethod,
                dataIdString = dataIdString,
                propertyPositions = positions,
                entries = entries,
            )
        }
    }
}
