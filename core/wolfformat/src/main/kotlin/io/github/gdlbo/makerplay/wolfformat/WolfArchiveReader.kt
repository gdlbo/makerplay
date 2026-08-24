package io.github.gdlbo.makerplay.wolfformat

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * Reader for WOLF RPG `.wolf` archives (DxLib "DXArchive" containers).
 *
 * Clean-room implementation of the documented DX layout:
 * - Header (64 bytes): u16 'DX' magic, u16 version, u32 header size, then
 *   64-bit offsets to the data area, name table, file table, and directory
 *   table, plus character-code format, flags, and huffman settings.
 * - The whole header area (name/file/directory tables included) is XOR-encoded
 *   with a 7-byte archive key derived from two CRC-32 values over the key
 *   string (Game.dat's encryption key).
 * - Each file is additionally XOR-encoded with a per-file key built from the
 *   key string, the file name, and its parent directory names; the stream
 *   position is seeded with the file's own uncompressed size.
 *
 * Compressed entries are rejected explicitly rather than mis-decoded.
 */
class WolfArchiveReader(
    private val file: File,
    /** Key string from Game.dat; falls back to the DxLib default when short. */
    private val keyString: String = "",
    private val limits: Limits = Limits(),
) {
    data class Limits(val maxEntryBytes: Long = 512L * 1024 * 1024, val maxEntries: Int = 500_000)

    data class Entry(
        val path: String,
        val offset: Long,
        val size: Long,
        val compressed: Boolean,
        internal val fileKey: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = other is Entry && other.path == path
        override fun hashCode(): Int = path.hashCode()
    }

    private val root: Node by lazy { open() }

    /** Lists every file entry, using '/'-separated paths relative to the archive root. */
    fun entries(): List<Entry> {
        val out = mutableListOf<Entry>()
        fun visit(node: Node, prefix: String) {
            if (!node.isDirectory) {
                out.add(
                    Entry(
                        prefix + node.name,
                        node.dataAddress,
                        node.dataSize,
                        node.compressed,
                        deriveKey(prefix + node.name),
                    ),
                )
                return
            }
            val nextPrefix = if (node.name.isEmpty()) prefix else prefix + node.name + "/"
            for (child in node.children) visit(child, nextPrefix)
        }
        for (child in root.children) visit(child, "")
        return out
    }

    /** Reads and decodes one entry into memory. */
    fun extract(entry: Entry): ByteArray {
        if (entry.compressed) {
            throw WolfFormatException("Compressed .wolf entry '${entry.path}' is not supported yet")
        }
        if (entry.size > limits.maxEntryBytes) {
            throw WolfFormatException("Entry '${entry.path}' exceeds size limit")
        }
        RandomAccessFile(file, "r").use { raf ->
            val data = ByteArray(entry.size.toInt())
            readFully(raf, entry.offset, data)
            // The obfuscation stream position is seeded with the file's size.
            decode(data, entry.size, entry.fileKey)
            return data
        }
    }

    // --- internals ---------------------------------------------------------

    private class Node(
        var name: String,
        val isDirectory: Boolean,
        val children: MutableList<Node> = mutableListOf(),
        var dataAddress: Long = 0,
        var dataSize: Long = 0,
        var compressed: Boolean = false,
    )

    private fun open(): Node = RandomAccessFile(file, "r").use { raf ->
        if (raf.length() < HEADER_BYTES) {
            throw WolfFormatException(".wolf archive too small: ${raf.length()} bytes")
        }
        val head = ByteArray(HEADER_BYTES).also { readFully(raf, 0, it) }
        val plaintextMagic =
            (head[0].toInt() and 0xFF) == 0x44 && (head[1].toInt() and 0xFF) == 0x58
        val useKey = !plaintextMagic

        val archiveKey = deriveKey(keyString)
        if (useKey) {
            decode(head, 0, archiveKey)
            if ((head[0].toInt() and 0xFF) != 0x44 || (head[1].toInt() and 0xFF) != 0x58) {
                throw WolfFormatException(".wolf magic mismatch after decryption; wrong key?")
            }
        }

        fun u16(o: Int): Int = (head[o].toInt() and 0xFF) or ((head[o + 1].toInt() and 0xFF) shl 8)

        fun u32(o: Int): Long {
            var v = 0L
            for (i in 3 downTo 0) v = (v shl 8) or (head[o + i].toLong() and 0xFF)
            return v
        }

        fun u64(o: Int): Long = (u32(o) and 0xFFFFFFFFL) or (u32(o + 4) shl 32)

        val version = u16(2)
        if (version < MIN_VERSION) {
            throw WolfFormatException("Unsupported .wolf archive version $version")
        }
        val dataStart = u64(8)
        val nameTableStart = u64(16)
        val fileTableStart = u64(24)
        val dirTableStart = u64(32)
        val charCodeFormat = u32(40).toInt()
        if (u32(44) != 0L && useKey) {
            throw WolfFormatException("Unsupported .wolf flags 0x${u32(44).toString(16)}")
        }
        val nameCharset =
            if (charCodeFormat == CODE_PAGE_UTF8) Charsets.UTF_8 else Charset.forName("windows-31j")

        // The directory table starts with the root directory at address 0.
        readDirectory(
            raf, useKey, archiveKey, keyString, dataStart, nameTableStart, fileTableStart,
            dirTableStart, 0L, charCodeFormat, nameCharset, "",
        )
    }

    private fun readDirectory(
        raf: RandomAccessFile,
        useKey: Boolean,
        archiveKey: ByteArray,
        keyPrefix: String,
        dataStart: Long,
        nameTableStart: Long,
        fileTableBase: Long,
        dirTableBase: Long,
        dirOffset: Long,
        charCodeFormat: Int,
        nameCharset: Charset,
        parentPath: String,
    ): Node {
        val dirBytes = ByteArray(DIRECTORY_BYTES).also { readFully(raf, dirTableBase + dirOffset, it) }
        if (useKey) decode(dirBytes, dirTableBase + dirOffset, archiveKey)
        val directoryAddress = le64(dirBytes, 0)
        val fileHeadNum = le64(dirBytes, 16)
        val fileHeadAddress = le64(dirBytes, 24)
        if (fileHeadNum > limits.maxEntries) {
            throw WolfFormatException("Directory claims $fileHeadNum entries")
        }

        val dirName = if (directoryAddress != ALL_ONES) {
            val dirFileHead = ByteArray(FILE_HEAD_BYTES).also {
                readFully(raf, fileTableBase + directoryAddress, it)
            }
            if (useKey) decode(dirFileHead, fileTableBase + directoryAddress, archiveKey)
            readName(raf, useKey, archiveKey, nameTableStart, le64(dirFileHead, 0), nameCharset)
        } else {
            ""
        }
        val node = Node(dirName.ifBlank { "" }, isDirectory = true)
        val childPath = if (dirName.isBlank()) parentPath else parentPath + dirName + "/"

        for (i in 0 until fileHeadNum.toInt()) {
            val tableOffset = fileHeadAddress + i * FILE_HEAD_BYTES
            val fh = ByteArray(FILE_HEAD_BYTES).also { readFully(raf, fileTableBase + tableOffset, it) }
            if (useKey) decode(fh, fileTableBase + tableOffset, archiveKey)
            val nameAddress = le64(fh, 0)
            val attributes = le64(fh, 8)
            val dataAddress = le64(fh, 40)
            val dataSize = le64(fh, 48)
            val pressDataSize = le64(fh, 56)
            val huffDataSize = le64(fh, 64)
            val name = readName(raf, useKey, archiveKey, nameTableStart, nameAddress, nameCharset)
            if (attributes and FILE_ATTRIBUTE_DIRECTORY != 0L) {
                node.children.add(
                    readDirectory(
                        raf, useKey, archiveKey, keyPrefix, dataStart, nameTableStart,
                        fileTableBase, dirTableBase, dataAddress, charCodeFormat,
                        nameCharset, childPath,
                    ).also { it.name = name },
                )
            } else {
                node.children.add(
                    Node(
                        name = name,
                        isDirectory = false,
                        dataAddress = dataStart + dataAddress,
                        dataSize = dataSize,
                        compressed = pressDataSize != ALL_ONES || huffDataSize != ALL_ONES,
                    ),
                )
            }
        }
        return node
    }

    /**
     * Reads one name-table record: u16 length-in-quarters, u16 parity word, an
     * uppercase copy padded to four bytes, then the original-case name.
     */
    private fun readName(
        raf: RandomAccessFile,
        useKey: Boolean,
        key: ByteArray,
        nameTableStart: Long,
        nameAddress: Long,
        charset: Charset,
    ): String {
        val lenWord = ByteArray(4).also { readFully(raf, nameTableStart + nameAddress, it) }
        if (useKey) decode(lenWord, nameAddress, key)
        val quarterLen = (lenWord[0].toInt() and 0xFF) or ((lenWord[1].toInt() and 0xFF) shl 8)
        if (quarterLen <= 0 || quarterLen > MAX_NAME_QUARTERS) {
            throw WolfFormatException("Invalid name table entry length $quarterLen")
        }
        val upperPadded = quarterLen * 4
        val rest = ByteArray(MAX_NAME_REST_BYTES).also {
            readFully(raf, nameTableStart + nameAddress + 4 + upperPadded, it)
        }
        if (useKey) decode(rest, nameAddress + 4 + upperPadded, key)
        val end = rest.indexOf(0)
        return String(rest, 0, if (end >= 0) end else rest.size, charset)
    }

    private fun readFully(raf: RandomAccessFile, position: Long, target: ByteArray) {
        if (position < 0 || target.isNotEmpty() && position + target.size > raf.length()) {
            throw WolfFormatException("Read past end of archive at $position")
        }
        raf.seek(position)
        raf.readFully(target)
    }

    companion object {
        private const val HEADER_BYTES = 64
        private const val FILE_HEAD_BYTES = 72
        private const val DIRECTORY_BYTES = 32
        private const val ALL_ONES = -1L
        private const val FILE_ATTRIBUTE_DIRECTORY = 0x10L
        private const val MIN_VERSION = 5
        private const val CODE_PAGE_UTF8 = 65001
        private const val MAX_NAME_QUARTERS = 256
        private const val MAX_NAME_REST_BYTES = 2048
        private const val DEFAULT_KEY_STRING = "DXLIBARC"

        /** CRC-32 (IEEE) used by the DxLib key derivation. */
        fun crc32(data: ByteArray): Long {
            var crc = 0xFFFFFFFFL
            for (b in data) {
                crc = crc xor (b.toLong() and 0xFFL)
                repeat(8) {
                    crc = if (crc and 1L != 0L) (crc ushr 1) xor 0xEDB88320L else crc ushr 1
                }
            }
            return crc xor 0xFFFFFFFFL
        }

        /**
         * Derives the 7-byte XOR key: interleaved characters split into two
         * halves whose CRC-32 values fill the key. Sources shorter than four
         * bytes fall back to DxLib's default key string.
         */
        fun deriveKey(source: String): ByteArray {
            var text = source
            if (text.toByteArray(Charsets.US_ASCII).size < 4) text += DEFAULT_KEY_STRING
            val bytes = text.toByteArray()
            val even = ByteArray((bytes.size + 1) / 2) { bytes[it * 2] }
            val odd = ByteArray(bytes.size / 2) { bytes[it * 2 + 1] }
            val c0 = crc32(even)
            val c1 = crc32(odd)
            return byteArrayOf(
                (c0 shr 0).toByte(), (c0 shr 8).toByte(), (c0 shr 16).toByte(), (c0 shr 24).toByte(),
                (c1 shr 0).toByte(), (c1 shr 8).toByte(), (c1 shr 16).toByte(),
            )
        }

        /** XOR-decodes [data] in place; [position] seeds the 7-byte key stream. */
        fun decode(data: ByteArray, position: Long, key: ByteArray) {
            var j = (position % key.size).toInt()
            for (i in data.indices) {
                data[i] = (data[i].toInt() xor key[j].toInt()).toByte()
                if (++j == key.size) j = 0
            }
        }

        private fun le64(data: ByteArray, o: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (data[o + i].toLong() and 0xFF)
            return v
        }
    }
}
