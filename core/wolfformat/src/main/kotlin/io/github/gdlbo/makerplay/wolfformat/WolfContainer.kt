package io.github.gdlbo.makerplay.wolfformat

/**
 * Shared WOLF RPG binary container primitives.
 *
 * All `.dat` files open with the 6-byte core magic `00 'W' 00 00 'O' 'L'`.
 * What follows depends on the file family:
 *
 * - Standard dat (`DataBase`, `SysDatabase`, `MapTree`, `TileSetData`):
 *   core magic, version byte, then the tag `'F','M',0x00`.
 * - `CommonEvent.dat`: same, but with tag `'F','C',0x00`.
 * - `Game.dat`: core magic, `0x00, 'F', 'M'`, **then** the version byte.
 * - `.mps` maps: ten zero bytes, `'WOLFM'`, `0x00`, version, three zeros
 *   (see [MapFile]).
 *
 * The version byte selects the string encoding: 0x00 = editor v2 (Shift-JIS),
 * 0x55 = v3 (UTF-8).
 */
object WolfContainer {
    val CORE_MAGIC: ByteArray = byteArrayOf(
        0x00, 'W'.code.toByte(), 0x00, 0x00,
        'O'.code.toByte(), 'L'.code.toByte(),
    )

    const val VERSION_V2: Int = 0x00
    const val VERSION_V3: Int = 0x55

    const val TAG_FM: Byte = 'F'.code.toByte()
    private val TAG_DATA: ByteArray = byteArrayOf(TAG_FM, 'M'.code.toByte(), 0x00)
    private val TAG_COMMON_EVENT: ByteArray = byteArrayOf(TAG_FM, 'C'.code.toByte(), 0x00)

    /** Minor-revision footer bytes used by Game.dat and database-family files. */
    const val FOOTER_V2: Int = 0xC2
    const val FOOTER_V30: Int = 0xC3
    const val FOOTER_V33: Int = 0xC4
    val DATABASE_FOOTERS: Set<Int> = setOf(FOOTER_V2, FOOTER_V30, FOOTER_V33)

    /** TileSetData.dat closes with its own constant. */
    const val FOOTER_TILESET: Int = 0xCF

    /** CommonEvent.dat closes with one of these separators. */
    val COMMON_EVENT_FOOTERS: Set<Int> = setOf(0x8F, 0x90, 0x91)

    /** Broad acceptance set for the generic archive reader. */
    val KNOWN_FOOTERS: Set<Int> = DATABASE_FOOTERS + COMMON_EVENT_FOOTERS + setOf(FOOTER_TILESET)

    fun isWolfDat(data: ByteArray): Boolean {
        if (data.size < CORE_MAGIC.size) return false
        for (i in CORE_MAGIC.indices) {
            if (data[i] != CORE_MAGIC[i]) return false
        }
        return true
    }

    /** Returns true when [data] starts like a WOLF dat of any known version. */
    fun detectVersionHeader(data: ByteArray): Int? {
        if (!isWolfDat(data)) return null
        // Standard dat: version at offset 6. Game.dat: version at offset 9.
        val standard = data[CORE_MAGIC.size].toInt() and 0xFF
        if (standard == VERSION_V2 || standard == VERSION_V3) return standard
        if (data.size > CORE_MAGIC.size + 4) {
            val gameDat = data[CORE_MAGIC.size + 3].toInt() and 0xFF
            if (gameDat == VERSION_V2 || gameDat == VERSION_V3) return gameDat
        }
        return null
    }

    /** Reads the core magic and version byte; true when strings are UTF-8 (v3). */
    fun readCore(reader: BoundedReader): Boolean = when (val version = readVersion(reader)) {
        VERSION_V2 -> false
        VERSION_V3 -> true
        else -> throw WolfFormatException("Unknown WOLF version header 0x${version.toString(16)}")
    }

    private fun readVersion(reader: BoundedReader): Int {
        val core = reader.readBytes(CORE_MAGIC.size, "WOLF magic")
        if (!core.contentEquals(CORE_MAGIC)) throw WolfFormatException("Not a WOLF data file")
        return reader.readU1()
    }

    /**
     * Validates a standard dat header (core magic + version + file tag) and
     * consumes the trailing per-file revision byte common to this family.
     * Returns true when the file uses the v3 (Unicode) encoding.
     */
    fun readStandardDatHeader(reader: BoundedReader, tagName: Char = 'M'): Boolean {
        val v3 = readCore(reader)
        val expected = if (tagName == 'C') TAG_COMMON_EVENT else TAG_DATA
        val tag = reader.readBytes(expected.size, "file tag '$tagName'")
        if (!tag.contentEquals(expected)) {
            throw WolfFormatException("Unexpected WOLF file tag; expected '$tagName'")
        }
        reader.readU1() // per-file revision byte (e.g. 0xC2 databases, 209/210 tilesets)
        return v3
    }

    /**
     * Validates the closing footer byte against an accepted set.
     */
    fun readFooter(reader: BoundedReader, accepted: Set<Int> = KNOWN_FOOTERS) {
        val footer = reader.readU1()
        if (footer !in accepted) {
            throw WolfFormatException("Invalid WOLF footer byte 0x${footer.toString(16)}")
        }
    }
}
