package io.github.gdlbo.makerplay.runtime.wolf

/**
 * Header decrypt for official WOLF `SaveDataXX.sav` files.
 *
 * Matches Sinflower WolfSave: three MSVC `srand`/`rand` passes from bytes 0, 3, and 9,
 * XORing from offset 0x14 with steps 1, 2, and 5. The bytes before 0x14 stay clear and
 * hold a UTF-8 game name (u16 length plus bytes, including the trailing NUL) and then a
 * u16 file version. On the Sister saves that name field begins at offset 0x15, after the
 * unencrypted seed bytes and the first encrypted byte.
 */
object WolfOfficialSave {
    const val START_OFFSET = 0x14
    const val HIGHEST_SUPPORTED_VERSION = 0x8E
    /** Name-length field in SaveData01.sav, SaveData02.sav, and System.sav. */
    const val SISTER_NAME_OFFSET = 0x15

    fun decrypt(cipher: ByteArray): ByteArray = crypt(cipher, decrypt = true)

    fun encrypt(plain: ByteArray): ByteArray = crypt(plain, decrypt = false)

    fun header(plain: ByteArray): Header {
        val offset = findNameOffset(plain)
        val length = u16(plain, offset)
        val nameStart = offset + 2
        require(nameStart + length + 2 <= plain.size) { "Official Wolf save name overruns the file" }
        val nameBytes = plain.copyOfRange(nameStart, nameStart + length)
        val versionOffset = nameStart + length
        val version = u16(plain, versionOffset)
        val name = nameBytes.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
        return Header(name, version, bodyOffset = versionOffset + 2)
    }

    private fun findNameOffset(plain: ByteArray): Int {
        val preferred = SISTER_NAME_OFFSET
        if (isNameField(plain, preferred)) return preferred
        for (offset in 0 until minOf(plain.size - 4, 64)) {
            if (isNameField(plain, offset)) return offset
        }
        throw IllegalArgumentException("Official Wolf save has no game-name header")
    }

    private fun isNameField(plain: ByteArray, offset: Int): Boolean {
        if (offset + 4 > plain.size) return false
        val length = u16(plain, offset)
        if (length !in 2..120 || offset + 2 + length + 2 > plain.size) return false
        val blob = plain.copyOfRange(offset + 2, offset + 2 + length)
        if (blob.last() != 0.toByte()) return false
        if (blob.dropLast(1).any { (it.toInt() and 0xFF) !in 32..126 }) return false
        val version = u16(plain, offset + 2 + length)
        return version in 1..HIGHEST_SUPPORTED_VERSION
    }

    data class Header(val gameName: String, val version: Int, val bodyOffset: Int)

    /**
     * Length-prefixed UTF-8 paths stored in the save body.
     * Each record is a little-endian u32 byte count, including the trailing NUL.
     */
    fun resourcePaths(plain: ByteArray): List<String> {
        val found = mutableListOf<String>()
        var offset = 0
        while (offset + 4 < plain.size) {
            val length = (plain[offset].toLong() and 0xFF) or
                ((plain[offset + 1].toLong() and 0xFF) shl 8) or
                ((plain[offset + 2].toLong() and 0xFF) shl 16) or
                ((plain[offset + 3].toLong() and 0xFF) shl 24)
            val start = offset + 4
            if (length in 4..200 && start + length <= plain.size) {
                val blob = plain.copyOfRange(start.toInt(), (start + length).toInt())
                if (blob.last() == 0.toByte() && blob.dropLast(1).all { byte ->
                        val value = byte.toInt() and 0xFF
                        value in 32..126
                    }
                ) {
                    val text = blob.dropLast(1).toByteArray().toString(Charsets.US_ASCII)
                    if ('/' in text || '.' in text) found += text
                    offset = (start + length).toInt()
                    continue
                }
            }
            offset++
        }
        return found
    }

    /** First fixed fields of WolfSave `SavePart1_1::Parse`, before the nested parts. */
    data class BodyPrefix(
        val words: List<Long>,
        val flag: Int,
        val blobSize: Int,
    )

