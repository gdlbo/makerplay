package io.github.gdlbo.makerplay.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class SaveCodecTest {
    @Test
    fun mvMatchesCanonicalLzStringBase64Vector() {
        val codec = MvLzStringSaveCodec()
        val plain = "Hello world".toByteArray(StandardCharsets.UTF_8)
        val encoded = "BIUwNmD2AEDukCcwBMg=".toByteArray(StandardCharsets.US_ASCII)

        assertEquals(
            String(encoded, StandardCharsets.US_ASCII),
            String(codec.encode(plain), StandardCharsets.US_ASCII),
        )
        assertArrayEquals(plain, codec.decode(encoded))
    }

    @Test
    fun mvMatchesCanonicalEmptyVectorAndRoundTripsUnicodeJson() {
        val codec = MvLzStringSaveCodec()
        assertArrayEquals(
            "Q===".toByteArray(StandardCharsets.US_ASCII),
            codec.encode(byteArrayOf())
        )
        assertArrayEquals(
            byteArrayOf(),
            codec.decode("Q===".toByteArray(StandardCharsets.US_ASCII))
        )

        val json = "{\"name\":\"Save \\u0414\\u0430\\u043d\\u043d\\u044b\\u0435\",\"gold\":1234}"
            .replace("\\u0414", "\u0414")
            .replace("\\u0430", "\u0430")
            .replace("\\u043d", "\u043d")
            .replace("\\u044b", "\u044b")
            .replace("\\u0435", "\u0435")
            .toByteArray(StandardCharsets.UTF_8)
        assertArrayEquals(json, codec.decode(codec.encode(json)))
    }

    @Test
    fun mvRejectsMalformedTransportTextAndNonCanonicalData() {
        val codec = MvLzStringSaveCodec()
        listOf("", "Q", "Q===A===", "Q===\n", "====").forEach { invalid ->
            assertThrows(SaveCodecException::class.java) {
                codec.decode(invalid.toByteArray(StandardCharsets.US_ASCII))
            }
        }
        assertThrows(SaveCodecException::class.java) {
            codec.decode(
                byteArrayOf(
                    0xff.toByte(),
                    0x00
                )
            )
        }
        assertThrows(SaveCodecException::class.java) {
            codec.decode("BIUwNmD2AEDukCcwBMh=".toByteArray(StandardCharsets.US_ASCII))
        }
    }

    @Test
    fun mvRejectsMalformedUtf8AndBothLimitDirections() {
        val codec = MvLzStringSaveCodec(maxEncodedBytes = 20, maxDecodedBytes = 12)
        assertThrows(SaveCodecException::class.java) {
            codec.encode(
                byteArrayOf(
                    0xc3.toByte(),
                    0x28
                )
            )
        }
        assertThrows(SaveCodecException::class.java) { codec.encode(ByteArray(13)) }
        assertThrows(SaveCodecException::class.java) { codec.decode(ByteArray(21) { 'A'.code.toByte() }) }
        assertThrows(SaveCodecException::class.java) {
            MvLzStringSaveCodec(maxEncodedBytes = 19, maxDecodedBytes = 11)
                .encode("Hello world".toByteArray(StandardCharsets.UTF_8))
        }
        assertThrows(SaveCodecException::class.java) {
            MvLzStringSaveCodec(maxEncodedBytes = 64, maxDecodedBytes = 10)
                .decode("BIUwNmD2AEDukCcwBMg=".toByteArray(StandardCharsets.US_ASCII))
        }
    }

    @Test
    fun mzMatchesIndependentZlibLevelOneVector() {
        val codec = MzPakoSaveCodec()
        val plain = "Hello world!".toByteArray(StandardCharsets.UTF_8)
        val encoded = hex("7801f348cdc9c95728cf2fca495104001d09045e")

        assertArrayEquals(encoded, codec.encode(plain))
        assertArrayEquals(plain, codec.decode(encoded))
    }

    @Test
    fun mzRejectsTruncatedInvalidTrailingAndOversizedStreams() {
        val vector = hex("7801f348cdc9c95728cf2fca495104001d09045e")
        val codec = MzPakoSaveCodec(maxEncodedBytes = 64, maxDecodedBytes = 12)
        assertThrows(SaveCodecException::class.java) { codec.decode(vector.copyOf(vector.size - 1)) }
        assertThrows(SaveCodecException::class.java) { codec.decode(byteArrayOf(1, 2, 3, 4)) }
        assertThrows(SaveCodecException::class.java) { codec.decode(vector + 0) }
        assertThrows(SaveCodecException::class.java) { codec.decode(ByteArray(65)) }
        assertThrows(SaveCodecException::class.java) {
            MzPakoSaveCodec(maxEncodedBytes = 64, maxDecodedBytes = 11).decode(vector)
        }
        assertThrows(SaveCodecException::class.java) {
            MzPakoSaveCodec(
                maxEncodedBytes = 8,
                maxDecodedBytes = 64
            ).encode(ByteArray(64) { it.toByte() })
        }
    }

    @Test
    fun opaqueCodecIsBoundedAndReturnsDefensiveCopies() {
        val codec = OpaquePluginSaveCodec(maxBytes = 4)
        val source = byteArrayOf(0, 1, 0xfe.toByte(), 0xff.toByte())
        val encoded = codec.encode(source)
        val decoded = codec.decode(encoded)

        assertArrayEquals(source, encoded)
        assertArrayEquals(source, decoded)
        assertNotSame(source, encoded)
        assertNotSame(encoded, decoded)
        assertThrows(SaveCodecException::class.java) { codec.encode(ByteArray(5)) }
        assertThrows(SaveCodecException::class.java) { codec.decode(ByteArray(5)) }
    }

    @Test
    fun codecsExposeStableDistinctIdsAndValidateLimits() {
        val ids = setOf(
            MvLzStringSaveCodec().id,
            MzPakoSaveCodec().id,
            OpaquePluginSaveCodec().id,
        )
        assertEquals(3, ids.size)
        assertTrue(ids.all { it.matches(Regex("[a-z0-9][a-z0-9._-]+")) })
        assertThrows(IllegalArgumentException::class.java) { MvLzStringSaveCodec(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { MzPakoSaveCodec(1, 0) }
        assertThrows(IllegalArgumentException::class.java) { OpaquePluginSaveCodec(0) }
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
