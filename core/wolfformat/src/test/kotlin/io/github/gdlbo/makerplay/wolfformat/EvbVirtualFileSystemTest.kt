package io.github.gdlbo.makerplay.wolfformat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EvbVirtualFileSystemTest {

    // Known compressed-stream test vector
    private val aplibVector = byteArrayOf(
        0x54, 0x00, 0x68, 0x65, 0x20, 0x71, 0x75, 0x69, 0x63, 0x6B, 0xEC.toByte(),
        0x62, 0x0E.toByte(), 0x72, 0x6F, 0x77, 0x6E, 0xCE.toByte(), 0x66, 0xAE.toByte(),
        0x78, 0x80.toByte(), 0x6A, 0x75, 0x6D, 0x70, 0x73, 0xED.toByte(), 0xE4.toByte(),
        0x76, 0x65, 0x75, 0x72, 0x60, 0x74, 0x3F, 0x6C, 0x61, 0x7A, 0x79, 0xEA.toByte(),
        0x64, 0xFE.toByte(), 0x67, 0xC0.toByte(), 0x00,
    )
    private val aplibExpected =
        "The quick brown fox jumps over the lazy dog".toByteArray(Charsets.US_ASCII)

    @Test
    fun `aplib decompresses known vector`() {
        assertArrayEquals(aplibExpected, AplibDecompressor.decompress(aplibVector))
    }

    @Test
    fun `aplib handles AP32 container header`() {
        val payload = aplibVector
        val header = ByteArray(20)
        "AP32".toByteArray().copyInto(header, 0)
        writeU32(header, 4, 20L)                   // header_size
        writeU32(header, 8, payload.size.toLong()) // packed_size
        val container = header + payload
        assertArrayEquals(aplibExpected, AplibDecompressor.decompress(container))
    }

    @Test
    fun `lists and extracts stored and compressed entries`() {
        val helloContent = "hello evb".toByteArray()
        val image = buildEvbImage(
            listOf(
                FakeFile("hello.txt", helloContent),
                FakeFile("packed.bin", aplibVector, originalSize = aplibExpected.size),
            ),
        )
        val entries = EvbVirtualFileSystem.fromMemory(image).entries()

        assertEquals(listOf("hello.txt", "packed.bin"), entries.map { it.path })
        assertEquals(helloContent.size, entries[0].originalSize)
        assertEquals(entries[0].originalSize, entries[0].storedSize)
        assertEquals(aplibVector.size, entries[1].storedSize)
        assertEquals(aplibExpected.size, entries[1].originalSize)
        assertEquals(true, entries[1].isCompressed)

        val vfs = EvbVirtualFileSystem.fromMemory(image)
        assertArrayEquals(helloContent, vfs.extract(entries[0]))
        assertArrayEquals(aplibExpected, vfs.extract(entries[1]))
    }

    @Test
    fun `extractMatching streams only matching paths`() {
        val image = buildEvbImage(
            listOf(
                FakeFile("rpg_core.js", "// core".toByteArray()),
                FakeFile("System.json", "{}".toByteArray()),
                FakeFile("other.txt", "nope".toByteArray()),
            ),
        )
        val tmp = java.io.File.createTempFile("evb", ".bin").apply { writeBytes(image) }
        try {
            val seen = mutableMapOf<String, ByteArray>()
            EvbVirtualFileSystem.extractMatching(
                tmp,
                predicate = { it.endsWith(".json") },
            ) { path, bytes -> seen[path] = bytes }
            assertEquals(setOf("System.json"), seen.keys)
            assertArrayEquals("{}".toByteArray(), seen["System.json"])
        } finally {
            tmp.delete()
        }
    }

    @Test(expected = WolfFormatException::class)
    fun `missing magic throws`() {
        EvbVirtualFileSystem.fromMemory(ByteArray(1024)).entries()
    }

    // --- Synthetic EVB image builder -----------------------------------------

    private class FakeFile(val name: String, val payload: ByteArray, val originalSize: Int = payload.size)

    /**
     * Builds a structurally exact minimal EVB filesystem image:
     * junk PE prefix + magic + pack header + main node + records + file data,
     * including the off-by-one record start position used after the main node.
     */
    private fun buildEvbImage(files: List<FakeFile>): ByteArray {
        val folderName = "%DEFAULT FOLDER%"

        fun record(name: String, type: Int, count: Int, original: Int = 0, stored: Int = 0): List<Byte> {
            val out = mutableListOf<Byte>()
            fun u32(v: Int) {
                out.add((v and 0xFF).toByte()); out.add(((v shr 8) and 0xFF).toByte())
                out.add(((v shr 16) and 0xFF).toByte()); out.add(((v ushr 24) and 0xFF).toByte())
            }
            u32(0)                        // header size (unused by external-mode reader)
            repeat(8) { out.add(0) }      // header padding
            u32(count)                    // objects_count
            name.forEach { c -> val u = c.code; out.add((u and 0xFF).toByte()); out.add(((u shr 8) and 0xFF).toByte()) }
            out.add(0); out.add(0)        // UTF-16LE double-null terminator
            out.add(type.toByte())        // type byte
            if (type == NODE_FOLDER) {
                repeat(25) { out.add(0) } // folder optional area (skipped by reader)
            } else {
                out.add(0); out.add(0)    // 2s prefix
                u32(original)             // original_size @+2
                repeat(4) { out.add(0) }  // 4s
                repeat(24) { out.add(0) } // 3 × 8s filetimes
                repeat(15) { out.add(0) } // 15s
                u32(stored)               // stored_size @+49
            }
            return out
        }

        val allRecords = mutableListOf<List<Byte>>()
        allRecords.add(record(folderName, NODE_FOLDER, count = files.size))
        files.forEach {
            allRecords.add(record(it.name, NODE_FILE, count = 0, original = it.originalSize, stored = it.payload.size))
        }
        val recordsLength = allRecords.sumOf { it.size }

        val prefix = "MZ-fake-pe-payload".toByteArray()
        val magicPos = prefix.size
        val mainNodePos = magicPos + 64
        val firstRecordPos = mainNodePos + 15           // off-by-one quirk after seek(-1,1)
        val dataStart = firstRecordPos + recordsLength
        val mainSize = dataStart - (mainNodePos + 4)    // absOffset = tell(P0+16) + size - 12

        val buf = ByteArray(dataStart + files.sumOf { it.payload.size })
        prefix.copyInto(buf)
        byteArrayOf('E'.code.toByte(), 'V'.code.toByte(), 'B'.code.toByte(), 0).copyInto(buf, magicPos)
        writeU32(buf, mainNodePos, mainSize.toLong())
        writeU32(buf, mainNodePos + 12, 1L)             // root holds one folder

        var w = firstRecordPos
        for (rec in allRecords) {
            rec.forEachIndexed { i, b -> buf[w + i] = b }
            w += rec.size
        }
        var d = dataStart
        for (f in files) {
            f.payload.copyInto(buf, d)
            d += f.payload.size
        }
        return buf
    }

    private fun writeU32(buf: ByteArray, offset: Int, v: Long) {
        buf[offset] = (v and 0xFF).toByte()
        buf[offset + 1] = ((v shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((v shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((v shr 24) and 0xFF).toByte()
    }

    companion object {
        private const val NODE_FILE = 2
        private const val NODE_FOLDER = 3
    }
}