    fun bodyPrefix(plain: ByteArray, header: Header): BodyPrefix {
        var offset = header.bodyOffset
        fun u8(): Int {
            require(offset < plain.size) { "Official Wolf save ended in the body prefix" }
            return plain[offset++].toInt() and 0xFF
        }
        fun u16(): Int {
            require(offset + 2 <= plain.size) { "Official Wolf save ended in the body prefix" }
            val value = u16(plain, offset)
            offset += 2
            return value
        }
        fun u32(): Long {
            require(offset + 4 <= plain.size) { "Official Wolf save ended in the body prefix" }
            val value = (plain[offset].toLong() and 0xFF) or
                ((plain[offset + 1].toLong() and 0xFF) shl 8) or
                ((plain[offset + 2].toLong() and 0xFF) shl 16) or
                ((plain[offset + 3].toLong() and 0xFF) shl 24)
            offset += 4
            return value
        }
        val words = listOf(u32(), u32(), u32(), u32())
        val flag = u8()
        val afterFlag = u32()
        val blobSize = u16()
        require(blobSize in 0..plain.size) { "Official Wolf save blob is $blobSize bytes" }
        require(offset + blobSize <= plain.size) { "Official Wolf save blob overruns the file" }
        return BodyPrefix(words + afterFlag, flag, blobSize)
    }

    /**
     * Part-4 character record. Map events in part 1 use the same record, and their
     * raw X/Y are the map-file tiles multiplied by 2.
     */
    data class ResumePoint(val id: Int, val rawX: Int, val rawY: Int) {
        val tileX: Int get() = rawX / 2
        val tileY: Int get() = rawY / 2
    }

    /** The single character record at the start of `SavePart4`. Sister slots are id -2. */
    fun resumePoint(plain: ByteArray, header: Header): ResumePoint {
        val cursor = Cursor(plain, savePart3End(plain, header))
        return ResumePoint(cursor.s32(), cursor.s32(), cursor.s32())
    }

    /**
     * Replaces normal-variable keys (0 and above) with [saved]. Negative keys,
     * including the seeded screen-size slots, stay.
     */
    fun mergeNormalVariables(target: MutableMap<Int, Int>, saved: Map<Int, Int>) {
        target.keys.filter { it >= 0 }.toList().forEach { target.remove(it) }
        target.putAll(saved)
    }

    /** Integer list after the part-4 character records. Sister slots are 100 wide, not 150. */
    fun part4Values(plain: ByteArray, header: Header): List<Int> {
        val cursor = Cursor(plain, savePart3End(plain, header))
        cursor.part1Record(header.version)
        val records = cursor.u32()
        require(records in 0..100_000) { "Official Wolf save part4 records are $records" }
        repeat(records.toInt()) { cursor.part1Record(header.version) }
        cursor.u8(); cursor.u8()
        val count = cursor.u32().toInt()
        require(count in 0..10_000) { "Official Wolf save part4 values are $count" }
        return List(count) { cursor.s32() }
    }

    /** A part-5 picture. Sister backgrounds use negative slots; standing pictures use 90 and up. */
    data class SavedPicture(
        val slot: Int,
        val fileName: String,
        val x: Int,
        val y: Int,
        val divisionWidth: Int,
        val divisionHeight: Int,
        val pattern: Int,
        val opacity: Int,
        val isText: Boolean,
    )

    /** Non-empty part-5 pictures. Sister slot 1 starts with the sunset CG at (0, 0). */
    fun part5Pictures(plain: ByteArray, header: Header): List<SavedPicture> {
        val cursor = Cursor(plain, savePart4End(plain, header))
        cursor.collectPart5Pictures = true
        cursor.part5(header.version)
        return cursor.part5Pictures
    }

    /** First string of each part-5 record. Sister slot 1 has 62, mostly picture paths. */
    fun part5Names(plain: ByteArray, header: Header): List<String> {
        val cursor = Cursor(plain, savePart4End(plain, header))
        cursor.collectPart5Names = true
        cursor.part5(header.version)
        return cursor.part5Names
    }

