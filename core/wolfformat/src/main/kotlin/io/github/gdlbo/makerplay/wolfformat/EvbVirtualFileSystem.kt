package io.github.gdlbo.makerplay.wolfformat

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Seek-based reader for virtual files embedded in packed game executables.
 * The reader avoids loading a complete image into memory.
 */
class EvbVirtualFileSystem private constructor(private val input: SeekableInput) : Closeable {

    /** Minimal random-access view used by the parser and extractor. */
    interface SeekableInput {
        val size: Long
        fun readAt(offset: Long, length: Int): ByteArray
    }

    data class Entry(
        val path: String,
        val dataOffset: Int,
        val storedSize: Int,
        val originalSize: Int,
    ) {
        val isCompressed: Boolean get() = storedSize != originalSize
    }

    private var parsed = false
    private var fileList: List<Entry> = emptyList()

    private data class Record(
        val name: String,
        val type: Int,
        val objectsCount: Int,
        val dataOffset: Int,
        val storedSize: Int,
        val originalSize: Int,
    )

    fun entries(): List<Entry> {
        ensureParsed()
        return fileList
    }

    /** Reads and (if needed) aPLib-decompresses one file entry. Use for small files. */
    fun extract(entry: Entry): ByteArray {
        validateBounds(entry)
        val raw = input.readAt(entry.dataOffset.toLong(), entry.storedSize)
        return if (entry.isCompressed) AplibDecompressor.decompress(raw) else raw
    }

    /**
     * Memory-bounded extraction: streams [entry] into [out] in fixed chunks.
     * Stored entries never materialize fully in memory; compressed entries are
     * decompressed one at a time because the decoder needs its full input.
     */
    fun extractTo(entry: Entry, out: java.io.OutputStream) {
        validateBounds(entry)
        if (!entry.isCompressed) {
            var remaining = entry.storedSize
            var offset = entry.dataOffset.toLong()
            while (remaining > 0) {
                val chunk = input.readAt(offset, minOf(COPY_CHUNK_BYTES, remaining))
                out.write(chunk)
                offset += chunk.size
                remaining -= chunk.size
            }
        } else {
            out.write(extract(entry))
        }
    }

    override fun close() {
        (input as? Closeable)?.close()
    }

