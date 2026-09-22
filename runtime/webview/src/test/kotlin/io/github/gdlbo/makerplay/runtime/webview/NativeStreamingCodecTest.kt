package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.codec.SeekableAssetSource
import io.github.gdlbo.makerplay.runtime.webview.nativebridge.NativeRpgMakerAssetCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeStreamingCodecTest {
    @Test
    fun `small encrypted ranges read only header and requested bytes`() {
        val header = byteArrayOf(0x52, 0x50, 0x47, 0x4d, 0x56, 0, 0, 0, 0, 3, 1, 0, 0, 0, 0, 0)
        var bytesRead = 0
        var closed = false
        val source = object : SeekableAssetSource {
            override val length = 32L * 1024 * 1024
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                bytesRead += length
                repeat(length) { i ->
                    buffer[offset + i] = if (position + i < header.size) header[(position + i).toInt()] else 7
                }
                return length
            }
            override fun close() { closed = true }
        }
        val codec = NativeRpgMakerAssetCodec("00".repeat(16))
        codec.open(source, 1_000_000, 3).use { stream ->
            assertTrue(stream.readBytes().contentEquals(byteArrayOf(7, 7, 7)))
        }
        assertEquals(19, bytesRead)
        assertTrue(closed)
    }
}