    /**
     * MakerPlay snapshot for [mapPath]. Variables stay empty: the save's 30 types
     * are `CDataBase`, not the system variable table.
     */
    /** `SysDatabase` type 0 entry [mapId], as a `Data/MapData/...` path. Not `Map001.mps`. */
    fun databaseMapPath(database: io.github.gdlbo.makerplay.wolfformat.DataBaseDat, mapId: Int): String? {
        if (mapId < 0) return null
        val file = database.types.getOrNull(0)?.entries?.getOrNull(mapId)?.strings?.firstOrNull()
            ?.takeIf { it.endsWith(".mps", ignoreCase = true) }
            ?: return null
        return if (file.startsWith("Data/")) file else "Data/$file"
    }

    /** Resume snapshot, or null when the save has no map (System.sav is -1). */
    fun officialSlotState(
        plain: ByteArray,
        database: io.github.gdlbo.makerplay.wolfformat.DataBaseDat,
    ): WolfSaveFormat.GameState? {
        val header = header(plain)
        val mapId = Cursor(plain, header.bodyOffset).s32()
        val path = databaseMapPath(database, mapId) ?: return null
        return resumeState(plain, header, path).copy(variables = normalVariables(plain, header))
    }

    /**
     * First part-3 integer row when it is 800 wide, matching the normal-variable
     * name table. Zeros are omitted; a missing key reads as 0.
     */
    fun normalVariables(plain: ByteArray, header: Header): Map<Int, Int> {
        val cursor = Cursor(plain, savePart2End(plain, header))
        cursor.u32()
        val groups = cursor.s32()
        if (groups < 0) return emptyMap()
        repeat(groups) {
            val inner = cursor.s32()
            if (inner < 0) return emptyMap()
            repeat(inner) {
                val width = cursor.u8()
                repeat(width) { cursor.u32() }
            }
        }
        if (cursor.u32() == 0L) return emptyMap()
        val width = cursor.s32()
        if (width != 800) return emptyMap()
        val values = LinkedHashMap<Int, Int>()
        repeat(width) { index ->
            val value = cursor.s32()
            if (value != 0) values[index] = value
        }
        return values
    }

    fun resumeState(plain: ByteArray, header: Header, mapPath: String): WolfSaveFormat.GameState {
        val point = resumePoint(plain, header)
        return WolfSaveFormat.GameState(
            title = header.gameName,
            mapPath = mapPath,
            tileX = point.tileX,
            tileY = point.tileY,
            variables = emptyMap(),
            strings = emptyMap(),
        )
    }

    /** Part-1 character records, in file order. Raw coordinates, not tiles. */
    fun mapCharacters(plain: ByteArray, header: Header): List<Triple<Int, Int, Int>> {
        val cursor = Cursor(plain, header.bodyOffset)
        cursor.collectCharacters = true
        walkPart1(cursor, header.version)
        return cursor.characters
    }

    /** Width, height, and first cell of the part-1 tile grid. Sister slots are 64 by 36. */
    fun mapGrid(plain: ByteArray, header: Header): Triple<Int, Int, Long> {
        val cursor = Cursor(plain, header.bodyOffset)
        cursor.u32(); cursor.u32(); cursor.u32()
        val columns = cursor.u32().toInt()
        val rows = cursor.u32().toInt()
        val first = cursor.u32()
        return Triple(columns, rows, first)
    }

    /**
     * Offset just after WolfSave `SavePart1::Parse` for a version 0x8C file.
     * The two Sister slot saves both end this part at byte 34512.
     */
    fun savePart1End(plain: ByteArray, header: Header): Int {
        val cursor = Cursor(plain, header.bodyOffset)
        walkPart1(cursor, header.version)
        return cursor.offset
    }

