package io.github.gdlbo.makerplay.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException

class RpgMakerAssetCodecTest {
    private val codec = RpgMakerAssetCodec.fromHexKey(TEST_KEY)

    @Test
    fun decodesKnownVectorWithoutMaterializingTheWholeAsset() {
        val source = MemorySource(KNOWN_STORED_ASSET)

        val decoded = codec.open(source, 0, PLAINTEXT.size.toLong()).use { it.readBytes() }

        assertArrayEquals(PLAINTEXT, decoded)
        assertTrue(source.closed)
        assertTrue(source.maxRequestedRead <= PLAINTEXT.size)
    }

    @Test
    fun decodesRangesOnBothSidesOfTheXorBoundary() {
        listOf(0L to 1L, 4L to 7L, 12L to 9L, 16L to 16L, 31L to 1L).forEach { (start, length) ->
            val actual =
                codec.open(MemorySource(encrypted(PLAINTEXT)), start, length).use { it.readBytes() }
            assertArrayEquals(
                PLAINTEXT.copyOfRange(start.toInt(), (start + length).toInt()),
                actual
            )
        }
    }

    @Test
    fun validatesEveryHeaderByteEvenForLateRanges() {
        listOf(0, 10, 15).forEach { headerIndex ->
            val invalid = encrypted(PLAINTEXT).also {
                it[headerIndex] = (it[headerIndex].toInt() xor 1).toByte()
            }
            val source = MemorySource(invalid)
            assertThrows(AssetCodecException::class.java) {
                codec.open(source, 16, 1)
            }
            assertTrue(source.closed)
        }
    }

    @Test
    fun rejectsMalformedKeysWithoutIncludingTheirValue() {
        listOf("", "0".repeat(31), "0".repeat(33), "0".repeat(31) + "x", " " + "0".repeat(31))
            .forEach { invalid ->
                val error = assertThrows(IllegalArgumentException::class.java) {
                    RpgMakerAssetCodec.fromHexKey(invalid)
                }
                assertTrue(error.message.orEmpty().contains("32 hexadecimal"))
                assertTrue(!error.message.orEmpty().contains(invalid) || invalid.isEmpty())
            }
        RpgMakerAssetCodec.fromHexKey(TEST_KEY.uppercase())
    }

    @Test
    fun rejectsTruncatedStoredAssetsAndPrematureEof() {
        assertThrows(AssetCodecException::class.java) { codec.logicalLength(31) }
        val stored = encrypted(PLAINTEXT)
        val truncated = MemorySource(stored.copyOf(stored.size - 1), stored.size.toLong())
        val stream = codec.open(truncated, 0, PLAINTEXT.size.toLong())

        assertThrows(EOFException::class.java) { stream.use { it.readBytes() } }
        assertTrue(truncated.closed)
    }

    @Test
    fun registryRejectsDuplicateIds() {
        assertThrows(IllegalArgumentException::class.java) {
            AssetCodecRegistry.of(codec, RpgMakerAssetCodec.fromHexKey(TEST_KEY))
        }
    }

    @Test
    fun largeAssetsAreReadWithCallerBoundedBuffers() {
        val plaintext = ByteArray(1024 * 1024) { it.toByte() }
        val source = MemorySource(encrypted(plaintext))
        val stream = codec.open(source, 0, plaintext.size.toLong())
        val buffer = ByteArray(4096)
        var total = 0
        stream.use {
            while (true) {
                val read = it.read(buffer)
                if (read == -1) break
                total += read
            }
        }

        assertEquals(plaintext.size, total)
        assertTrue(source.maxRequestedRead <= buffer.size)
        assertTrue(source.closed)
    }

    private class MemorySource(
        private val bytes: ByteArray,
        override val length: Long = bytes.size.toLong(),
    ) : SeekableAssetSource {
        var closed = false
        var maxRequestedRead = 0

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            maxRequestedRead = maxOf(maxRequestedRead, length)
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position.toInt())
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val TEST_KEY = "000102030405060708090a0b0c0d0e0f"
        val HEADER = hex("5250474d560000000003010000000000")
        val PLAINTEXT = hex("89504e470d0a1a0a0000000d494844520102030405060708090a0b0c0d0e0f10")
        val KNOWN_STORED_ASSET = hex(
            "5250474d560000000003010000000000" +
                    "89514c44090f1c0d08090a0645454a5d0102030405060708090a0b0c0d0e0f10",
        )

        fun encrypted(plain: ByteArray): ByteArray {
            val key = hex(TEST_KEY)
            val body = plain.copyOf()
            repeat(16) { body[it] = (body[it].toInt() xor key[it].toInt()).toByte() }
            return HEADER + body
        }

        fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
