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
        detectWolf(byLowerPath, fallbackTitle)?.let { return it }
        return detectAtPrefix(byLowerPath, "", fallbackTitle)
            ?: detectAtPrefix(byLowerPath, "www/", fallbackTitle)
            ?: throw describeFailure(byLowerPath)
    }

    /**
     * WOLF games ship a closed native Game.exe and binary data files. Detect the
     * deployment without requiring an index.html (which is the MV/MZ contract).
     */
    /**
     * Gives an actionable reason instead of a generic failure. The common
     * near-miss is a packaged NW.js game whose engine files (www/js, www/data)
     * are embedded inside a protected Game.exe — MakerPlay cannot unpack
     * protected executables, so only deployments with loose engine files can
     * be imported.
     */
    private fun describeFailure(entries: Map<String, ImportEntry>): ImportFailure {
        val hasIndex = entries.containsKey("index.html") || entries.containsKey("www/index.html")
        val hasEngineFiles = entries.keys.any { it.startsWith("js/") || it.startsWith("data/") } &&
            entries.keys.any { it.startsWith("www/js/") || it.startsWith("www/data/") || !it.contains('/') }
        if (hasIndex && !hasEngineFiles) {
            return ImportFailure(
                "This game's engine files are packed inside its Game.exe. " +
                    "MakerPlay cannot unpack protected executables; import the " +
                    "unpacked version of this game instead.",
            )
        }
        return ImportFailure("The selected folder is not a supported RPG Maker or WOLF RPG deployment.")
    }

    private fun detectWolf(
        entries: Map<String, ImportEntry>,
        fallbackTitle: String?,
    ): DetectedGame? {
        // Game.exe is optional: some distributions ship without it (or with it
        // renamed); the data files are the authoritative WOLF signature.

        // Archive-only layout: all data lives in .wolf containers
        // (e.g. Data/BasicData.wolf) with no loose .dat files. Archives are
        // read directly; imports fail when a custom encryption key is needed.
        val wolfArchives = entries.keys.filter { it.endsWith(".wolf") }
        val hasPlainWolfData = entries.keys.any {
            it == "commonevent.dat" || it == "data/basicdata/commonevent.dat" ||
                it.endsWith("/mapdata/maptree.dat") || it.endsWith("/maptree.dat")
        }
        if (wolfArchives.isNotEmpty() && !hasPlainWolfData) {
            val title2 = fallbackTitle?.trim().orEmpty().ifBlank { "Imported WOLF RPG game" }
            return DetectedGame(
                sourcePrefix = "",
                engine = GameEngine.WOLF,
                title = title2.take(MAX_TITLE_LENGTH),
                engineVersion = "WOLF RPG (encrypted archives)",
                plugins = emptyList(),
                artworkRelativePath = entries["icon.png"]?.relativePath,
            )
        }

        val gameDat = entries["game.dat"] ?: entries["data/basicdata/game.dat"] ?: return null
        val hasWolfData = entries.keys.any {
            it == "commonevent.dat" || it == "data/basicdata/commonevent.dat" ||
                it.endsWith("/mapdata/maptree.dat") || it.endsWith("/maptree.dat")
        }
        if (!hasWolfData) return null
        val title = fallbackTitle?.trim().orEmpty().ifBlank { "Imported WOLF RPG game" }
        return DetectedGame(
            sourcePrefix = "",
            engine = GameEngine.WOLF,
            title = title.take(MAX_TITLE_LENGTH),
            engineVersion = wolfVersion(gameDat),
            plugins = emptyList(),
            artworkRelativePath = entries["icon.png"]?.relativePath,
        )
    }

    private fun wolfVersion(gameDat: ImportEntry): String? {
        // WOLF v3 files use a 0x55 version marker in their binary headers. Do not
        // guess a precise editor revision from it; preserve an explicit format label.
        val marker = gameDat.open().use { input ->
            input.skip(8)
            input.read()
        }
        return when (marker) {
            0x55 -> "WOLF RPG v3"
            0 -> "WOLF RPG v2"
            else -> "WOLF RPG"
        }
    }

    private fun detectAtPrefix(
        entries: Map<String, ImportEntry>,
        prefix: String,
        fallbackTitle: String?,
    ): DetectedGame? {
        if (entries["${prefix}index.html"] == null) return null
        val mzCore = entries["${prefix}js/rmmz_core.js"]
        val mvCore = entries["${prefix}js/rpg_core.js"]
        val gameScript = entries["${prefix}js/game.js"]
        val engine = when {
            mzCore != null -> GameEngine.MZ
            mvCore != null -> GameEngine.MV
            gameScript != null -> detectBundledEngine(gameScript)
            else -> null
        } ?: return null
        val core = mzCore ?: mvCore ?: gameScript!!
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
            engineVersion = readEngineVersion(core),
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
            json.parseToJsonElement(
                String(bytes, StandardCharsets.UTF_8).removePrefix(UTF8_BOM),
            ).jsonObject["gameTitle"]?.jsonPrimitive?.content.orEmpty()
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
            String(input.readUpTo(limit), StandardCharsets.UTF_8)
        }
    }

    private fun ImportEntry.readHead(limit: Int): String =
        open().use { input -> String(input.readUpTo(limit), StandardCharsets.UTF_8) }

    private fun java.io.InputStream.readUpTo(limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var total = 0
        while (total < buffer.size) {
            val read = read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return buffer.copyOf(total)
    }

    private fun detectBundledEngine(entry: ImportEntry): GameEngine? {
        val name = ENGINE_NAME_PATTERN.find(entry.readHead(ENGINE_SCRIPT_PROBE_BYTES))
            ?.groupValues?.get(1) ?: return null
        return when {
            name.equals("MZ", ignoreCase = true) -> GameEngine.MZ
            name.equals("MV", ignoreCase = true) -> GameEngine.MV
            else -> null
        }
    }

    private fun readEngineVersion(entry: ImportEntry): String? {
        val text = if (entry.size > MAX_METADATA_BYTES) {
            entry.readHead(VERSION_PROBE_BYTES)
        } else {
            entry.readText()
        }
        return VERSION_PATTERN.find(text)?.groupValues?.get(1)
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
        const val UTF8_BOM = "\uFEFF"
        const val MAX_METADATA_BYTES = 2 * 1024 * 1024
        const val MAX_TITLE_LENGTH = 160
        const val MAX_PLUGIN_NAME_LENGTH = 128
        const val MAX_PLUGINS = 512
        const val OBFUSCATION_PROBE_BYTES = 4 * 1024
        const val ENGINE_SCRIPT_PROBE_BYTES = 256 * 1024
        const val VERSION_PROBE_BYTES = 256 * 1024
        val VERSION_PATTERN = Regex("RPGMAKER_VERSION\\s*=\\s*[\"']([^\"']+)[\"']")
        val ENGINE_NAME_PATTERN = Regex("""RPGMAKER_NAME\s*=\s*["']([^"']+)["']""")
        val PLUGINS_ASSIGNMENT_PATTERN = Regex("""(?:var|let|const)?\s*\${'$'}plugins\s*=\s*""")
        val OBFUSCATED_SCRIPT_PATTERN = Regex(
            """^\s*(?:var|let|const|function)\s+_0x[0-9a-f]+""",
            RegexOption.IGNORE_CASE,
        )
    }
}