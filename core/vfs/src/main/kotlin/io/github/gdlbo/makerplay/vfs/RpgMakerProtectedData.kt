package io.github.gdlbo.makerplay.vfs

object RpgMakerProtectedData {
    fun isCryptoJsOpenSslBase64(bytes: ByteArray): Boolean {
        var start = 0
        var end = bytes.size
        while (start < end && bytes[start].isAsciiWhitespace()) start++
        while (end > start && bytes[end - 1].isAsciiWhitespace()) end--
        val length = end - start
        if (length < MIN_CONTAINER_CHARS || length % 4 != 0) return false
        if (!bytes.matchesAt(start, OPENSSL_SALTED_BASE64_PREFIX)) return false

        var padding = 0
        for (index in start until end) {
            val value = bytes[index].toInt() and 0xff
            if (value == '='.code) {
                padding++
                if (padding > 2 || index < end - 2) return false
            } else {
                if (padding != 0 || !value.isBase64Character()) return false
            }
        }
        return true
    }

    private fun ByteArray.matchesAt(offset: Int, expected: ByteArray): Boolean {
        if (size - offset < expected.size) return false
        return expected.indices.all { index -> this[offset + index] == expected[index] }
    }

    private fun Byte.isAsciiWhitespace(): Boolean = when (toInt() and 0xff) {
        ' '.code, '\t'.code, '\r'.code, '\n'.code -> true
        else -> false
    }

    private fun Int.isBase64Character(): Boolean =
        this in 'A'.code..'Z'.code ||
                this in 'a'.code..'z'.code ||
                this in '0'.code..'9'.code ||
                this == '+'.code || this == '/'.code

    private const val MIN_CONTAINER_CHARS = 44
    private val OPENSSL_SALTED_BASE64_PREFIX = "U2FsdGVkX18".toByteArray(Charsets.US_ASCII)
}