package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.wolfformat.DataBaseDat
import io.github.gdlbo.makerplay.wolfformat.DataBaseProject
import io.github.gdlbo.makerplay.wolfformat.EventCommand
import io.github.gdlbo.makerplay.wolfformat.GameDataSource

/**
 * Mutable runtime view of the three standard database files plus helpers for
 * opcode 250 (and no-op stubs for the import family).
 *
 * Flag packing for params[3] (little-endian bytes):
 * - b0: operator (hi nibble) + rhs kind (lo nibble: 0 number, 1 indirect, 2 string)
 * - b1: mode (hi nibble: 0 write, 1 read, …) + db kind (lo: 0 changeable, 1 system, 2 user)
 * - b2: name flags (1 type, 2 entry, 4 field)
 */
class WolfDatabase private constructor(
    private val banks: Array<Bank?>,
) {
    enum class Kind { CHANGEABLE, SYSTEM, USER }

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

    fun execute(
        command: EventCommand,
        readNumber: (Int) -> Int,
        writeNumber: (Int, Int) -> Unit,
        readString: (Int) -> String,
        writeString: (Int, String) -> Unit,
    ) {
        when (command.commandType) {
            250 -> executeAssign(command, readNumber, writeNumber, readString, writeString)
            251, 252, 255 -> Unit // import / commit family: not required for the sample set yet
            else -> Unit
        }
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
        val mode = modeByte and 0xF0
        val dbKind = when (modeByte and 0x0F) {
            0 -> Kind.CHANGEABLE
            1 -> Kind.SYSTEM
            2 -> Kind.USER
            else -> return // XY array not implemented yet
        }
        val bank = bank(dbKind) ?: return

        val typeIdx = resolveType(bank, p[0], s.getOrNull(1).orEmpty(), nameFlags)
            ?: return
        val type = bank.typeAt(typeIdx) ?: return

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
        if (mode != MODE_READ) {
            // Full reset / clear variants land here for write mode; keep a
            // conservative no-op until a sample requires mutation semantics.
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
            return WolfDatabase(banks)
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
