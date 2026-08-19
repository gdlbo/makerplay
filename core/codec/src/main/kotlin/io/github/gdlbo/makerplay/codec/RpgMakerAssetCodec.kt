package io.github.gdlbo.makerplay.codec

import java.io.EOFException
import java.io.InputStream
import java.util.UUID

class RpgMakerAssetCodec private constructor(
    private val key: ByteArray,
) : AssetCodec {
    override val id: String = ID
    override val cacheTag: String = "$CACHE_TAG_PREFIX-${UUID.randomUUID()}"

    override fun logicalLength(storedLength: Long): Long {
        if (storedLength < MIN_STORED_LENGTH) throw AssetCodecException("Encrypted asset is truncated")
        return storedLength - HEADER.size
    }

    override fun open(
        source: SeekableAssetSource,
        logicalOffset: Long,
        length: Long,
    ): InputStream {
        try {
            val logicalLength = logicalLength(source.length)
            if (logicalOffset < 0L || length < 0L || logicalOffset > logicalLength - length) {
                throw AssetCodecException("Invalid decoded asset range")
            }
            val header = ByteArray(HEADER.size)
            source.readFully(0L, header)
            if (!header.contentEquals(HEADER)) throw AssetCodecException("Encrypted asset header is invalid")
            return DecodingInputStream(source, key.copyOf(), logicalOffset, length)
        } catch (error: Exception) {
            try {
                source.close()
            } catch (closeError: Exception) {
                error.addSuppressed(closeError)
            }
            if (error is AssetCodecException) throw error
            throw AssetCodecException("Encrypted asset cannot be opened", error)
        }
    }

    companion object {
        const val ID = "rpg-maker-standard"
        private const val CACHE_TAG_PREFIX = "rpgmaker-v1"
        private const val KEY_BYTES = 16
        private const val MIN_STORED_LENGTH = 32L
        private val HEADER = byteArrayOf(
            0x52, 0x50, 0x47, 0x4d, 0x56, 0x00, 0x00, 0x00,
            0x00, 0x03, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
        )

        fun fromHexKey(value: String): RpgMakerAssetCodec {
            require(value.length == KEY_BYTES * 2 && value.all { it.isAsciiHexDigit() }) {
                "RPG Maker encryption key must contain exactly 32 hexadecimal characters"
            }
            val key = ByteArray(KEY_BYTES) { index ->
                value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            return RpgMakerAssetCodec(key)
        }

        fun recoverHexKeyFromEncryptedPngHeader(storedHeader: ByteArray): String {
            require(storedHeader.size >= HEADER.size + PNG_HEADER.size) {
                "Encrypted PNG header is truncated"
            }
            require(storedHeader.copyOfRange(0, HEADER.size).contentEquals(HEADER)) {
                "Encrypted asset header is invalid"
            }
            return buildString(KEY_BYTES * 2) {
                repeat(KEY_BYTES) { index ->
                    val value = (
                            storedHeader[HEADER.size + index].toInt() xor
                                    PNG_HEADER[index].toInt()
                            ) and 0xff
                    append(HEX[value ushr 4])
                    append(HEX[value and 0x0f])
                }
            }
        }

        private fun Char.isAsciiHexDigit(): Boolean =
            this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        private const val HEX = "0123456789abcdef"
        private val PNG_HEADER = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
        )
    }
}

private class DecodingInputStream(
    private val source: SeekableAssetSource,
    private val key: ByteArray,
    logicalOffset: Long,
    length: Long,
) : InputStream() {
    private var position = logicalOffset
    private var remaining = length
    private var closed = false

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "Stream is closed" }
        if (offset < 0 || length < 0 || length > buffer.size - offset) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val requested = minOf(length.toLong(), remaining).toInt()
        try {
            val read = source.readAt(HEADER_LENGTH + position, buffer, offset, requested)
            if (read <= 0) throw EOFException("Encrypted asset ended before its indexed length")
            val xorEnd = minOf(read.toLong(), (XOR_LENGTH - position).coerceAtLeast(0L)).toInt()
            for (index in 0 until xorEnd) {
                buffer[offset + index] = buffer[offset + index] xor key[(position + index).toInt()]
            }
            position += read
            remaining -= read
            return read
        } catch (error: Exception) {
            try {
                close()
            } catch (closeError: Exception) {
                error.addSuppressed(closeError)
            }
            throw error
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            key.fill(0)
            source.close()
        }
    }

    private infix fun Byte.xor(other: Byte): Byte = (toInt() xor other.toInt()).toByte()

    private companion object {
        const val HEADER_LENGTH = 16L
        const val XOR_LENGTH = 16L
    }
}

private fun SeekableAssetSource.readFully(position: Long, destination: ByteArray) {
    var total = 0
    while (total < destination.size) {
        val read = readAt(position + total, destination, total, destination.size - total)
        if (read <= 0) throw EOFException("Encrypted asset header is truncated")
        total += read
    }
}