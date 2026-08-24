package io.github.gdlbo.makerplay.wolfformat

/** Decompresses aPLib streams used by packed game deployments. */
object AplibDecompressor {

    /**
     * Decompresses aPLib data. Handles an optional "AP32" container header.
     */
    fun decompress(data: ByteArray): ByteArray {
        var offset = 0
        if (data.size >= 24 &&
            data[0] == 'A'.code.toByte() && data[1] == 'P'.code.toByte() &&
            data[2] == '3'.code.toByte() && data[3] == '2'.code.toByte()
        ) {
            val headerSize = readU32(data, 4).toInt()
            offset = headerSize
        }
        return Depacker(data, offset).depack()
    }

    private class Depacker(private val source: ByteArray, start: Int) {
        private var srcPos = start
        private val dst = mutableListOf<Byte>()
        private var tag = 0
        private var bitCount = 0
        private var r0 = -1
        private var lwm = 0

        private fun getBit(): Int {
            bitCount--
            if (bitCount < 0) {
                if (srcPos >= source.size) throw TruncatedStream()
                tag = source[srcPos++].toInt() and 0xFF
                bitCount = 7
            }
            val bit = (tag ushr 7) and 1
            tag = (tag shl 1) and 0xFF
            return bit
        }

        private fun getGamma(): Int {
            var result = 1
            while (true) {
                result = (result shl 1) + getBit()
                if (getBit() == 0) break
            }
            return result
        }

        /** Returns a byte or -1 on EOF (reference treats EOF as end-of-stream). */
        private fun readByte(): Int =
            if (srcPos >= source.size) -1 else source[srcPos++].toInt() and 0xFF

        fun depack(): ByteArray {
            // First byte verbatim
            val first = readByte()
            if (first < 0) return ByteArray(0)
            dst.add(first.toByte())

            while (true) {
                try {
                    if (getBit() == 1) {
                        if (getBit() == 1) {
                            if (getBit() == 1) {
                                // Short run of 4 bits offset
                                var offs = 0
                                repeat(4) { offs = (offs shl 1) + getBit() }
                                if (offs != 0) dst.add(dst[dst.size - offs]) else dst.add(0)
                                lwm = 0
                            } else {
                                // Immediate byte-encoded match; offs==0 terminates
                                val b = readByte()
                                if (b < 0) break
                                var offs = b
                                val length = 2 + (offs and 1)
                                offs = offs shr 1
                                if (offs != 0) repeat(length) { dst.add(dst[dst.size - offs]) } else break
                                r0 = offs
                                lwm = 1
                            }
                        } else {
                            val gamma = getGamma()
                            if (lwm == 0 && gamma == 2) {
                                // Repeat last offset
                                val offs = r0
                                val length = getGamma()
                                repeat(length) { dst.add(dst[dst.size - offs]) }
                            } else {
                                var offs = if (lwm == 0) gamma - 3 else gamma - 2
                                val low = readByte()
                                if (low < 0) break
                                offs = (offs shl 8) + low
                                var length = getGamma()
                                if (offs >= 32000) length++
                                if (offs >= 1280) length++
                                if (offs < 128) length += 2
                                repeat(length) { dst.add(dst[dst.size - offs]) }
                                r0 = offs
                            }
                            lwm = 1
                        }
                    } else {
                        // Literal byte
                        val b = readByte()
                        if (b < 0) break
                        dst.add(b.toByte())
                        lwm = 0
                    }
                } catch (e: TruncatedStream) {
                    break
                } catch (e: IndexOutOfBoundsException) {
                    break
                }
            }
            return dst.toByteArray()
        }
    }

    private class TruncatedStream : Exception()

    private fun readU32(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)
}
