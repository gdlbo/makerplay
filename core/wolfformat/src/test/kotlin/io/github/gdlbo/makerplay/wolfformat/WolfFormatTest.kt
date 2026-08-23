package io.github.gdlbo.makerplay.wolfformat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Builds synthetic WOLF binary fixtures byte-by-byte so parser behavior and
 * malformed-input rejection are verified without shipping game assets.
 */
object WolfFixture {

    fun coreMagic(): ByteArray = byteArrayOf(0, 'W'.code.toByte(), 0, 0, 'O'.code.toByte(), 'L'.code.toByte())

    fun standardHeader(v3: Boolean, tag: Char = 'M'): ByteArray =
        ByteArrayOutputStream().let { out ->
            out.write(coreMagic())
            out.write(if (v3) 0x55 else 0x00)
            out.write(byteArrayOf('F'.code.toByte(), tag.code.toByte(), 0))
            out.write(0xC2) // per-file revision byte
            out.toByteArray()
        }

    /** Length-prefixed WOLF string with trailing NUL, as written by the editor. */
    fun wolfString(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8) + 0
        val out = ByteArrayOutputStream()
        writeU4(out, bytes.size.toLong())
        out.write(bytes)
        return out.toByteArray()
    }

    fun sjisString(text: String): ByteArray {
        val charset = runCatching { java.nio.charset.Charset.forName("windows-31j") }
            .getOrElse { Charsets.ISO_8859_1 }
        val bytes = text.toByteArray(charset) + 0
        val out = ByteArrayOutputStream()
        writeU4(out, bytes.size.toLong())
        out.write(bytes)
        return out.toByteArray()
    }

    fun u4(value: Long): ByteArray = ByteArrayOutputStream().also { writeU4(it, value) }.toByteArray()

    fun s4(value: Int): ByteArray = u4(value.toLong() and 0xFFFFFFFFL)

    fun writeU4(out: ByteArrayOutputStream, value: Long) {
        out.write((value and 0xFF).toInt())
        out.write(((value shr 8) and 0xFF).toInt())
        out.write(((value shr 16) and 0xFF).toInt())
        out.write(((value shr 24) and 0xFF).toInt())
    }

    /** Minimal v3 Game.dat with the given title/encryption key. */
    fun gameDat(
        title: String = "Test Game",
        encryptionKey: String = "KEY",
        width: Int = 800,
        height: Int = 600,
        tileSize: Int = 40,
        fps: Int = 60,
        v3: Boolean = true,
    ): ByteArray {
        val u8Settings = byteArrayOf(
            tileSize.toByte(),
            4, 4, // directions image/move
            0, // guruguru
            fps.toByte(),
            1, 1, 2,
            3, // animation patterns
            0, 0,
            16, 16, 16, 0, // text paddings
            0, // anti aliasing
            6, 6, // move speeds
            1, // language japanese
            0, 0, 0, 0, 0, // image scale, inactive, system language...
        )
        val strings = if (v3) {
            listOf(title, "SERIAL", encryptionKey, "font.fnt", "", "", "", "hero.png", "", "", "", "", "")
        } else {
            listOf(title, "SERIAL", encryptionKey, "font.fnt", "", "", "", "hero.png", "")
        }
        val stringBlock = ByteArrayOutputStream().apply { strings.forEach { write(wolfString(it)) } }.toByteArray()

        val u16 = ShortArray(19)
        u16[16] = width.toShort()
        u16[17] = height.toShort()
        u16[18] = 0x0300

        val out = ByteArrayOutputStream()
        out.write(coreMagic())
        out.write(0)
        out.write('F'.code.toByte().toInt())
        out.write('M'.code.toByte().toInt())
        out.write(if (v3) 0x55 else 0x00)
        writeU4(out, u8Settings.size.toLong())
        out.write(u8Settings)
        writeU4(out, stringBlock.size.toLong())
        out.write(stringBlock)
        writeU4(out, 29001L) // static randoms size marker (filesize - pos)
        writeU4(out, 0) // unknown3
        writeU4(out, u16.size.toLong())
        u16.forEach {
            out.write(it.toInt() and 0xFF)
            out.write((it.toInt() shr 8) and 0xFF)
        }
        repeat(28000) { out.write(7) } // static randoms filler
        out.write(0xC3) // footer
        return out.toByteArray()
    }
}

class BoundedReaderTest {

    @Test
    fun readsLittleEndianValues() {
        val reader = BoundedReader(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        assertEquals(1, reader.readU1())
        assertEquals(0x0302, reader.readU2())
        assertEquals(0x07060504L, reader.readU4())
        assertEquals(0x0908, reader.readU2())
        assertEquals(1, reader.remaining)
    }

    @Test(expected = WolfFormatException::class)
    fun truncatedReadThrows() {
        BoundedReader(byteArrayOf(1, 2)).readU4()
    }

    @Test
    fun oversizedStringThrows() {
        val data = WolfFixture.u4(10L * 1024 * 1024) + ByteArray(16)
        val exception = assertThrows(WolfFormatException::class.java) {
            BoundedReader(data).readString(v3 = true)
        }
        assertTrue(exception.message!!.contains("exceeds limit"))
    }

    @Test
    fun decodesUtf8AndStripsTrailingNull() {
        val text = "テスト"
        val data = WolfFixture.wolfString(text)
        assertEquals(text, BoundedReader(data).readString(v3 = true))
    }

    @Test
    fun sliceBoundsSubsequentReads() {
        val reader = BoundedReader(byteArrayOf(4, 0, 0, 0, 2, 2))
        val slice = reader.slice(4, "window")
        assertEquals(4L, slice.readU4())
        assertEquals(2, reader.readU1())
        assertFalse(slice.hasRemainingBytes())
    }

    private fun BoundedReader.hasRemainingBytes(): Boolean = remaining > 0
}

class WolfContainerTest {

