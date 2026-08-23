package io.github.gdlbo.makerplay.wolfformat

/**
 * Parser for `CommonEvent.dat`: globally defined events with run conditions.
 *
 * Editor-only metadata (argument specifications, cself names, return values)
 * is validated and skipped so malformed files still fail loudly here while
 * keeping only interpreter-relevant fields in the model.
 */
data class CommonEventDat(
    val v3: Boolean,
    val events: List<CommonEvent>,
) {
    data class CommonEvent(
        val id: Int,
        /** 0 call-only, 1 auto start, 2 parallel, 3 parallel always. */
        val runCondition: Int,
        val conditionVariableRaw: Int,
        val conditionValue: Int,
        val title: String,
        val commands: List<EventCommand>,
    )

    companion object {
        private const val EVENT_HEADER = 0x8E
        private const val SEPARATOR_ARGS = 0x8F
        private const val SEPARATOR_COLOR = 0x90
        private const val SEPARATOR_CSELF = 0x91
        private const val SEPARATOR_RETURN = 0x92

        fun parse(data: ByteArray): CommonEventDat {
            val reader = BoundedReader(data)
            val v3 = WolfContainer.readStandardDatHeader(reader, tagName = 'C')
            val count = reader.readCount("common event")
            val events = ArrayList<CommonEvent>(count)
            repeat(count) { events.add(readEvent(reader, v3)) }
            WolfContainer.readFooter(reader, WolfContainer.COMMON_EVENT_FOOTERS)
            return CommonEventDat(v3, events)
        }

        private fun readEvent(reader: BoundedReader, v3: Boolean): CommonEvent {
            if (reader.readU1() != EVENT_HEADER) {
                throw WolfFormatException("Common event missing header byte")
            }
            val id = reader.readS4()
            // One byte of 4-bit fields: operator in the high nibble, run
            // condition in the low nibble.
            val conditionBits = reader.readU1()
            val runCondition = conditionBits and 0xF
            if (runCondition !in 0..3) {
                throw WolfFormatException("Common event $id has invalid run condition $runCondition")
            }
            val conditionVariable = reader.readU4().toInt()
            val conditionValue = reader.readU4().toInt()
            val argumentNumberCount = reader.readU1()
            val argumentStringCount = reader.readU1()
            if (argumentNumberCount > 64 || argumentStringCount > 64) {
                throw WolfFormatException("Common event $id has too many arguments")
            }
            val title = reader.readString(v3)
            val commands = EventCommand.parseList(reader, v3)

            // Trailing editor metadata block.
            reader.skip(5, "common event unknown block") // contents [1,0,0,0,0]
            reader.readString(v3) // memo
            requireSeparator(reader, SEPARATOR_ARGS, "arguments", id)

            readStringArray(reader, v3) // argument names
            val modePageCount = reader.readCount("special specification mode page")
            repeat(modePageCount) { reader.readU1() } // special specification mode
            val optionStringPageCount = reader.readCount("option string page")
            repeat(optionStringPageCount) { readStringArray(reader, v3) }
            val optionValuePageCount = reader.readCount("option value page")
            repeat(optionValuePageCount) { readU4Array(reader) }
            readS4Array(reader) // argument number default values

            requireSeparator(reader, SEPARATOR_COLOR, "color", id)
            reader.readU4() // color
            repeat(100) { reader.readString(v3) } // cself variable names
            requireSeparator(reader, SEPARATOR_CSELF, "cself", id)
            reader.skip(5, "common event unknown tail")
            requireSeparator(reader, SEPARATOR_RETURN, "return", id)
            reader.readString(v3) // return name
            reader.readU4() // return value id
            requireSeparator(reader, SEPARATOR_RETURN, "return end", id)

            return CommonEvent(
                id = id,
                runCondition = runCondition,
                conditionVariableRaw = conditionVariable,
                conditionValue = conditionValue,
                title = title,
                commands = commands,
            )
        }

        private fun requireSeparator(reader: BoundedReader, expected: Int, what: String, eventId: Int) {
            val actual = reader.readU1()
            if (actual != expected) {
                throw WolfFormatException(
                    "Common event $eventId missing '$what' separator " +
                        "(expected 0x${expected.toString(16)}, got 0x${actual.toString(16)})",
                )
            }
        }

        private fun readStringArray(reader: BoundedReader, v3: Boolean): List<String> {
            val count = reader.readCount("string array")
            val strings = ArrayList<String>(count)
            repeat(count) { strings.add(reader.readString(v3)) }
            return strings
        }

        private fun readU4Array(reader: BoundedReader): List<Int> {
            val count = reader.readCount("u4 array")
            val values = ArrayList<Int>(count)
            repeat(count) { values.add(reader.readS4()) }
            return values
        }

        private fun readS4Array(reader: BoundedReader): List<Int> {
            val count = reader.readCount("s4 array")
            val values = ArrayList<Int>(count)
            repeat(count) { values.add(reader.readS4()) }
            return values
        }
    }
}
