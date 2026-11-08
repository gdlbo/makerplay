package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.wolfformat.DataBaseDat
import io.github.gdlbo.makerplay.wolfformat.DataBaseProject
import io.github.gdlbo.makerplay.wolfformat.EventCommand
import io.github.gdlbo.makerplay.wolfformat.GameDataSource

/**
 * Mutable runtime view of the three standard database files plus helpers for
 * opcode 250 (and no-op stubs for the import family).
 *
 * Flag packing for params[3] (little-endian bytes), confirmed against
 * `GamePro.exe` in docs/wolf-ida-vm-reference.md (case 250 of sub_9E20C0 and
 * the executor sub_A0CFE0):
 * - b0: operator (hi nibble) + rhs kind (lo nibble: 0 number, 1 indirect, 2 string)
 * - b1: name-presence flags + **bank select** (low nibble)
 * - b2: name flags (1 type, 2 entry, 4 field)
 *
 * The bank nibble is not just "which DB": the engine has four stores, and
 * `0, 1, 2` are the changeable, system and user databases while `3` is the XY
 * array (`case 255000..255300` -> sub_AED340 in sub_A0CFE0).
 */
class WolfDatabase private constructor(
    private val banks: Array<Bank?>,
    private val source: GameDataSource? = null,
) {
    /** XY array store (bank nibble 3). */
    private val xyArrays = HashMap<Int, XyArray>()

    enum class Kind { CHANGEABLE, SYSTEM, USER }

    /**
     * XY arrays are not database types: they are 2-D integer arrays addressed by
     * `(array index, x, y)` with no field layout. `GamePro.exe` grows them lazily
     * (`sub_AED340` extends rows and columns on first write).
     */
    class XyArray {
        private val rows = HashMap<Int, HashMap<Int, Int>>()

        fun read(x: Int, y: Int): Int = rows[x]?.get(y) ?: 0

        fun write(x: Int, y: Int, value: Int, operator: Int) {
            val row = rows.getOrPut(x) { HashMap() }
            val current = row[y] ?: 0
            // Operators arrive on two different scales: the DB path hands over
            // the low-byte nibble (0x10/0x20/...) while the engine constants in
            // sub_AED340 are 0x100..0x400. Normalise to the latter.
            val op = if (operator in 1..0xF) operator shl 8 else if (operator <= 0xF0) operator shl 4 else operator
            row[y] = when (op) {
                0x100 -> current + value
                0x200 -> current - value
                0x300 -> current * value
                // sub_AED340 substitutes 1 for a zero divisor instead of
                // faulting: `v12 = a4; if (a4 == 0) v12 = 1;`
                0x400 -> current / (if (value != 0) value else 1)
                0x500 -> current % (if (value != 0) value else 1)
                else -> value
            }
        }

        fun clear() = rows.clear()
    }

    class Bank(
        val kind: Kind,
        val types: List<TypeState>,
        private val typesByName: Map<String, Int>,
    ) {
        fun typeIndex(name: String): Int? = typesByName[name]
        fun typeAt(index: Int): TypeState? = types.getOrNull(index)
    }

    class TypeState(
        val name: String,
        val fieldNames: List<String>,
        val entryNames: MutableList<String>,
        val positions: IntArray,
        val entries: MutableList<EntryState>,
    ) {
        private val fieldsByName: Map<String, Int> =
            fieldNames.withIndex().associate { (i, n) -> n to i }
        private val entriesByName: MutableMap<String, Int> =
            entryNames.withIndex().associate { (i, n) -> n to i }.toMutableMap()

        fun fieldIndex(name: String): Int? = fieldsByName[name]
        fun entryIndex(name: String): Int? = entriesByName[name]

        fun isStringField(field: Int): Boolean {
            val pos = positions.getOrNull(field) ?: return false
            return pos >= DataBaseDat.STRING_START
        }

        fun isNumberField(field: Int): Boolean {
            val pos = positions.getOrNull(field) ?: return false
            return pos in DataBaseDat.INT_START until DataBaseDat.STRING_START
        }

        fun poolIndex(field: Int): Int {
            val pos = positions.getOrNull(field) ?: return -1
            return when {
                pos >= DataBaseDat.STRING_START -> pos - DataBaseDat.STRING_START
                pos >= DataBaseDat.INT_START -> pos - DataBaseDat.INT_START
                else -> -1
            }
        }

        fun readNumber(entry: Int, field: Int): Int {
            val e = entries.getOrNull(entry) ?: return 0
            val slot = poolIndex(field)
            return e.numbers.getOrNull(slot) ?: 0
        }

        fun readString(entry: Int, field: Int): String {
            val e = entries.getOrNull(entry) ?: return ""
            val slot = poolIndex(field)
            return e.strings.getOrNull(slot) ?: ""
        }

        fun writeNumber(entry: Int, field: Int, value: Int, op: Int) {
            ensureEntry(entry)
            val e = entries[entry]
            val slot = poolIndex(field)
            if (slot !in e.numbers.indices) return
            val cur = e.numbers[slot]
            e.numbers[slot] = applyOp(cur, value, op)
        }

        fun writeString(entry: Int, field: Int, value: String) {
            ensureEntry(entry)
            val e = entries[entry]
            val slot = poolIndex(field)
            if (slot !in e.strings.indices) return
            e.strings[slot] = value
        }

        fun clearValues() {
            for (entry in entries) {
                entry.numbers.fill(0)
                for (index in entry.strings.indices) entry.strings[index] = ""
            }
        }

        fun replaceFromRows(rows: List<List<String>>) {
            entries.clear()
            entryNames.clear()
            entriesByName.clear()
            for (row in rows) {
                val name = row.firstOrNull().orEmpty()
                val values = if (row.size > 1) row.drop(1) else row
                val numbers = IntArray(numberPoolSize())
                val strings = MutableList(stringPoolSize()) { "" }
                var numberSlot = 0
                var stringSlot = 0
                for (field in fieldNames.indices) {
                    val cell = values.getOrNull(field) ?: continue
                    if (isStringField(field)) {
                        if (stringSlot < strings.size) strings[stringSlot++] = cell
                    } else if (isNumberField(field) && numberSlot < numbers.size) {
                        numbers[numberSlot++] = cell.toIntOrNull() ?: 0
                    }
                }
                val index = entries.size
                entries.add(EntryState(numbers, strings))
                entryNames.add(name)
                if (name.isNotEmpty()) entriesByName[name] = index
            }
        }

        private fun ensureEntry(entry: Int) {
            while (entries.size <= entry) {
                val sample = entries.firstOrNull()
                val numbers = IntArray(sample?.numbers?.size ?: numberPoolSize()) { 0 }
                val strings = MutableList(sample?.strings?.size ?: stringPoolSize()) { "" }
                entries.add(EntryState(numbers, strings))
                entryNames.add("")
            }
        }

        private fun numberPoolSize(): Int =
            positions.count { it in DataBaseDat.INT_START until DataBaseDat.STRING_START }

        private fun stringPoolSize(): Int =
            positions.count { it >= DataBaseDat.STRING_START }
    }

    class EntryState(
        val numbers: IntArray,
        val strings: MutableList<String>,
    )

    fun bank(kind: Kind): Bank? = banks[kind.ordinal]

    /** Copies saved `CDataBase` numbers and strings onto the bank with the same type count. */
    fun applySavedUserRows(rows: List<List<WolfOfficialSave.SavedDatabaseRow>>): Int {
        val bank = banks.firstOrNull { it != null && it.types.size == rows.size }
            ?: bank(Kind.CHANGEABLE)
            ?: return 0
        var applied = 0
        for ((typeIndex, typeRows) in rows.withIndex()) {
            val type = bank.typeAt(typeIndex) ?: continue
            for ((entryIndex, row) in typeRows.withIndex()) {
                val entry = type.entries.getOrNull(entryIndex) ?: continue
                var wrote = false
                if (entry.numbers.size == row.numbers.size) {
                    row.numbers.copyInto(entry.numbers)
                    wrote = true
                }
                if (entry.strings.size == row.strings.size) {
                    row.strings.forEachIndexed { index, value -> entry.strings[index] = value }
                    wrote = true
                }
                if (wrote) applied++
            }
        }
        return applied
    }

    fun execute(
        command: EventCommand,
        readNumber: (Int) -> Int,
        writeNumber: (Int, Int) -> Unit,
        readString: (Int) -> String,
        writeString: (Int, String) -> Unit,
    ) {
        when (command.commandType) {
            250, 252 -> executeAssign(command, readNumber, writeNumber, readString, writeString)
            251 -> importCsv(command)
            // 255/256/257 are the XY-array family, handled by their own
            // engine routines (sub_A2ACA0 / sub_A2BD90 / sub_A2C500), not by
            // the database path.
            255, 256, 257 -> executeXyArray(command, readNumber, writeNumber)
            else -> Unit
        }
    }

    /** Opcode 251. strings[0] is a CSV path and strings[1] is the user-database type. */
    private fun importCsv(command: EventCommand) {
        val file = command.strings.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return
        val typeName = command.strings.getOrNull(1).orEmpty()
        val user = bank(Kind.USER) ?: bank(Kind.CHANGEABLE) ?: return
        val typeIdx = if (typeName.isNotEmpty()) {
            user.typeIndex(typeName)
        } else {
            command.params.getOrNull(0)?.takeIf { it >= 0 }
        } ?: return
        val type = user.typeAt(typeIdx) ?: return
        val bytes = readImport(file) ?: return
        val rows = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { splitCsv(it) }
            .toList()
        if (rows.isEmpty()) return
        val header = rows.first()
        val body = if (header.any { it == type.name || type.fieldIndex(it) != null }) rows.drop(1) else rows
        type.replaceFromRows(body)
    }

    /**
     * Clears a named type's values before it is rebuilt.
     *
     * Opcode 255 is an XY-array operation in this engine build
     * (sub_A2ACA0), so this helper is no longer dispatched from `execute`.
     * It is kept because the entry/field sentinel path in [handleMeta] still
     * uses the same notion of an empty type.
     */
    @Suppress("unused")
    private fun clearNamedType(command: EventCommand) {
        val typeName = command.strings.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: command.strings.firstOrNull { it.isNotBlank() }
            ?: return
        val type = sequenceOf(Kind.USER, Kind.CHANGEABLE, Kind.SYSTEM).firstNotNullOfOrNull { kind ->
            val found = bank(kind) ?: return@firstNotNullOfOrNull null
            found.typeIndex(typeName)?.let { found.typeAt(it) }
        } ?: return
        type.clearValues()
    }

    private fun findNamedType(name: String): TypeState? {
        if (name.isEmpty()) return null
        for (kind in Kind.entries) {
            val found = bank(kind) ?: continue
            val index = found.typeIndex(name) ?: continue
            return found.typeAt(index)
        }
        return null
    }

    private fun typeIndexOf(type: TypeState): Int {
        for (kind in Kind.entries) {
            val found = bank(kind) ?: continue
            val index = found.typeIndex(type.name)
            if (index != null && found.typeAt(index) === type) return index
        }
        return 0
    }

    private fun readImport(path: String): ByteArray? {
        val reader = source ?: return null
        val trimmed = path.removePrefix("/")
        val candidates = listOf(trimmed, "Data/$trimmed", "Data/${trimmed.removePrefix("Data/")}")
        for (candidate in candidates.distinct()) {
            val bytes = runCatching { reader.read(candidate) }.getOrNull()
            if (bytes != null) return bytes
        }
        return null
    }

    private fun splitCsv(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        for (ch in line) {
            when {
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    cells += current.toString().trim()
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        cells += current.toString().trim()
        return cells
    }

    private fun executeAssign(
        command: EventCommand,
        readNumber: (Int) -> Int,
        writeNumber: (Int, Int) -> Unit,
        readString: (Int) -> String,
        writeString: (Int, String) -> Unit,
    ) {
        val p = command.params
        val s = command.strings
        if (p.size < 4) return

        val flags = p[3]
        val opByte = flags and 0xFF
        val modeByte = (flags ushr 8) and 0xFF
        val nameFlags = (flags ushr 16) and 0x0F
        val operator = opByte and 0xF0
        val rhsKind = opByte and 0x0F
        val mode = when (modeByte and 0xF0) {
            0x20 -> MODE_WRITE
            else -> modeByte and 0xF0
        }
        val dbKind = when (modeByte and 0x0F) {
            0 -> Kind.CHANGEABLE
            1 -> Kind.SYSTEM
            2, 3 -> Kind.USER
            // GamePro.exe's opcode-250 handler (case 250 of sub_9E20C0) maps
            // banks 0/1/2 and jumps out for anything else; XY arrays are
            // separate opcodes (255/256/257), not a bank of this command.
            else -> return
        }
        val bank = bank(dbKind)
        val typeName = s.getOrNull(1).orEmpty()
        val type = bank?.let { found ->
            resolveType(found, p[0], typeName, nameFlags)?.let { found.typeAt(it) }
        } ?: findNamedType(typeName) ?: return
        val typeIdx = typeIndexOf(type)

        // Meta ops use sentinel field / entry markers.
        val entryRaw = p.getOrElse(1) { 0 }
        val fieldRaw = p.getOrElse(2) { 0 }
        if (isMeta(entryRaw, fieldRaw)) {
            handleMeta(
                type = type,
                typeIdx = typeIdx,
                entryRaw = entryRaw,
                fieldRaw = fieldRaw,
                mode = mode,
                valueRaw = p.getOrElse(4) { 0 },
                typeNameHint = s.getOrNull(1).orEmpty(),
                entryNameHint = s.getOrNull(2).orEmpty(),
                fieldNameHint = s.getOrNull(3).orEmpty(),
                readNumber = readNumber,
                writeNumber = writeNumber,
                writeString = writeString,
            )
            return
        }

        val entryIdx = resolveEntry(type, entryRaw, s.getOrNull(2).orEmpty(), nameFlags, readNumber)
            ?: return
        val fieldIdx = resolveField(type, fieldRaw, s.getOrNull(3).orEmpty(), nameFlags, readNumber)
            ?: return

        when (mode) {
            MODE_READ -> {
                val dest = p.getOrElse(4) { return }
                if (type.isStringField(fieldIdx)) {
                    writeString(dest, type.readString(entryIdx, fieldIdx))
                } else {
                    writeNumber(dest, type.readNumber(entryIdx, fieldIdx))
                }
            }
            MODE_WRITE -> {
                if (type.isStringField(fieldIdx) || rhsKind == RHS_STRING) {
                    val value = when (rhsKind) {
                        RHS_STRING -> s.getOrNull(0).orEmpty()
                        else -> readString(p.getOrElse(4) { 0 })
                    }
                    // String fields only support overwrite in this subset.
                    type.writeString(entryIdx, fieldIdx, value)
                } else {
                    val value = resolveRhsNumber(p.getOrElse(4) { 0 }, rhsKind, readNumber)
                    type.writeNumber(entryIdx, fieldIdx, value, operator)
                }
            }
            else -> Unit
        }
    }

    private fun handleMeta(
        type: TypeState,
        typeIdx: Int,
        entryRaw: Int,
        fieldRaw: Int,
        mode: Int,
        valueRaw: Int,
        typeNameHint: String,
        entryNameHint: String,
        fieldNameHint: String,
        readNumber: (Int) -> Int,
        writeNumber: (Int, Int) -> Unit,
        writeString: (Int, String) -> Unit,
    ) {
        if (mode == MODE_WRITE && (entryRaw == SENTINEL_CLEAR || fieldRaw == SENTINEL_CLEAR)) {
            type.clearValues()
            return
        }
        if (mode != MODE_READ) {
            return
        }
        when {
            entryRaw == SENTINEL_NAME && fieldRaw == SENTINEL_NAME -> {
                // Type name <-> type number
                if (typeNameHint.isNotEmpty() && typeIdx == 0 && type.name != typeNameHint) {
                    // Number-from-name is resolved earlier; treat as name fetch.
                }
                writeString(valueRaw, type.name)
            }
            fieldRaw == SENTINEL_NAME && entryRaw != SENTINEL_NAME && entryRaw != SENTINEL_CLEAR -> {
                val entry = resolveLiteralOrRef(entryRaw, readNumber)
                writeString(valueRaw, type.entryNames.getOrNull(entry).orEmpty())
            }
            entryRaw == SENTINEL_NAME -> {
                // Field name fetch / field number from name
                if (fieldNameHint.isNotEmpty()) {
                    writeNumber(valueRaw, type.fieldIndex(fieldNameHint) ?: -1)
                } else {
                    val field = resolveLiteralOrRef(fieldRaw, readNumber)
                    writeString(valueRaw, type.fieldNames.getOrNull(field).orEmpty())
                }
            }
            fieldRaw == SENTINEL_NAME && entryRaw == 0 -> {
                // Data number from name
                writeNumber(valueRaw, type.entryIndex(entryNameHint) ?: -1)
            }
            else -> Unit
        }
        // Common “length” style reads: field == -1 variants appear as 0xFFFFFFFF
        // in some dumps; expose entry/field counts when value dest is present.
        if (fieldRaw == -1) {
            writeNumber(valueRaw, type.entries.size)
        } else if (entryRaw == -1) {
            writeNumber(valueRaw, type.fieldNames.size)
        }
    }

    private fun resolveType(
        bank: Bank,
        raw: Int,
        name: String,
        nameFlags: Int,
    ): Int? {
        if ((nameFlags and NAME_TYPE) != 0 && name.isNotEmpty()) {
            return bank.typeIndex(name) ?: raw.takeIf { it >= 0 }
        }
        return raw.takeIf { it >= 0 }
    }

    private fun resolveEntry(
        type: TypeState,
        raw: Int,
        name: String,
        nameFlags: Int,
        readNumber: (Int) -> Int,
    ): Int? {
        if ((nameFlags and NAME_ENTRY) != 0 && name.isNotEmpty()) {
            return type.entryIndex(name) ?: resolveLiteralOrRef(raw, readNumber).takeIf { it >= 0 }
        }
        return resolveLiteralOrRef(raw, readNumber).takeIf { it >= 0 }
    }

    private fun resolveField(
        type: TypeState,
        raw: Int,
        name: String,
        nameFlags: Int,
        readNumber: (Int) -> Int,
    ): Int? {
        if ((nameFlags and NAME_FIELD) != 0 && name.isNotEmpty()) {
            return type.fieldIndex(name) ?: resolveLiteralOrRef(raw, readNumber).takeIf { it >= 0 }
        }
        return resolveLiteralOrRef(raw, readNumber).takeIf { it >= 0 }
    }

    private fun resolveLiteralOrRef(raw: Int, readNumber: (Int) -> Int): Int =
        if (raw >= 1_000_000 || raw in 1_600_000..1_699_999) readNumber(raw) else raw

    /**
     * XY array access (bank nibble 3).
     *
     * `GamePro.exe` models these as 2-D integer arrays keyed by an array
     * selector plus x/y (see sub_AED340 in docs/wolf-ida-vm-reference.md).
     * Param roles here follow the same slot order the DB path uses:
     * `p[0]` array selector, `p[1]` x/column, `p[2]` y/row, `p[4]` value or
     * destination. Direction comes from the operator byte: an assignment
     * operator reads into `p[4]`, a modifying operator writes from it.
     */
    private fun executeXyArray(
        command: EventCommand,
        readNumber: (Int) -> Int,
        writeNumber: (Int, Int) -> Unit,
    ) {
        val p = command.params
        if (p.size < 4) return
        val flags = p[3]
        // Engine layout (sub_A2C500): bits 12..15 select the action.
        //   0 = read a cell into the destination operand
        //   1..3 = write forms used by the 255/256/257 variants
        val action = (flags ushr 12) and 0xF
        val operator = (flags ushr 4) and 0xF
        val arrayIndex = p.getOrElse(0) { 0 }
        val x = resolveXyCoordinate(p.getOrElse(1) { 0 }, readNumber)
        val y = resolveXyCoordinate(p.getOrElse(2) { 0 }, readNumber)
        val array = xyArrays.getOrPut(arrayIndex) { XyArray() }
        if (action == 0) {
            val dest = p.getOrElse(4) { return }
            writeNumber(dest, array.read(x, y))
            return
        }
        val raw = p.getOrElse(4) { 0 }
        val rhsKind = flags and 0xF
        array.write(x, y, resolveRhsNumber(raw, rhsKind, readNumber), operator)
    }

    /** Indirect coordinates arrive as a variable reference rather than a literal. */
    private fun resolveXyCoordinate(raw: Int, readNumber: (Int) -> Int): Int =
        if (raw >= 1_000_000 || raw in 1_600_000..1_699_999) readNumber(raw) else raw

    private fun resolveRhsNumber(raw: Int, rhsKind: Int, readNumber: (Int) -> Int): Int =
        when (rhsKind) {
            RHS_INDIRECT -> readNumber(readNumber(raw))
            else -> if (raw >= 1_000_000 || raw in 1_600_000..1_699_999) readNumber(raw) else raw
        }

    private fun isMeta(entryRaw: Int, fieldRaw: Int): Boolean =
        entryRaw == SENTINEL_NAME || entryRaw == SENTINEL_CLEAR ||
            fieldRaw == SENTINEL_NAME || fieldRaw == SENTINEL_CLEAR

    companion object {
        fun empty(): WolfDatabase = WolfDatabase(arrayOfNulls(Kind.entries.size))

        internal fun forTest(type: TypeState, source: GameDataSource): WolfDatabase {
            val bank = Bank(Kind.USER, listOf(type), mapOf(type.name to 0))
            val banks = arrayOfNulls<Bank>(Kind.entries.size)
            banks[Kind.USER.ordinal] = bank
            return WolfDatabase(banks, source)
        }

        private const val MODE_WRITE = 0x00
        private const val MODE_READ = 0x10
        private const val RHS_NUMBER = 0x00
        private const val RHS_INDIRECT = 0x01
        private const val RHS_STRING = 0x02
        private const val NAME_TYPE = 0x01
        private const val NAME_ENTRY = 0x02
        private const val NAME_FIELD = 0x04
        private const val SENTINEL_NAME = -3 // 0xFFFFFFFD
        private const val SENTINEL_CLEAR = -2 // 0xFFFFFFFE

        fun load(source: GameDataSource): WolfDatabase {
            val banks = arrayOfNulls<Bank>(Kind.entries.size)
            loadBank(
                source,
                Kind.CHANGEABLE,
                datCandidates = listOf(
                    "Data/BasicData/CDataBase.dat",
                    "Data/BasicData/BasicData/CDataBase.dat",
                ),
                projectCandidates = listOf(
                    "Data/BasicData/CDataBase.project",
                    "Data/BasicData/BasicData/CDataBase.project",
                ),
            )?.let { banks[Kind.CHANGEABLE.ordinal] = it }
            loadBank(
                source,
                Kind.USER,
                datCandidates = listOf(
                    "Data/BasicData/DataBase.dat",
                    "Data/BasicData/BasicData/DataBase.dat",
                ),
                projectCandidates = listOf(
                    "Data/BasicData/DataBase.project",
                    "Data/BasicData/BasicData/DataBase.project",
                ),
            )?.let { banks[Kind.USER.ordinal] = it }
            loadBank(
                source,
                Kind.SYSTEM,
                datCandidates = listOf(
                    "Data/BasicData/SysDatabase.dat",
                    "Data/BasicData/BasicData/SysDatabase.dat",
                ),
                projectCandidates = listOf(
                    "Data/BasicData/SysDatabase.project",
                    "Data/BasicData/BasicData/SysDatabase.project",
                ),
            )?.let { banks[Kind.SYSTEM.ordinal] = it }
            return WolfDatabase(banks, source)
        }

        private fun loadBank(
            source: GameDataSource,
            kind: Kind,
            datCandidates: List<String>,
            projectCandidates: List<String>,
        ): Bank? {
            val datPath = datCandidates.firstOrNull { source.has(it) } ?: return null
            val projectPath = projectCandidates.firstOrNull { source.has(it) }
            val dat = runCatching { DataBaseDat.parse(source.read(datPath)) }.getOrNull() ?: return null
            val project = projectPath?.let {
                runCatching { DataBaseProject.parse(source.read(it), v3 = dat.v3) }.getOrNull()
            }
            val types = ArrayList<TypeState>(dat.types.size)
            for (i in dat.types.indices) {
                val datType = dat.types[i]
                val meta = project?.types?.getOrNull(i)
                val entries = datType.entries.map { entry ->
                    EntryState(entry.numbers.copyOf(), entry.strings.toMutableList())
                }.toMutableList()
                types.add(
                    TypeState(
                        name = meta?.name ?: "type-$i",
                        fieldNames = meta?.fieldNames
                            ?: List(datType.propertyPositions.size) { "field-$it" },
                        entryNames = (meta?.entryNames ?: List(entries.size) { "entry-$it" })
                            .toMutableList(),
                        positions = datType.propertyPositions,
                        entries = entries,
                    ),
                )
            }
            val byName = types.withIndex().associate { (idx, t) -> t.name to idx }
            return Bank(kind, types, byName)
        }

        private fun applyOp(current: Int, value: Int, operator: Int): Int = when (operator) {
            0x00 -> value
            0x10 -> current + value
            0x20 -> current - value
            0x30 -> current * value
            0x40 -> if (value != 0) current / value else 0
            0x50 -> if (value != 0) current % value else 0
            0x60 -> maxOf(current, value)
            0x70 -> minOf(current, value)
            else -> value
        }
    }
}
