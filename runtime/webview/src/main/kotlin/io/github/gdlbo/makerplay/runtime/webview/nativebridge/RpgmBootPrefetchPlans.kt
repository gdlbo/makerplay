package io.github.gdlbo.makerplay.runtime.webview.nativebridge

import io.github.gdlbo.makerplay.vfs.GameFileSystem
import io.github.gdlbo.makerplay.vfs.VfsOpenResult

/**
 * Builds boot-time native prefetch path lists (plaintext JSON + encrypted logical assets).
 * Kept pure for unit tests; [NativeAssetPrefetch] performs the IO.
 */
internal object RpgmBootPrefetchPlans {
    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp")
    private val AUDIO_EXTS = setOf("ogg", "m4a", "mp3", "wav")

    fun plaintextHotPaths(fileSystem: GameFileSystem): List<String> {
        val paths = LinkedHashSet<String>()
        paths += "data/System.json"
        paths += "data/CommonEvents.json"
        paths += "data/MapInfos.json"
        paths += "data/Actors.json"
        paths += "data/Classes.json"
        paths += "data/Skills.json"
        paths += "data/Items.json"
        paths += "data/Weapons.json"
        paths += "data/Armors.json"
        paths += "data/Enemies.json"
        paths += "data/Troops.json"
        paths += "data/States.json"
        paths += "data/Animations.json"
        paths += "data/Tilesets.json"
        paths += "js/plugins.js"

        fileSystem.list("data").orEmpty()
            .filter { it.endsWith(".json", ignoreCase = true) }
            .map { "data/$it" }
            .sortedByDescending { path -> fileSystem.resolve(path)?.storedSize ?: 0L }
            .take(40)
            .forEach(paths::add)

        // Early map payloads (title/new-game often touch low IDs first).
        (1..8).forEach { id ->
            paths += "data/Map%03d.json".format(id)
            paths += "data/Map%d.json".format(id)
        }
        return paths.filter { fileSystem.resolve(it) != null }
    }

    /** Image/audio logical paths useful at title/boot (encrypted or plaintext). */
    fun mediaLogicalPaths(fileSystem: GameFileSystem): List<String> {
        val paths = LinkedHashSet<String>()
        paths += "img/system/Window.png"
        paths += "img/system/GameOver.png"
        paths += "img/system/Title.png"
        paths += "img/system/Loading.png"
        paths += "img/system/Shadow.png"
        paths += "img/system/IconSet.png"
        paths += "img/system/Balloon.png"
        paths += "img/system/Damage.png"

        addListed(fileSystem, paths, "img/animations", IMAGE_EXTS, 40)
        addListed(fileSystem, paths, "img/characters", IMAGE_EXTS, 32)
        addListed(fileSystem, paths, "img/faces", IMAGE_EXTS, 24)
        addListed(fileSystem, paths, "img/tilesets", IMAGE_EXTS, 24)
        addListed(fileSystem, paths, "img/parallaxes", IMAGE_EXTS, 12)
        addListed(fileSystem, paths, "img/pictures", IMAGE_EXTS, 12)
        addListed(fileSystem, paths, "img/enemies", IMAGE_EXTS, 16)
        addListed(fileSystem, paths, "img/sv_actors", IMAGE_EXTS, 12)
        addListed(fileSystem, paths, "img/sv_enemies", IMAGE_EXTS, 12)
        addListed(fileSystem, paths, "img/titles1", IMAGE_EXTS, 8)
        addListed(fileSystem, paths, "img/titles2", IMAGE_EXTS, 8)
        // Small audio set for title/BGM; large tracks stay on-demand.
        addListed(fileSystem, paths, "audio/bgm", AUDIO_EXTS, 4)
        addListed(fileSystem, paths, "audio/bgs", AUDIO_EXTS, 2)
        addListed(fileSystem, paths, "audio/me", AUDIO_EXTS, 4)
        addListed(fileSystem, paths, "audio/se", AUDIO_EXTS, 12)

        return paths.filter { fileSystem.resolve(it) != null }
    }

    fun encryptedLogicalPaths(fileSystem: GameFileSystem): List<String> =
        mediaLogicalPaths(fileSystem).filter { logical ->
            fileSystem.resolve(logical)?.codecId != null
        }

    fun plaintextMediaPaths(fileSystem: GameFileSystem): List<String> =
        mediaLogicalPaths(fileSystem).filter { logical ->
            fileSystem.resolve(logical)?.codecId == null
        }

    fun readEncryptionKey(fileSystem: GameFileSystem): String? {
        val system = fileSystem.open("data/System.json") as? VfsOpenResult.Found ?: return null
        val text = system.stream.use { it.readBytes().toString(Charsets.UTF_8) }
        return Regex("\"encryptionKey\"\\s*:\\s*\"([0-9a-fA-F]{32})\"").find(text)?.groupValues?.get(1)
    }

    private fun addListed(
        fileSystem: GameFileSystem,
        paths: MutableSet<String>,
        directory: String,
        exts: Set<String>,
        limit: Int,
    ) {
        fileSystem.list(directory).orEmpty()
            .asSequence()
            .mapNotNull { name -> logicalMediaName(name, exts) }
            .sortedByDescending { logical ->
                fileSystem.resolve("$directory/$logical")?.storedSize ?: 0L
            }
            .take(limit)
            .forEach { logical -> paths += "$directory/$logical" }
    }

    /** Map stored names (incl. `.png_` / `.rpgmvp`) to logical `base.ext`. */
    private fun logicalMediaName(storedName: String, exts: Set<String>): String? {
        val lower = storedName.lowercase()
        for (ext in exts) {
            when {
                lower.endsWith(".$ext") -> {
                    val base = storedName.dropLast(ext.length + 1)
                    return "$base.$ext"
                }
                lower.endsWith(".${ext}_") -> {
                    val base = storedName.dropLast(ext.length + 2)
                    return "$base.$ext"
                }
                lower.endsWith(".rpgmvp") && ext == "png" -> {
                    val base = storedName.dropLast(".rpgmvp".length)
                    return "$base.png"
                }
                lower.endsWith(".rpgmvo") && ext == "ogg" -> {
                    val base = storedName.dropLast(".rpgmvo".length)
                    return "$base.ogg"
                }
                lower.endsWith(".rpgmvm") && (ext == "m4a" || ext == "mp3") -> {
                    val base = storedName.dropLast(".rpgmvm".length)
                    return "$base.$ext"
                }
            }
        }
        return null
    }
}
