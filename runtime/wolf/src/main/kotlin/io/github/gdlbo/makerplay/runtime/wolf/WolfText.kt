package io.github.gdlbo.makerplay.runtime.wolf

/**
 * Expands common message / picture escape tags against live machine state.
 *
 * Supported subset: `\v[n]`, `\cself[n]`, `\s[n]`, `\cdb[t:e:f]`, `\udb[t:e:f]`,
 * `\sdb[t:e:f]` (and the same forms with names). Unknown tags are left intact.
 */
object WolfText {
    private val simpleRef = Regex("""\\(v|cself|s)\[(\d+)\]""", RegexOption.IGNORE_CASE)
    private val dbRef = Regex(
        """\\(cdb|udb|sdb)\[([^:\]]+):([^:\]]+)(?::([^\]]+))?\]""",
        RegexOption.IGNORE_CASE,
    )

    fun interpolate(
        raw: String,
        variables: Map<Int, Int>,
        strings: Map<Int, String>,
        database: WolfDatabase? = null,
    ): String {
        if (!raw.contains('\\')) return raw
        var out = simpleRef.replace(raw) { match ->
            val kind = match.groupValues[1].lowercase()
            val id = match.groupValues[2].toIntOrNull() ?: return@replace match.value
            when (kind) {
                "v" -> (variables[id] ?: variables[id + 2_000_000] ?: 0).toString()
                // CSelf is dual-typed: prefer string bank (title picture paths),
                // else numeric bank (both live at decoded -1000000 - n).
                "cself" -> {
                    val decoded = -1_000_000 - id
                    strings[decoded] ?: strings[1_600_000 + id] ?: strings[id]
                        ?: (variables[decoded] ?: variables[id] ?: 0).toString()
                }
                "s" -> strings[id].orEmpty()
                else -> match.value
            }
        }
        if (database != null && out.contains('\\')) {
            out = dbRef.replace(out) { match ->
                val kind = match.groupValues[1].lowercase()
                val typeKey = match.groupValues[2]
                val entryKey = match.groupValues[3]
                val fieldKey = match.groupValues[4].ifEmpty { "0" }
                val bank = when (kind) {
                    "cdb" -> database.bank(WolfDatabase.Kind.CHANGEABLE)
                    "udb" -> database.bank(WolfDatabase.Kind.USER)
                    "sdb" -> database.bank(WolfDatabase.Kind.SYSTEM)
                    else -> null
                } ?: return@replace match.value
                val typeIdx = typeKey.toIntOrNull() ?: bank.typeIndex(typeKey)
                    ?: return@replace match.value
                val type = bank.typeAt(typeIdx) ?: return@replace match.value
                val entryIdx = entryKey.toIntOrNull() ?: type.entryIndex(entryKey)
                    ?: return@replace match.value
                val fieldIdx = fieldKey.toIntOrNull() ?: type.fieldIndex(fieldKey)
                    ?: return@replace match.value
                if (type.isStringField(fieldIdx)) {
                    type.readString(entryIdx, fieldIdx)
                } else {
                    type.readNumber(entryIdx, fieldIdx).toString()
                }
            }
        }
        return out
    }

    /** Removes WOLF display directives which Android's canvas does not render. */
    fun stripPresentationMarkup(raw: String): String = raw
        .replace(Regex("""\\[Ee]"""), "")
        .replace(Regex("""\\f\[[^]]*]""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\\c\[[^]]*]""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\\i\[[^]]*]""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\\w\[[^]]*]""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\\s\[[^]]*]""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\\space\[[^]]*]""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""\\(?:a[xy]|[a-z]+\d*)\[[^]]*]""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""^@\d+\s*""", RegexOption.MULTILINE), "")
        .replace(Regex("""<[^>]*>"""), "")
        .replace(Regex("""[ \t]{2,}"""), " ")
        .trim()
}
