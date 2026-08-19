package io.github.gdlbo.makerplay.feature.importer

import io.github.gdlbo.makerplay.model.GameEngine
import io.github.gdlbo.makerplay.vfs.RpgMakerProtectedData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets

class GameDetector(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun detect(entries: List<ImportEntry>, fallbackTitle: String? = null): DetectedGame {
        val byLowerPath = entries.associateBy { it.relativePath.lowercase() }
        return detectAtPrefix(byLowerPath, "", fallbackTitle)
            ?: detectAtPrefix(byLowerPath, "www/", fallbackTitle)
            ?: throw ImportFailure("The selected folder is not an RPG Maker MV or MZ deployment.")
    }

    private fun detectAtPrefix(
        entries: Map<String, ImportEntry>,
        prefix: String,
        fallbackTitle: String?,
    ): DetectedGame? {
        if (entries["${prefix}index.html"] == null) return null
        val mzCore = entries["${prefix}js/rmmz_core.js"]
        val mvCore = entries["${prefix}js/rpg_core.js"]
        val engine = when {
            mzCore != null -> GameEngine.MZ
            mvCore != null -> GameEngine.MV
            else -> return null
        }
        val core = mzCore ?: mvCore!!
        val system = entries["${prefix}data/system.json"]
            ?: throw ImportFailure("The game is missing data/System.json.")
        val title = parseTitle(system).ifBlank {
            fallbackTitle?.trim()?.takeIf(String::isNotEmpty)
                ?: "Imported RPG Maker ${engine.name} game"
        }
        val plugins = entries["${prefix}js/plugins.js"]?.let(::parsePlugins).orEmpty()
        return DetectedGame(
            sourcePrefix = prefix,
            engine = engine,
            title = title.take(MAX_TITLE_LENGTH),
            engineVersion = VERSION_PATTERN.find(core.readText())?.groupValues?.get(1),
            plugins = plugins.distinct().take(MAX_PLUGINS),
            artworkRelativePath = entries["${prefix}icon/icon.png"]
                ?.relativePath
                ?.drop(prefix.length),
        )
    }

    private fun parseTitle(entry: ImportEntry): String {
        val bytes = entry.readBytes()
        if (RpgMakerProtectedData.isCryptoJsOpenSslBase64(bytes)) return ""
        return runCatching {
            json.parseToJsonElement(String(bytes, StandardCharsets.UTF_8))
                .jsonObject["gameTitle"]?.jsonPrimitive?.content.orEmpty()
        }.getOrElse { throw ImportFailure("The game has an invalid data/System.json file.", it) }
    }

    private fun parsePlugins(entry: ImportEntry): List<String> {
        if (OBFUSCATED_SCRIPT_PATTERN.containsMatchIn(entry.readPrefix(OBFUSCATION_PROBE_BYTES))) {
            return emptyList()
        }
        val script = entry.readText()
        val assignment = PLUGINS_ASSIGNMENT_PATTERN.find(script) ?: return emptyList()
        val arrayStart = script.indexOf('[', assignment.range.last + 1)
        val arrayEnd = script.findArrayEnd(arrayStart)
        if (arrayStart < 0 || arrayEnd < 0) {
            throw ImportFailure("The game has an invalid js/plugins.js file.")
        }
        return runCatching {
            json.parseToJsonElement(script.substring(arrayStart, arrayEnd + 1)).jsonArray
                .mapNotNull { element ->
                    val plugin = element.jsonObject
                    val enabled =
                        plugin["status"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                    plugin["name"]?.jsonPrimitive?.content
                        ?.takeIf { enabled && it.isNotBlank() }
                        ?.take(MAX_PLUGIN_NAME_LENGTH)
                }
        }.getOrElse { throw ImportFailure("The game has an invalid js/plugins.js file.", it) }
    }

    private fun ImportEntry.readBytes(): ByteArray {
        if (size > MAX_METADATA_BYTES) throw ImportFailure("Game metadata exceeds the supported size.")
        return open().use { input ->
            val capacity = if (size in 1..MAX_METADATA_BYTES.toLong()) {
                size.toInt() + 1
            } else {
                MAX_METADATA_BYTES + 1
            }
            val bytes = ByteArray(capacity)
            var bytesRead = 0
            while (bytesRead < bytes.size) {
                val read = input.read(bytes, bytesRead, bytes.size - bytesRead)
                if (read < 0) break
                bytesRead += read
            }
            if (bytesRead > MAX_METADATA_BYTES || bytesRead == bytes.size && input.read() >= 0) {
                throw ImportFailure("Game metadata exceeds the supported size.")
            }
            bytes.copyOf(bytesRead)
        }
    }

    private fun ImportEntry.readText(): String = String(readBytes(), StandardCharsets.UTF_8)

    private fun ImportEntry.readPrefix(limit: Int): String {
        if (size > MAX_METADATA_BYTES) throw ImportFailure("Game metadata exceeds the supported size.")
        return open().use { input ->
            String(input.readNBytes(limit), StandardCharsets.UTF_8)
        }
    }

    private fun String.findArrayEnd(start: Int): Int {
        if (start !in indices || this[start] != '[') return -1
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until length) {
            val char = this[index]
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
            } else {
                when (char) {
                    '"' -> quoted = true
                    '[' -> depth++
                    ']' -> if (--depth == 0) return index
                }
            }
        }
        return -1
    }

    private companion object {
        const val MAX_METADATA_BYTES = 2 * 1024 * 1024
        const val MAX_TITLE_LENGTH = 160
        const val MAX_PLUGIN_NAME_LENGTH = 128
        const val MAX_PLUGINS = 512
        const val OBFUSCATION_PROBE_BYTES = 4 * 1024
        val VERSION_PATTERN = Regex("RPGMAKER_VERSION\\s*=\\s*[\"']([^\"']+)[\"']")
        val PLUGINS_ASSIGNMENT_PATTERN = Regex("""(?:var|let|const)?\s*\${'$'}plugins\s*=\s*""")
        val OBFUSCATED_SCRIPT_PATTERN = Regex(
            """^\s*(?:var|let|const|function)\s+_0x[0-9a-f]+""",
            RegexOption.IGNORE_CASE,
        )
    }
}