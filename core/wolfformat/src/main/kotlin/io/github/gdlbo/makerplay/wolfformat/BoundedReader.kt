package io.github.gdlbo.makerplay.wolfformat

/**
 * Bounds-checked little-endian reader over an in-memory WOLF data file.
 *
 * Every read validates against [size] and every length/count field is checked
 * against [limits] before allocation, so malformed or hostile files fail with
 * [WolfFormatException] instead of exhausting memory.
 */
class BoundedReader(
    private val data: ByteArray,
    private val offset: Int = 0,
    private val size: Int = data.size - offset,
    private val limits: Limits = Limits.DEFAULT,
) {
    init {
        require(offset >= 0 && size >= 0 && offset + size <= data.size) {
            "BoundedReader window exceeds buffer"
        }
    }

    data class Limits(
        val maxFileBytes: Long,
        val maxStringBytes: Int,
        val maxCount: Int,
        val maxRecords: Int,
    ) {
        companion object {
            // WOLF data files are at most a few hundred MB; keep generous but
            // finite ceilings so corrupt counts cannot drive allocation.
            val DEFAULT = Limits(
                maxFileBytes = 512L * 1024 * 1024,
                maxStringBytes = 4 * 1024 * 1024,
                maxCount = 8_000_000,
                maxRecords = 1_000_000,
            )
        }
    }

    private var pos = offset
    private val end = offset + size

    val remaining: Int get() = end - pos

    fun position(): Int = pos - offset

    private fun need(bytes: Int, what: String) {
        if (bytes < 0 || pos + bytes > end) {
            throw WolfFormatException("Truncated $what: need $bytes byte(s) at ${position()}, only $remaining left")
        }
    }

    fun readU1(): Int {
        need(1, "u1")
        return data[pos++].toInt() and 0xFF
    }

    fun readU2(): Int {
        need(2, "u2")
        val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    fun readS2(): Int = readU2().let { if (it >= 0x8000) it - 0x10000 else it }

    fun readU4(): Long {
        need(4, "u4")
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
        pos += 4
        return v
    }

    fun readS4(): Int = readU4().toInt()

    fun readU64(): Long = readU4() or (readU4() shl 32)

    /** Reads a u4 count validated against the given ceiling. */
    fun readCount(what: String): Int {
        val count = readU4()
        if (count > limits.maxCount) {
            throw WolfFormatException("$what count $count exceeds limit ${limits.maxCount}")
        }
        return count.toInt()
    }

    /**
     * Length-prefixed WOLF string: u4 length then that many bytes holding a
     * NUL-terminated string. v2 files use Shift-JIS; v3 files use UTF-8.
     */
    fun readString(v3: Boolean): String {
        val length = readU4()
        if (length > limits.maxStringBytes) {
            throw WolfFormatException("String length $length exceeds limit ${limits.maxStringBytes}")
        }
        need(length.toInt(), "string")
        val bytes = data.copyOfRange(pos, pos + length.toInt())
        pos += length.toInt()
        // Strip one trailing NUL as produced by the editor.
        val effective = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == 0.toByte()) {
            bytes.copyOfRange(0, bytes.size - 1)
        } else {
            bytes
        }
        val charset = if (v3) Charsets.UTF_8 else CharsetShiftJis
        return runCatching { String(effective, charset) }.getOrElse {
            throw WolfFormatException("Undecodable string in ${if (v3) "v3" else "v2"} file", it)
        }
    }

    fun skip(bytes: Long, what: String) {
        if (bytes < 0 || bytes > remaining) {
            throw WolfFormatException("Cannot skip $bytes byte(s) of $what; only $remaining left")
        }
        pos += bytes.toInt()
    }

    fun readBytes(count: Int, what: String): ByteArray {
        need(count, what)
        val out = data.copyOfRange(pos, pos + count)
        pos += count
        return out
    }

    /** Sub-reader over the next [length] bytes without copying. */
    fun slice(length: Int, what: String): BoundedReader {
        need(length, what)
        val sub = BoundedReader(data, pos, length, limits)
        pos += length
        return sub
    }

    companion object {
        /** Shift-JIS (Windows-31J); falls back to a lenient decoder on JVMs without it. */
        internal val CharsetShiftJis: java.nio.charset.Charset by lazy {
            runCatching { java.nio.charset.Charset.forName("windows-31j") }
                .getOrElse { java.nio.charset.Charset.forName("Shift_JIS") }
        }
    }
}

/** Thrown for malformed, truncated, or unsupported WOLF data. */
class WolfFormatException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