    /**
     * Opens one entry as a lazy [InputStream]. Stored entries are streamed in
     * fixed chunks (constant memory); compressed entries are decompressed once
     * (the decoder needs the compressed entry as a complete stream).
     */
    fun openStream(entry: Entry): java.io.InputStream {
        validateBounds(entry)
        if (!entry.isCompressed) {
            return object : java.io.InputStream() {
                private var remaining = entry.storedSize
                private var chunkPos = 0
                private var chunk = ByteArray(0)
                private var offset = entry.dataOffset.toLong()

                override fun read(): Int {
                    val b = ByteArray(1)
                    return if (read(b) == -1) -1 else b[0].toInt() and 0xFF
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remaining <= 0) return -1
                    if (chunkPos >= chunk.size) {
                        chunk = input.readAt(offset, minOf(COPY_CHUNK_BYTES, remaining))
                        chunkPos = 0
                        offset += chunk.size
                    }
                    val n = minOf(len, chunk.size - chunkPos, remaining)
                    System.arraycopy(chunk, chunkPos, b, off, n)
                    chunkPos += n
                    remaining -= n
                    return n
                }
            }
        }
        return extract(entry).inputStream()
    }

    private fun validateBounds(entry: Entry) {
        if (entry.dataOffset < 0 || entry.dataOffset.toLong() + entry.storedSize > input.size) {
            throw WolfFormatException("EVB entry out of bounds: ${entry.path}")
        }
    }

    // --- Parsing ---------------------------------------------------------------

    private fun ensureParsed() {
        if (parsed) return
        parsed = true
        fileList = try {
            parse()
        } catch (e: WolfFormatException) {
            throw e
        } catch (e: Exception) {
            throw WolfFormatException("EVB parse failed", e)
        }
    }

    private fun parse(): List<Entry> {
        val magicOffset = findMagic()
            ?: throw WolfFormatException("EVB filesystem magic not found")
        var pos = (magicOffset + PACK_HEADER_SIZE).toLong()

        // Main node: size(I) + pad(8s) + objects_count(I)
        if (pos + HEADER_NODE_SIZE > input.size) throw WolfFormatException("EVB truncated at main node")
        val mainNode = input.readAt(pos.toLong(), HEADER_NODE_SIZE)
        val mainSize = readU32(mainNode, 0).toInt()
        val mainObjectsCount = readU32(mainNode, 12).toInt()
        pos += HEADER_NODE_SIZE
        // Per reference: abs_offset = tell + size - 12, then seek(-1, 1)
        var absDataOffset = pos.toInt() + mainSize - 12
        pos -= 1

        // Flat sequential enumeration of all records until object budget exhausted
        val records = mutableListOf<Record>()
        var maxObjectCount = 0
        var currentObjectCount = 0
        while (true) {
            if (pos + HEADER_NODE_SIZE > input.size) break

            // Header node: size(I) + padding(8s) + objects_count(I)
            val header = input.readAt(pos.toLong(), HEADER_NODE_SIZE)
            val objectsCount = readU32(header, 12).toInt()
            pos += HEADER_NODE_SIZE

            // Named node: UTF-16LE chars until double-null, then 1 type byte
            val nameBytes = mutableListOf<Byte>()
            var terminated = false
            while (!terminated) {
                if (pos + 2 > input.size) return emptyList() // corrupted table
                val pair = input.readAt(pos, 2)
                pos += 2
                if (pair[0] == ZERO_BYTE && pair[1] == ZERO_BYTE) terminated = true
                else { nameBytes.add(pair[0]); nameBytes.add(pair[1]) }
            }
            if (pos >= input.size) break
            val type = input.readAt(pos, 1)[0].toInt() and 0xFF
            pos += 1L
            val name = decodeUtf16Le(nameBytes.toByteArray())

            when (type) {
                NODE_TYPE_FILE -> {
                    if (pos + OPTIONAL_FILE_NODE_SIZE > input.size) break
                    val optional = input.readAt(pos.toLong(), OPTIONAL_FILE_NODE_SIZE)
                    val originalSize = readU32(optional, 2).toInt()
                    val storedSize = readU32(optional, 49).toInt()
                    pos += OPTIONAL_FILE_NODE_SIZE
                    records.add(Record(name, type, objectsCount, absDataOffset, storedSize, originalSize))
                    absDataOffset += storedSize
                    currentObjectCount++
                }
                NODE_TYPE_FOLDER -> {
                    pos += FOLDER_SKIP_SIZE
                    maxObjectCount += objectsCount
                    currentObjectCount++
                    records.add(Record(name, type, objectsCount, 0, 0, 0))
                }
                else -> return emptyList() // finished (or corrupted)
            }
            if (currentObjectCount > maxObjectCount && maxObjectCount > 0) break
        }

        // Recursive path assignment mirroring traverse_next_node()
        val files = mutableListOf<Entry>()
        val iterator = records.iterator()

        fun descend(prefix: String, count: Int) {
            repeat(count) {
                if (!iterator.hasNext()) return
                val record = iterator.next()
                when (record.type) {
                    NODE_TYPE_FOLDER -> {
                        val folderName = FOLDER_ALTNAMES[record.name] ?: record.name
                        validateName(folderName)
                        val childPrefix = if (folderName.isEmpty()) prefix else "$prefix$folderName/"
                        descend(childPrefix, record.objectsCount)
                    }
                    NODE_TYPE_FILE -> {
                        validateName(record.name)
                        files.add(
                            Entry(
                                path = prefix + record.name,
                                dataOffset = record.dataOffset,
                                storedSize = record.storedSize,
                                originalSize = record.originalSize,
                            ),
                        )
                    }
                }
            }
        }
        descend("", mainObjectsCount)
        return files
    }

    private fun findMagic(): Int? {
        // Scan in chunks to avoid loading the whole image
        val chunkSize = 1 shl 20
        var base = 0L
        val prevTail = MAGIC.size - 1
        var carry = ByteArray(0)
        while (base < input.size) {
            val len = minOf(chunkSize, (input.size - base).toInt())
            val chunk = input.readAt(base, len)
            val window = carry + chunk
            val idx = indexOf(window, MAGIC)
            if (idx >= 0) return (base - carry.size).toInt() + idx
            carry = window.copyOfRange(maxOf(0, window.size - prevTail), window.size)
            base += len
        }
        return null
    }

    companion object {
        const val NODE_TYPE_MAIN = 0
        const val NODE_TYPE_FILE = 2
        const val NODE_TYPE_FOLDER = 3

        private const val PACK_HEADER_SIZE = 64      // 4s signature + 60s padding
        private const val HEADER_NODE_SIZE = 16      // I size + 8s pad + I objects_count
        private const val OPTIONAL_FILE_NODE_SIZE = 53
        private const val FOLDER_SKIP_SIZE = 25
        private const val COPY_CHUNK_BYTES = 1024 * 1024
        private val ZERO_BYTE: Byte = 0
        private val MAGIC = byteArrayOf('E'.code.toByte(), 'V'.code.toByte(), 'B'.code.toByte(), 0)

        private val FOLDER_ALTNAMES = mapOf("%DEFAULT FOLDER%" to "")

        /** Opens an EVB filesystem backed by [file] (memory-efficient). */
        fun open(file: File): EvbVirtualFileSystem {
            val raf = RandomAccessFile(file, "r")
            return EvbVirtualFileSystem(object : SeekableInput, Closeable {
                override val size: Long get() = raf.length()
                override fun readAt(offset: Long, length: Int): ByteArray {
                    raf.seek(offset)
                    val buf = ByteArray(length)
                    raf.readFully(buf)
                    return buf
                }
                override fun close() = raf.close()
            })
        }

        /** Opens an EVB filesystem over an in-memory image. */
        fun fromMemory(data: ByteArray): EvbVirtualFileSystem =
            EvbVirtualFileSystem(object : SeekableInput {
                override val size: Long get() = data.size.toLong()
                override fun readAt(offset: Long, length: Int): ByteArray =
                    data.copyOfRange(offset.toInt(), offset.toInt() + length)
            })

        /**
         * Opens one [entry] of [file] as a lazy stream. The returned stream
         * owns the underlying image handle and releases it on close.
         */
        fun openEntryStream(file: File, entry: Entry): java.io.InputStream {
            val vfs = open(file)
            val stream = try {
                vfs.openStream(entry)
            } catch (e: Exception) {
                runCatching(vfs::close)
                throw e
            }
            return object : java.io.FilterInputStream(stream) {
                override fun close() {
                    try { super.close() } finally { runCatching(vfs::close) }
                }
            }
        }

        /**
         * Streams every virtual file matching [predicate] to [sink], one file at
         * a time. Engine-agnostic and memory-bounded — suitable for Android.
         */
        inline fun extractMatching(
            file: File,
            predicate: (path: String) -> Boolean,
            sink: (path: String, bytes: ByteArray) -> Unit,
        ) {
            open(file).use { vfs ->
                for (entry in vfs.entries()) {
                    if (predicate(entry.path)) {
                        sink(entry.path, vfs.extract(entry))
                    }
                }
            }
        }

        private fun validateName(name: String) {
            require(!name.contains('\\') && !name.contains('/') && !name.contains(':')) {
                "Invalid character in EVB node name: $name"
            }
            require(name != ".." && name != ".") { "Invalid EVB node name: $name" }
        }

        private fun readU32(data: ByteArray, offset: Int): Long =
            (data[offset].toLong() and 0xFF) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24)

        private fun decodeUtf16Le(bytes: ByteArray): String {
            val sb = StringBuilder()
            var i = 0
            while (i + 1 < bytes.size) {
                val ch = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                if (ch == 0) break
                sb.append(ch.toChar())
                i += 2
            }
            return sb.toString()
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) {
                    if (haystack[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return -1
        }
    }
}
