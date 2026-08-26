package io.github.gdlbo.makerplay.wolfformat

/**
 * Parser for `*.project` sidecars that pair with database `.dat` files.
 *
 * Layout mirrors the editor project stream: per-type name, field names,
 * entry names, description, then field metadata (types / args / defaults).
 * Runtime value storage still lives in [DataBaseDat]; this file only supplies
 * the human-readable indices needed for name-based command access.
 */
data class DataBaseProject(
    val types: List<DbTypeMeta>,
) {
    data class DbTypeMeta(
        val name: String,
        val fieldNames: List<String>,
        val entryNames: List<String>,
        val description: String,
        /** Per-field editor type codes (0 = number-like, 1 = string-like, …). */
        val fieldTypes: IntArray,
    )

    companion object {
        fun parse(data: ByteArray, v3: Boolean = true): DataBaseProject {
            val reader = BoundedReader(data)
            val count = reader.readCount("database project type")
            val types = ArrayList<DbTypeMeta>(count)
            repeat(count) { types.add(readType(reader, v3)) }
            if (reader.remaining != 0) {
                throw WolfFormatException(
                    "Database project has ${reader.remaining} trailing byte(s)",
                )
            }
            return DataBaseProject(types)
        }

        private fun readType(reader: BoundedReader, v3: Boolean): DbTypeMeta {
            val name = reader.readString(v3)
            val fieldCount = reader.readCount("database project field")
            val fieldNames = List(fieldCount) { reader.readString(v3) }
            val entryCount = reader.readCount("database project entry")
            val entryNames = List(entryCount) { reader.readString(v3) }
            val description = reader.readString(v3)

            val fieldTypeListSize = reader.readU4().toInt()
            if (fieldTypeListSize < fieldCount || fieldTypeListSize > BoundedReader.Limits.DEFAULT.maxCount) {
                throw WolfFormatException("Invalid field-type list size $fieldTypeListSize")
            }
            val fieldTypes = IntArray(fieldCount) { reader.readU1() }
            if (fieldTypeListSize > fieldCount) {
                reader.skip((fieldTypeListSize - fieldCount).toLong(), "field-type padding")
            }

            // unknown1 strings, string-args, int-args, default values — one
            // record per field. Contents are editor-only; skip after reading.
            val unknownCount = reader.readCount("database project field note")
            repeat(unknownCount) { reader.readString(v3) }

            val stringArgsCount = reader.readCount("database project field string-args")
            repeat(stringArgsCount) {
                val n = reader.readCount("field string-arg")
                repeat(n) { reader.readString(v3) }
            }

            val intArgsCount = reader.readCount("database project field int-args")
            repeat(intArgsCount) {
                val n = reader.readCount("field int-arg")
                repeat(n) { reader.readS4() }
            }

            val defaultCount = reader.readCount("database project field default")
            repeat(defaultCount) { reader.readS4() }

            return DbTypeMeta(
                name = name,
                fieldNames = fieldNames,
                entryNames = entryNames,
                description = description,
                fieldTypes = fieldTypes,
            )
        }
    }
}
