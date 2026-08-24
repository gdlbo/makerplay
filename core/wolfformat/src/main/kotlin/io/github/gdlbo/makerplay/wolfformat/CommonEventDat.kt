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
            val v35 = (data[10].toInt() and 0xFF) == 0xCC || (data[10].toInt() and 0xFF) == 0x93
            val payload = if (v35) readV35Payload(reader) else reader
            val count = payload.readCount("common event")
            val events = ArrayList<CommonEvent>(count)
            repeat(count) { events.add(readEvent(payload, v3, v35)) }
            if (v35) {
                val footer = payload.readU1()
                if (footer < 0x89) throw WolfFormatException("Invalid v3.5 common event footer 0x${footer.toString(16)}")
            } else {
                WolfContainer.readFooter(payload, WolfContainer.COMMON_EVENT_FOOTERS)
            }
            return CommonEventDat(v3, events)
        }

        private fun readEvent(reader: BoundedReader, v3: Boolean, v35: Boolean): CommonEvent {
            if (reader.readU1() != EVENT_HEADER) {
                throw WolfFormatException("Common event missing header byte")
            }
            val id = reader.readS4()
            if (v35) {
                val conditionRaw = reader.readS4()
                reader.skip(7, "common event condition block")
                val title = reader.readString(v3)
                val commands = EventCommand.parseList(reader, v3, v35)
                reader.readString(v3) // editor name
                reader.readString(v3) // memo
                requireSeparator(reader, SEPARATOR_ARGS, "arguments", id)
                return readEventMetadata(reader, v3, id, conditionRaw, title, commands)
            }
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

        private fun readEventMetadata(
            reader: BoundedReader,
            v3: Boolean,
            id: Int,
            conditionRaw: Int,
            title: String,
            commands: List<EventCommand>,
        ): CommonEvent {
            readStringArray(reader, v3)
            val modePageCount = reader.readCount("special specification mode page")
            repeat(modePageCount) { reader.readU1() }
            val optionStringPageCount = reader.readCount("option string page")
            repeat(optionStringPageCount) { readStringArray(reader, v3) }
            val optionValuePageCount = reader.readCount("option value page")
            repeat(optionValuePageCount) { readU4Array(reader) }
            readS4Array(reader)
            requireSeparator(reader, SEPARATOR_COLOR, "color", id)
            reader.readU4()
            repeat(100) { reader.readString(v3) }
            requireSeparator(reader, SEPARATOR_CSELF, "cself", id)
            reader.readString(v3)
            val returnSeparator = reader.readU1()
            if (returnSeparator == SEPARATOR_RETURN) {
                reader.readString(v3)
                reader.readU4()
                requireSeparator(reader, SEPARATOR_RETURN, "return end", id)
            } else if (returnSeparator != SEPARATOR_CSELF) {
                throw WolfFormatException("Common event $id has invalid return separator")
            }
            return CommonEvent(id, conditionRaw and 0xF, conditionRaw, 0, title, commands)
        }

        private fun readV35Payload(reader: BoundedReader): BoundedReader {
            val decompressedSize = reader.readU4().toInt()
            val compressedSize = reader.readU4().toInt()
            if (decompressedSize < 0 || compressedSize < 0 || compressedSize > reader.remaining) {
                throw WolfFormatException("Invalid v3.5 common event compression header")
            }
            val compressed = reader.readBytes(compressedSize, "v3.5 common event payload")
            return BoundedReader(lz4Decompress(compressed, decompressedSize))
        }

        private fun lz4Decompress(input: ByteArray, outputSize: Int): ByteArray {
            val output = ByteArray(outputSize)
            var src = 0
            var dst = 0
            while (src < input.size) {
                val token = input[src++].toInt() and 0xFF
                var literalLength = token ushr 4
                if (literalLength == 15) {
                    var length: Int
                    do { if (src >= input.size) throw WolfFormatException("Truncated LZ4 literals"); length = input[src++].toInt() and 0xFF; literalLength += length } while (length == 255)
                }
                if (src + literalLength > input.size || dst + literalLength > output.size) throw WolfFormatException("Invalid LZ4 literal block")
                input.copyInto(output, dst, src, src + literalLength)
                src += literalLength
                dst += literalLength
                if (src == input.size) break
                if (src + 2 > input.size) throw WolfFormatException("Truncated LZ4 match offset")
                val offset = (input[src].toInt() and 0xFF) or ((input[src + 1].toInt() and 0xFF) shl 8)
                src += 2
                if (offset == 0 || offset > dst) throw WolfFormatException("Invalid LZ4 match offset")
                var matchLength = (token and 0xF) + 4
                if ((token and 0xF) == 15) {
                    var length: Int
                    do { if (src >= input.size) throw WolfFormatException("Truncated LZ4 match"); length = input[src++].toInt() and 0xFF; matchLength += length } while (length == 255)
                }
                if (dst + matchLength > output.size) throw WolfFormatException("Invalid LZ4 match block")
                repeat(matchLength) { output[dst + it] = output[dst - offset + it] }
                dst += matchLength
            }
            if (dst != output.size) throw WolfFormatException("LZ4 payload size mismatch")
            return output
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
