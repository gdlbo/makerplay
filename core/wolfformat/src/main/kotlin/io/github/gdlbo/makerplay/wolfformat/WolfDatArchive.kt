package io.github.gdlbo.makerplay.wolfformat

/**
 * Generic WOLF dat archive: magic + version + record count, where each record
 * is a length-prefixed blob beginning with a string block. This covers
 * `MapTree.dat` and `SysDatabase.dat`, whose per-record settings are consumed
 * positionally by later milestones.
 */
data class WolfDatArchive(
    val v3: Boolean,
    /** Each record's decoded leading strings plus the raw remainder. */
    val records: List<Record>,
) {
    data class Record(
        val strings: List<String>,
        /** Bytes following the string block; never interpreted here. */
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Record && strings == other.strings && data.contentEquals(other.data)

        override fun hashCode(): Int = strings.hashCode() * 31 + data.contentHashCode()
    }

    companion object {
        fun parse(data: ByteArray): WolfDatArchive {
            val reader = BoundedReader(data)
            val v3 = WolfContainer.readStandardDatHeader(reader)
            val count = reader.readCount("archive record")
            val records = ArrayList<Record>(count)
            repeat(count) {
                val size = reader.readU4()
                if (size > BoundedReader.Limits.DEFAULT.maxFileBytes) {
                    throw WolfFormatException("Archive record of $size bytes exceeds limit")
                }
                    val blob = reader.slice(size.toInt(), "archive record")
                val stringCount = blob.readCount("record string")
                val strings = ArrayList<String>(stringCount)
                repeat(stringCount) { strings.add(blob.readString(v3)) }
                val rest = blob.readBytes(blob.remaining, "record data")
                records.add(Record(strings, rest))
            }
            return WolfDatArchive(v3, records)
        }
    }
}
