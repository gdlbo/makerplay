package io.github.gdlbo.makerplay.wolfformat

/**
 * Minimal LZ4 block decompressor used by WOLF v3.5 containers (CommonEvent.dat
 * and v3.5 `.mps` maps pack their body with raw LZ4 blocks).
 */
internal object WolfLz4 {
    fun decompress(input: ByteArray, outputSize: Int): ByteArray {
        val output = ByteArray(outputSize)
        var src = 0
        var dst = 0
        while (src < input.size) {
            val token = input[src++].toInt() and 0xFF
            var literalLength = token ushr 4
            if (literalLength == 15) {
                var length: Int
                do {
                    if (src >= input.size) throw WolfFormatException("Truncated LZ4 literals")
                    length = input[src++].toInt() and 0xFF
                    literalLength += length
                } while (length == 255)
            }
            if (src + literalLength > input.size || dst + literalLength > output.size) {
                throw WolfFormatException("Invalid LZ4 literal block")
            }
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
                do {
                    if (src >= input.size) throw WolfFormatException("Truncated LZ4 match")
                    length = input[src++].toInt() and 0xFF
                    matchLength += length
                } while (length == 255)
            }
            if (dst + matchLength > output.size) throw WolfFormatException("Invalid LZ4 match block")
            repeat(matchLength) { output[dst + it] = output[dst - offset + it] }
            dst += matchLength
        }
        if (dst != output.size) throw WolfFormatException("LZ4 payload size mismatch")
        return output
    }
}