    @Test
    fun detectsV2AndV3Headers() {
        assertEquals(
            WolfContainer.VERSION_V2,
            WolfContainer.detectVersionHeader(WolfFixture.standardHeader(v3 = false)),
        )
        assertNull(WolfContainer.detectVersionHeader(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun rejectsUnknownVersionByte() {
        val bad = WolfFixture.coreMagic() + byteArrayOf(0x33.toByte()) +
            byteArrayOf('F'.code.toByte(), 'M'.code.toByte(), 0)
        assertThrows(WolfFormatException::class.java) {
            BoundedReader(bad).let { WolfContainer.readStandardDatHeader(it) }
        }
    }
}

class GameDatTest {

    @Test
    fun parsesSyntheticV3GameDat() {
        val dat = GameDat.parse(
            WolfFixture.gameDat(title = "Colette", encryptionKey = "SECRET", width = 1280, height = 720),
        )
        assertTrue(dat.v3)
        assertEquals("Colette", dat.title)
        assertEquals("SECRET", dat.encryptionKey)
        assertEquals(1280, dat.screenWidth)
        assertEquals(720, dat.screenHeight)
        assertEquals(60, dat.fps)
    }

    @Test
    fun rejectsTruncatedGameDat() {
        val full = WolfFixture.gameDat()
        assertThrows(WolfFormatException::class.java) { GameDat.parse(full.copyOfRange(0, 20)) }
    }

    @Test
    fun rejectsBadTileSize() {
        val broken = WolfFixture.gameDat(tileSize = 24)
        assertThrows(WolfFormatException::class.java) { GameDat.parse(broken) }
    }
}

class WolfDatArchiveTest {

    @Test
    fun parsesRecordStringsAndData() {
        val recordA = WolfFixture.u4(2) + WolfFixture.wolfString("Map001") + WolfFixture.wolfString("森") +
            WolfFixture.s4(42)
        val recordB = WolfFixture.u4(1) + WolfFixture.wolfString("Root") + WolfFixture.s4(-1)
        val blob = WolfFixture.standardHeader(v3 = true) +
            WolfFixture.u4(2) + WolfFixture.u4(recordA.size.toLong()) + recordA +
            WolfFixture.u4(recordB.size.toLong()) + recordB + byteArrayOf(0xC3.toByte())

        val archive = WolfDatArchive.parse(blob)
        assertTrue(archive.v3)
        assertEquals(listOf("Map001", "森"), archive.records[0].strings)
        assertEquals(42, archive.records[0].data.readS4At(0))
        assertEquals(listOf("Root"), archive.records[1].strings)
    }

    @Test
    fun rejectsOversizedRecordCount() {
        val blob = WolfFixture.standardHeader(v3 = true) + WolfFixture.u4(0x7FFFFFFFL)
        assertThrows(WolfFormatException::class.java) { WolfDatArchive.parse(blob) }
    }

    private fun ByteArray.readS4At(offset: Int): Int {
        var v = 0
        for (i in 3 downTo 0) {
            v = (v shl 8) or (this[offset + i].toInt() and 0xFF)
        }
        return v
    }
}

class MapFileTest {

    /** Builds a minimal valid v3 map: 2x2 tiles, no events. */
    private fun minimalMap(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        repeat(10) { out.write(0) }
        out.write("WOLFM".map { it.code.toByte() }.toByteArray())
        out.write(0)
        out.write(0x55) // v3
        out.write(byteArrayOf(0, 0, 0))
        out.write(WolfFixture.u4(0x64))
        out.write(0x66) // revision v3
        out.write(WolfFixture.wolfString("Test Map"))
        out.write(WolfFixture.s4(1)) // tileset id
        out.write(WolfFixture.u4(2)) // width
        out.write(WolfFixture.u4(2)) // height
        out.write(WolfFixture.u4(0)) // events
        repeat(2 * 2 * 3) { out.write(WolfFixture.u4(1)) } // 12 layer pixels
        out.write(0x66) // footer
        return out.toByteArray()
    }

    @Test
    fun parsesMinimalMap() {
        val map = MapFile.parse(minimalMap())
        assertEquals("Test Map", map.title)
        assertEquals(2, map.width)
        assertEquals(2, map.height)
        assertEquals(3, map.layers.size)
        assertEquals(0, map.events.size)
    }

    @Test(expected = WolfFormatException::class)
    fun rejectsTruncatedMapLayers() {
        val full = minimalMap()
        MapFile.parse(full.copyOfRange(0, full.size - 10))
    }

    @Test(expected = WolfFormatException::class)
    fun rejectsBadMagic() {
        val broken = minimalMap().also { it[12] = 'X'.code.toByte() }
        MapFile.parse(broken)
    }
}

class WolfArchiveKeyTest {

    @Test
    fun derivesSevenByteKey() {
        val key = WolfArchiveReader.deriveKey("BasicData")
        assertEquals(7, key.size)
        // Deterministic derivation.
        assertEquals(key.toList(), WolfArchiveReader.deriveKey("BasicData").toList())
    }

    @Test
    fun shortKeyFallsBackToDefault() {
        val key = WolfArchiveReader.deriveKey("")
        assertEquals(7, key.size)
    }

    @Test
    fun decodeRoundTripsAgainstPosition() {
        val key = WolfArchiveReader.deriveKey("TESTKEY")
        val original = "Hello WOLF archive".toByteArray()
        val encoded = original.copyOf()
        WolfArchiveReader.decode(encoded, 123L, key)
        assertFalse(original.contentEquals(encoded))
        WolfArchiveReader.decode(encoded, 123L, key)
        assertTrue(original.contentEquals(encoded))
    }
}