    private fun walkPart1(cursor: Cursor, version: Int) {
        cursor.u32(); cursor.u32(); cursor.u32()
        if (version >= 0x69) {
            val columns = cursor.u32()
            val rows = cursor.u32()
            val marker = cursor.u32()
            if (marker != 0xFFFF_FFFFL) {
                cursor.rewind(4)
                val cells = 3L * columns * rows
                require(cells in 0..5_000_000) { "Official Wolf save grid is $cells" }
                repeat(cells.toInt()) { cursor.u32() }
            }
        }
        cursor.mem(4)
        val stringCount = if (version >= 0x8A) 31 else if (version >= 0x73) 15 else 7
        repeat(stringCount) { cursor.mem(4) }
        val countA = cursor.u32()
        require(countA in 0..1_000_000) { "Official Wolf save count A is $countA" }
        repeat(countA.toInt()) { cursor.u32() }
        val records = cursor.u32()
        require(records in 0..100_000) { "Official Wolf save record count is $records" }
        repeat(records.toInt()) { cursor.part1Record(version) }
        if (version >= 0x72) repeat(11) { cursor.u32() }
        val countB = cursor.u32()
        require(countB in 0..1_000_000) { "Official Wolf save count B is $countB" }
        repeat(countB.toInt()) { cursor.u32() }
    }

    /** Offset just after WolfSave `SavePart2::Parse` for a version 0x8C slot save. */
    fun savePart2End(plain: ByteArray, header: Header): Int {
        val cursor = Cursor(plain, savePart1End(plain, header))
        cursor.part2(header.version)
        return cursor.offset
    }

    /** Offset just after WolfSave `SavePart3::Parse`. The two slot saves differ here. */
    fun savePart3End(plain: ByteArray, header: Header): Int {
        val cursor = Cursor(plain, savePart2End(plain, header))
        cursor.part3()
        return cursor.offset
    }

    /** Offset just after WolfSave `SavePart4::Parse`. */
    fun savePart4End(plain: ByteArray, header: Header): Int {
        val cursor = Cursor(plain, savePart3End(plain, header))
        cursor.part4(header.version)
        return cursor.offset
    }

    /** Bytes left after `SavePart5`. Both Sister slot saves leave 81831. */
    fun bytesAfterPart5(plain: ByteArray, header: Header): Int {
        val cursor = Cursor(plain, savePart4End(plain, header))
        cursor.part5(header.version)
        return plain.size - cursor.offset
    }

    /** Bytes left after the variable database and `SavePart7`. WolfSave expects 1. */
    fun bytesLeft(plain: ByteArray, header: Header): Int {
        val cursor = Cursor(plain, plain.size - bytesAfterPart5(plain, header))
        cursor.variableDatabase()
        cursor.part7()
        return plain.size - cursor.offset
    }

    /** Picture and audio paths stored in `SavePart2`. Sister slots name the home CG. */
    fun scenePaths(plain: ByteArray, header: Header): List<String> {
        val cursor = Cursor(plain, savePart1End(plain, header))
        cursor.collectStrings = true
        cursor.part2(header.version)
        return cursor.strings
    }

    /** One saved `CDataBase` row: number pool, then string pool. */
    data class SavedDatabaseRow(val numbers: IntArray, val strings: List<String>)

    /** Saved `CDataBase` rows. Type 0 entry 0 starts at 99 in the Sister slot saves, not the static 1. */
    fun userDatabaseRows(plain: ByteArray, header: Header): List<List<SavedDatabaseRow>> {
        val cursor = Cursor(plain, plain.size - bytesAfterPart5(plain, header))
        cursor.collectUserRows = true
        cursor.variableDatabase()
        return cursor.userRows
    }

    /** Sum of every numeric database cell. Slot saves that differ should not match. */
    fun variableChecksum(plain: ByteArray, header: Header): Long {
        val cursor = Cursor(plain, plain.size - bytesAfterPart5(plain, header))
        cursor.variableDatabase()
        return cursor.numberSum
    }

