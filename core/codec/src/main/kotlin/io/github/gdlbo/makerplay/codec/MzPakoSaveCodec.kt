package io.github.gdlbo.makerplay.codec

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

class MzPakoSaveCodec(
    private val maxEncodedBytes: Int = DEFAULT_MAX_BYTES,
    private val maxDecodedBytes: Int = DEFAULT_MAX_BYTES,
) : SaveCodec {
    init {
        require(maxEncodedBytes > 0) { "Encoded save limit must be positive" }
        require(maxDecodedBytes > 0) { "Decoded save limit must be positive" }
    }

    override val id: String = ID

    override fun encode(decoded: ByteArray): ByteArray {
        requireWithinLimit(decoded.size, maxDecodedBytes, "Decoded save")
        val deflater = Deflater(1, false)
        return try {
            deflater.setInput(decoded)
            deflater.finish()
            collect(maxEncodedBytes, "Encoded save") { buffer -> deflater.deflate(buffer) }
                .also {
                    if (!deflater.finished()) throw SaveCodecException("Encoded save exceeds the configured limit")
                }
        } finally {
            deflater.end()
        }
    }

    override fun decode(encoded: ByteArray): ByteArray {
        requireWithinLimit(encoded.size, maxEncodedBytes, "Encoded save")
        val inflater = Inflater(false)
        return try {
            inflater.setInput(encoded)
            val decoded = try {
                collect(maxDecodedBytes, "Decoded save") { buffer -> inflater.inflate(buffer) }
            } catch (error: DataFormatException) {
                throw SaveCodecException("MZ save is not a valid zlib stream", error)
            }
            if (!inflater.finished()) {
                val message = if (inflater.needsDictionary()) {
                    "MZ save requires an unsupported zlib dictionary"
                } else {
                    "MZ save is truncated"
                }
                throw SaveCodecException(message)
            }
            if (inflater.remaining != 0) throw SaveCodecException("MZ save has trailing data")
            decoded
        } finally {
            inflater.end()
        }
    }

    private inline fun collect(
        limit: Int,
        description: String,
        read: (ByteArray) -> Int,
    ): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, BUFFER_SIZE))
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val count = read(buffer)
            if (count == 0) break
            if (output.size() > limit - count) throw SaveCodecException("$description exceeds the configured limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        const val ID = "mz-pako-zlib-level-1"
        const val DEFAULT_MAX_BYTES = 16 * 1024 * 1024
        private const val BUFFER_SIZE = 8 * 1024
    }
}