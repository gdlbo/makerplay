package io.github.gdlbo.makerplay.runtime.webview

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * `zlib` support for the Node compatibility layer.
 *
 * Backed by the platform zlib (the same native library Node and the game engine use), so no extra
 * payload is shipped. Every result is bounded to protect the WebView from decompression bombs.
 */
internal object NodeZlibSupport {
    /** Matches the script-side buffer ceiling so a decoded result can always be allocated. */
    const val MAX_OUTPUT_BYTES = 64 * 1024 * 1024
    private const val CHUNK_BYTES = 32 * 1024

    fun transform(format: String, level: Int, payload: ByteArray): ByteArray = try {
        when (format) {
            "deflate" -> deflate(payload, level, raw = false)
            "deflateRaw" -> deflate(payload, level, raw = true)
            "gzip" -> gzip(payload, level)
            "inflate" -> inflate(payload, raw = false)
            "inflateRaw" -> inflate(payload, raw = true)
            "gunzip" -> gunzip(payload)
            else -> throw ProtocolFailure("invalid")
        }
    } catch (error: ProtocolFailure) {
        throw error
    } catch (_: Exception) {
        // Node reports malformed streams as Z_DATA_ERROR.
        throw ProtocolFailure("zdata")
    }

    private fun deflate(payload: ByteArray, level: Int, raw: Boolean): ByteArray {
        val deflater = Deflater(effectiveLevel(level), raw)
        val output = BoundedOutputStream(MAX_OUTPUT_BYTES)
        try {
            DeflaterOutputStream(output, deflater, CHUNK_BYTES).use { it.write(payload) }
        } finally {
            deflater.end()
        }
        return output.toByteArray()
    }

    private fun gzip(payload: ByteArray, level: Int): ByteArray {
        val output = BoundedOutputStream(MAX_OUTPUT_BYTES)
        GzipOutput(output, effectiveLevel(level)).use { it.write(payload) }
        return output.toByteArray()
    }

    private fun inflate(payload: ByteArray, raw: Boolean): ByteArray {
        val inflater = Inflater(raw)
        val output = BoundedOutputStream(MAX_OUTPUT_BYTES)
        try {
            InflaterInputStream(ByteArrayInputStream(payload), inflater, CHUNK_BYTES).use {
                it.copyTo(output, CHUNK_BYTES)
            }
        } finally {
            inflater.end()
        }
        return output.toByteArray()
    }

    private fun gunzip(payload: ByteArray): ByteArray {
        val output = BoundedOutputStream(MAX_OUTPUT_BYTES)
        GZIPInputStream(ByteArrayInputStream(payload), CHUNK_BYTES).use {
            it.copyTo(output, CHUNK_BYTES)
        }
        return output.toByteArray()
    }

    private fun effectiveLevel(level: Int): Int =
        if (level in Deflater.DEFAULT_COMPRESSION..Deflater.BEST_COMPRESSION) {
            level
        } else {
            Deflater.DEFAULT_COMPRESSION
        }

    private class GzipOutput(stream: OutputStream, level: Int) : GZIPOutputStream(stream, CHUNK_BYTES) {
        init {
            if (level != Deflater.DEFAULT_COMPRESSION) def.setLevel(level)
        }
    }

    private class BoundedOutputStream(private val limit: Int) : ByteArrayOutputStream() {
        override fun write(value: Int) {
            requireCapacity(1)
            super.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            requireCapacity(length)
            super.write(buffer, offset, length)
        }

        private fun requireCapacity(length: Int) {
            if (size() + length > limit) throw ProtocolFailure("zlimit")
        }
    }
}
