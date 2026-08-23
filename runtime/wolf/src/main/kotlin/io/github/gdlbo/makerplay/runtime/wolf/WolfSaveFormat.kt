package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.wolfformat.BoundedReader
import io.github.gdlbo.makerplay.wolfformat.WolfFormatException
import java.io.ByteArrayOutputStream

/**
 * MakerPlay's atomic WOLF save format ("MKPS", version 1).
 *
 * Layout: 8-byte magic `MKP\0SAVE\0`, u32 version, then length-prefixed
 * sections. Every section carries its own size so a corrupt or truncated tail
 * cannot corrupt earlier sections; readers validate all bounds before
 * allocating. Writers produce the full byte array first so persistence can be
 * a single atomic file replacement (see [WolfGameSaveManager]).
 *
 * Sections:
 * - header: title string, map id, tile position
 * - vars:   interpreter numeric variable slots
 * - strs:   interpreter string variable slots
 */
object WolfSaveFormat {

    /** `MKP\u0000SAVE\u0000` */
    val MAGIC: ByteArray =
        byteArrayOf(0x4D, 0x4B, 0x50, 0, 0x53, 0x41, 0x56, 0x45, 0) // "MKP?SAVE?"
    const val VERSION = 1

    data class GameState(
        val title: String,
        val mapPath: String,
        val tileX: Int,
        val tileY: Int,
        val variables: Map<Int, Int>,
        val strings: Map<Int, String>,
    )

    fun encode(state: GameState): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        writeU32(out, VERSION.toLong())

        // Header section.
        val header = ByteArrayOutputStream()
        writeString(header, state.title)
        writeString(header, state.mapPath)
        writeS32(header, state.tileX)
        writeS32(header, state.tileY)
        writeSection(out, header.toByteArray())

        // Variables section.
        val vars = ByteArrayOutputStream()
        writeU32(vars, state.variables.size.toLong())
        state.variables.entries.sortedBy { it.key }.forEach { (key, value) ->
            writeS32(vars, key)
            writeS32(vars, value)
        }
        writeSection(out, vars.toByteArray())

        // Strings section.
        val strs = ByteArrayOutputStream()
        writeU32(strs, state.strings.size.toLong())
        state.strings.entries.sortedBy { it.key }.forEach { (key, value) ->
            writeS32(strs, key)
            writeString(strs, value)
        }
        writeSection(out, strs.toByteArray())
        return out.toByteArray()
    }

    fun decode(data: ByteArray): GameState {
        if (data.size < MAGIC.size) throw WolfFormatException("Save too small")
        if (!startsWithBytes(data, MAGIC)) throw WolfFormatException("Not a MakerPlay WOLF save")

        val reader = BoundedReader(data, offset = MAGIC.size)
        val version = reader.readU4().toInt()
        if (version != VERSION) {
            throw WolfFormatException("Unsupported save version $version")
        }

        val header = section(reader)
        val title = header.readString(true)
        val mapPath = header.readString(true)
        val tileX = header.readS4()
        val tileY = header.readS4()

        val varsReader = section(reader)
        val varCount = varsReader.readCount("save variable")
        val variables = HashMap<Int, Int>(varCount)
        repeat(varCount) { variables[varsReader.readS4()] = varsReader.readS4() }

        val strReader = section(reader)
        val strCount = strReader.readCount("save string")
        val strings = HashMap<Int, String>(strCount)
        repeat(strCount) { strings[strReader.readS4()] = strReader.readString(true) }

        return GameState(
            title = title,
            mapPath = mapPath,
            tileX = tileX,
            tileY = tileY,
            variables = variables,
            strings = strings,
        )
    }

    private fun section(reader: BoundedReader): BoundedReader {
        val size = reader.readU4()
        if (size > reader.remaining) {
            throw WolfFormatException("Save section of $size bytes exceeds remaining ${reader.remaining}")
        }
        return reader.slice(size.toInt(), "save section")
    }

    private fun writeSection(out: ByteArrayOutputStream, payload: ByteArray) {
        writeU32(out, payload.size.toLong())
        out.write(payload)
    }

    private fun writeString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeU32(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeS32(out: ByteArrayOutputStream, value: Int) =
        writeU32(out, value.toLong() and 0xFFFFFFFFL)

    private fun writeU32(out: ByteArrayOutputStream, value: Long) {
        out.write((value and 0xFF).toInt())
        out.write(((value shr 8) and 0xFF).toInt())
        out.write(((value shr 16) and 0xFF).toInt())
        out.write(((value shr 24) and 0xFF).toInt())
    }

    private fun startsWithBytes(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) if (data[i] != prefix[i]) return false
        return true
    }
}