    private class Cursor(private val plain: ByteArray, var offset: Int) {
        var numberSum: Long = 0
        var collectStrings: Boolean = false
        val strings = mutableListOf<String>()
        var collectCharacters: Boolean = false
        val characters = mutableListOf<Triple<Int, Int, Int>>()
        var collectUserRows: Boolean = false
        val userRows = mutableListOf<List<SavedDatabaseRow>>()
        var collectPart5Names: Boolean = false
        val part5Names = mutableListOf<String>()
        var collectPart5Pictures: Boolean = false
        val part5Pictures = mutableListOf<SavedPicture>()
        fun rewind(bytes: Int) { offset -= bytes }
        fun u8(): Int {
            require(offset < plain.size) { "Official Wolf save ended at $offset" }
            return plain[offset++].toInt() and 0xFF
        }
        fun u16(): Int {
            require(offset + 2 <= plain.size) { "Official Wolf save ended at $offset" }
            val value = (plain[offset].toInt() and 0xFF) or ((plain[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            return value
        }
        fun u32(): Long {
            require(offset + 4 <= plain.size) { "Official Wolf save ended at $offset" }
            val value = (plain[offset].toLong() and 0xFF) or
                ((plain[offset + 1].toLong() and 0xFF) shl 8) or
                ((plain[offset + 2].toLong() and 0xFF) shl 16) or
                ((plain[offset + 3].toLong() and 0xFF) shl 24)
            offset += 4
            return value
        }
        fun mem(width: Int): String {
            val size = if (width == 2) u16().toLong() else u32()
            require(size in 0..plain.size) { "Official Wolf save blob is $size" }
            require(offset + size <= plain.size) { "Official Wolf save blob overruns" }
            val blob = if (size == 0L) {
                ByteArray(0)
            } else {
                plain.copyOfRange(offset, offset + size.toInt())
            }
            val end = blob.indexOf(0)
            val text = (if (end >= 0) blob.copyOfRange(0, end) else blob).toString(Charsets.UTF_8)
            if (collectStrings && size in 2..200 && ('/' in text || '.' in text)) strings += text
            offset += size.toInt()
            return text
        }
        fun part111() {
            u8()
            val dwords = u8()
            repeat(dwords) { u32() }
            val bytes = u8()
            repeat(bytes) { u8() }
        }
        fun part11() {
            repeat(6) { u8() }
            val count = u32()
            require(count in 0..0x10000) { "Official Wolf save list is $count" }
            repeat(count.toInt()) { part111() }
        }
        fun part1Record(version: Int) {
            val id = s32()
            val rawX = s32()
            val rawY = s32()
            u32()
            if (collectCharacters) characters += Triple(id, rawX, rawY)
            u8(); u32(); mem(2)
            repeat(4) { u16() }
            u8(); u8()
            part11(); part11()
            repeat(6) { u16() }
            u8(); u8(); u8()
            val values = u32()
            require(values in 0..1_000_000) { "Official Wolf save value list is $values" }
            repeat(values.toInt()) { u32() }
            if (version >= 0x70) { u8(); u8() }
            if (version >= 0x73) repeat(4) { u32() }
            if (version >= 0x78) u32()
            if (version >= 0x85) repeat(3) { u32() }
            if (version >= 0x8A) {
                val texts = u16()
                require(texts in 0..100_000) { "Official Wolf save text list is $texts" }
                repeat(texts) { mem(2) }
                u32(); u8(); u32(); u32()
            }
            if (version >= 0x8B) u32()
            if (version >= 0x8C) u32()
        }

        fun part2(version: Int) {
            u8(); u8()
            repeat(19) { u32() }
            repeat(3) { u16() }
            u32()
            repeat(3) { u16() }
            repeat(9) { u32() }
            u8(); u16(); u32(); u32()
            repeat(4) { u16() }
            repeat(3) { u32() }
            u8()
            if (version <= 96) u32()
            u32()
            if (version >= 98) { u32(); u32() }
            if (version >= 100) {
                u32(); u32(); u8()
                val texts = u32()
                require(texts in 0..100_000) { "Official Wolf save part2 texts are $texts" }
                repeat(texts.toInt()) { mem(4) }
                val values = u32()
                require(values in 0..1_000_000) { "Official Wolf save part2 values are $values" }
                repeat(values.toInt()) { u32() }
                repeat(5) { u32() }
            }
            if (version >= 101) repeat(6) { u32() }
            if (version >= 102) u16()
            if (version >= 103) { mem(2); mem(2) }
            if (version >= 104) u32()
            if (version >= 106) repeat(3) { u32() }
            if (version >= 108) {
                mem(2); repeat(3) { u32() }
                mem(2); repeat(3) { u32() }
            }
            if (version >= 109) { mem(2); mem(2); u8() }
            if (version >= 110) u8()
            if (version >= 119) { mem(2); mem(2); mem(2) }
            for (gate in intArrayOf(121, 122, 124, 126, 128)) if (version >= gate) u32()
            if (version >= 129) { u32(); u32() }
            if (version >= 130) u32()
            if (version >= 131) u32()
            if (version >= 132) repeat(4) { u32() }
            if (version >= 134) u8()
            if (version >= 136) u8()
            if (version >= 137) repeat(28) { u32() }
            if (version >= 0x8A) {
                repeat(4) { u32() }
                mem(4)
                val more = u32()
                require(more in 0..100_000) { "Official Wolf save part2 tail is $more" }
                repeat(more.toInt()) { mem(4) }
            }
        }

        fun part3() {
            u32()
            val groups = s32()
            if (groups < 0) return
            require(groups <= 100_000) { "Official Wolf save part3 groups are $groups" }
            repeat(groups) {
                val inner = s32()
                require(inner >= 0) { "Official Wolf save part3 inner is $inner" }
                repeat(inner) {
                    val width = u8()
                    repeat(width) { u32() }
                }
            }
            val count = u32()
            if (count > 0x270F) return
            repeat(count.toInt()) {
                val inner = s32()
                require(inner >= 0) { "Official Wolf save part3 row is $inner" }
                repeat(inner) { u32() }
            }
            val texts = s32()
            if (texts < 0) return
            require(texts <= 100_000) { "Official Wolf save part3 texts are $texts" }
            repeat(texts) { mem(4) }
            val rows = u32()
            require(rows <= 10_000) { "Official Wolf save part3 rows are $rows" }
            repeat(rows.toInt()) {
                val width = u8()
                repeat(width) { u32() }
            }
            val named = u32()
            if (named > 10_000) return
            require(named > 0) { "Official Wolf save part3 names are empty" }
            repeat(named.toInt()) {
                val width = u8()
                if (width != 0) repeat(width) { mem(4) }
            }
        }

        fun part4(version: Int) {
            part1Record(version)
            val records = u32()
            require(records in 0..100_000) { "Official Wolf save part4 records are $records" }
            repeat(records.toInt()) { part1Record(version) }
            u8(); u8()
            val values = u32()
            require(values in 0..1_000_000) { "Official Wolf save part4 values are $values" }
            repeat(values.toInt()) { u32() }
            if (version >= 0x8A) {
                val wide = u32()
                require(wide in 0..1_000_000) { "Official Wolf save part4 wide values are $wide" }
                repeat(wide.toInt()) {
                    require(offset + 8 <= plain.size) { "Official Wolf save ended at $offset" }
                    offset += 8
                }
            }
        }

        fun part5(version: Int) {
            val count = u16()
            if ((count and 0x8000) != 0) return
            require(count <= 10_000) { "Official Wolf save part5 count is $count" }
            repeat(count) { part5Record(version) }
        }

        fun part5Record(version: Int) {
            val slot = s32()
            u8(); u8(); u16(); u8(); u8()
            val picture = mem(2)
            if (collectPart5Names) part5Names += picture
            val x = s32()
            val y = s32()
            val divisionWidth = s32()
            val divisionHeight = s32()
            val pattern = s32()
            val opacity = s32()
            repeat(12) { u32() }
            if (collectPart5Pictures && picture.isNotEmpty()) {
                val isText = '/' !in picture && '.' !in picture
                part5Pictures += SavedPicture(
                    slot, picture, x, y, divisionWidth, divisionHeight, pattern, opacity, isText,
                )
            }
            if (version >= 0x69) repeat(18) { u32() }
            if (version >= 0x6B) u8()
            if (version >= 0x72) {
                u8(); u8(); repeat(8) { u32() }
            }
            if (version >= 0x73) {
                repeat(4) { u32() }
                if (version >= 0x74) repeat(5) { u32() }
                if (version >= 0x75) repeat(6) { u32() }
                repeat(6) { u32() }
            }
            if (version >= 0x76) repeat(3) { u32() }
            if (version < 0x81) return
            repeat(21) { u32() }
            if (version >= 0x87) repeat(5) { u32() }
            if (version >= 0x89) {
                val groups = u32()
                require(groups in 0..100_000) { "Official Wolf save part5 groups are $groups" }
                repeat(groups.toInt()) {
                    val inner = u32()
                    require(inner in 0..100_000) { "Official Wolf save part5 inner is $inner" }
                    repeat(inner.toInt()) { u32() }
                }
                repeat(7) { u32() }
            }
        }

        fun variableDatabase() {
            u8()
            val types = u32()
            require(types in 0..100_000) { "Official Wolf save type count is $types" }
            repeat(types.toInt()) { variableType() }
        }

        fun variableType() {
            var fields = s32()
            if (fields <= -1) {
                if (fields <= -2) s32()
                fields = s32()
            }
            val config = mutableListOf<Long>()
            if (fields > 0) {
                require(fields <= 10_000) { "Official Wolf save field count is $fields" }
                repeat(fields) { config += u32() }
            }
            val rows = u32()
            require(rows in 0..1_000_000) { "Official Wolf save rows are $rows" }
            val numbers = config.count { it < 2000L }
            val strings = config.count { it >= 2000L }
            val typeRows = ArrayList<SavedDatabaseRow>(rows.toInt())
            repeat(rows.toInt()) {
                val nums = IntArray(numbers)
                repeat(numbers) { index ->
                    val value = s32()
                    numberSum += value.toLong()
                    nums[index] = value
                }
                val texts = List(strings) { mem(4) }
                if (collectUserRows) typeRows += SavedDatabaseRow(nums, texts)
            }
            if (collectUserRows) userRows += typeRows
        }

        fun part7() {
            val flag = u8()
            if (flag != 1) return
            val count = u32()
            require(count in 0..1_000_000) { "Official Wolf save part7 count is $count" }
            repeat(count.toInt()) {
                val kind = u8()
                if (kind < 0xFA) u8() else u32()
            }
        }

        fun s32(): Int {
            val value = u32()
            return if (value >= 0x8000_0000L) (value - 0x1_0000_0000L).toInt() else value.toInt()
        }
    }

    private fun crypt(input: ByteArray, decrypt: Boolean): ByteArray {
        val out = input.copyOf()
        if (out.size <= START_OFFSET) return out
        val b0 = out[0].toInt() and 0xFF
        val b3 = out[3].toInt() and 0xFF
        val b9 = out.getOrElse(9) { 0 }.toInt() and 0xFF
        val passes = if (decrypt) {
            listOf(b0 to 1, b3 to 2, b9 to 5)
        } else {
            listOf(b9 to 5, b3 to 2, b0 to 1)
        }
        for ((seed, step) in passes) {
            val rng = MsvcRand(seed)
            var index = START_OFFSET
            while (index < out.size) {
                out[index] = (out[index].toInt() xor (rng.next() shr 12)).toByte()
                index += step
            }
        }
        return out
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    /** MSVC `rand`: `hold = hold * 214013 + 2531011`, return bits 16..30. */
    private class MsvcRand(seed: Int) {
        private var hold = seed.toLong() and 0xFFFF_FFFFL

        fun next(): Int {
            hold = (hold * 214013L + 2531011L) and 0xFFFF_FFFFL
            return ((hold shr 16) and 0x7FFF).toInt()
        }
    }
}
