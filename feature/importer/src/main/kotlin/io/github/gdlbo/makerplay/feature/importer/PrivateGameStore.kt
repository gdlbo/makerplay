package io.github.gdlbo.makerplay.feature.importer

import io.github.gdlbo.makerplay.model.GameEngine
import io.github.gdlbo.makerplay.model.GameSummary
import io.github.gdlbo.makerplay.model.RuntimeBackendId
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

class PrivateGameStore(private val gamesRoot: File) {
    private val stagingRoot = File(gamesRoot, STAGING_DIRECTORY)

    fun begin(importId: String): File {
        require(SAFE_ID.matches(importId)) { "Invalid import ID" }
        gamesRoot.mkdirs()
        stagingRoot.mkdirs()
        val staging = File(stagingRoot, importId)
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Unable to create import staging directory" }
        return staging
    }

    fun writeMetadata(staging: File, game: GameSummary, directSource: File? = null) {
        require(staging.parentFile?.canonicalFile == stagingRoot.canonicalFile) {
            "Staging directory is outside the private import root"
        }
        val properties = Properties().apply {
            setProperty("formatVersion", METADATA_VERSION)
            setProperty("id", game.id)
            setProperty("title", game.title)
            setProperty("engine", game.engine.name)
            setProperty("backend", game.backend.name)
            setProperty("engineVersion", game.engineVersion.orEmpty())
            game.artworkRelativePath?.let { setProperty("artworkRelativePath", it) }
            directSource?.let { setProperty("directSourcePath", it.canonicalPath) }
            setProperty("installedAtEpochMillis", game.installedAtEpochMillis.toString())
            setProperty("pluginCount", game.plugins.size.toString())
            game.plugins.forEachIndexed { index, plugin -> setProperty("plugin.$index", plugin) }
        }
        FileOutputStream(File(staging, METADATA_FILE)).use { output ->
            properties.store(output, "MakerPlay imported game metadata")
            output.fd.sync()
        }
    }

    fun commit(staging: File, gameId: String): File {
        require(SAFE_ID.matches(gameId)) { "Invalid game ID" }
        val destination = File(gamesRoot, gameId)
        check(!destination.exists()) { "A game with this ID already exists" }
        try {
            Files.move(
                staging.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (error: AtomicMoveNotSupportedException) {
            throw ImportFailure("Private storage does not support atomic import commit.", error)
        }
        return destination
    }

    fun abort(staging: File) {
        if (staging.parentFile?.canonicalFile == stagingRoot.canonicalFile) {
            staging.deleteRecursively()
        }
    }

    fun listGames(): List<GameSummary> {
        gamesRoot.mkdirs()
        val games = gamesRoot.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && it.name != STAGING_DIRECTORY }
            .mapNotNull { readStoredGame(it)?.summary }
            .sortedByDescending(GameSummary::installedAtEpochMillis)
            .toList()
        val savedOrder = readGameOrder()
        if (savedOrder.isEmpty()) return games
        val orderedIds = savedOrder.toSet()
        val gamesById = games.associateBy(GameSummary::id)
        return buildList(games.size) {
            addAll(games.filterNot { it.id in orderedIds })
            savedOrder.mapNotNullTo(this) { gamesById[it] }
        }
    }

    fun writeGameOrder(gameIds: List<String>) {
        gamesRoot.mkdirs()
        val uniqueIds = gameIds.distinct().filter(SAFE_ID::matches)
        val properties = Properties().apply {
            setProperty("formatVersion", GAME_ORDER_VERSION)
            setProperty("gameCount", uniqueIds.size.toString())
            uniqueIds.forEachIndexed { index, id -> setProperty("game.$index", id) }
        }
        val destination = File(gamesRoot, GAME_ORDER_FILE)
        val temporary = File(gamesRoot, "$GAME_ORDER_FILE.tmp")
        FileOutputStream(temporary).use { output ->
            properties.store(output, "MakerPlay game order")
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    fun cleanStaleStaging() {
        stagingRoot.listFiles().orEmpty().forEach { it.deleteRecursively() }
    }

    fun deleteGame(gameId: String): Boolean {
        val directory = findCatalogDirectory(gameId) ?: return false
        check(directory.deleteRecursively() && !directory.exists()) { "Unable to delete installed game" }
        return true
    }

    fun findInstalledGame(gameId: String): File? {
        val directory = findCatalogDirectory(gameId) ?: return null
        val stored = readStoredGame(directory) ?: return null
        val directSource = stored.directSourcePath ?: return directory
        val source = File(directSource)
        if (Files.isSymbolicLink(source.toPath()) || !source.isDirectory) return null
        return runCatching { source.canonicalFile }.getOrNull()
    }

    fun findIndexDirectory(gameId: String): File? = findCatalogDirectory(gameId)

    fun isDirectGame(gameId: String): Boolean {
        val directory = findCatalogDirectory(gameId) ?: return false
        return readStoredGame(directory)?.directSourcePath != null
    }

    fun controllerLayoutFile(gameId: String): File? =
        findCatalogDirectory(gameId)?.let { File(it, CONTROLLER_LAYOUT_FILE) }

    private fun findCatalogDirectory(gameId: String): File? {
        if (!SAFE_ID.matches(gameId)) { return null }
        val directory = File(gamesRoot, gameId)
        if (Files.isSymbolicLink(directory.toPath()) || !directory.isDirectory) return null
        val canonical = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        if (canonical.parentFile != gamesRoot.canonicalFile) return null
        return canonical.takeIf { readStoredGame(it)?.summary?.id == gameId }
    }

    private fun readStoredGame(directory: File): StoredGame? = runCatching {
        val properties = Properties().apply {
            FileInputStream(File(directory, METADATA_FILE)).use(::load)
        }
        require(properties.getProperty("formatVersion") in SUPPORTED_METADATA_VERSIONS)
        val pluginCount = properties.getProperty("pluginCount").toInt().coerceIn(0, MAX_PLUGINS)
        val directSourcePath = properties.getProperty("directSourcePath")?.ifBlank { null }
        StoredGame(
            summary = GameSummary(
                id = properties.getProperty("id"),
                title = properties.getProperty("title"),
                engine = GameEngine.valueOf(properties.getProperty("engine")),
                backend = RuntimeBackendId.valueOf(properties.getProperty("backend")),
                engineVersion = properties.getProperty("engineVersion").ifBlank { null },
                plugins = List(pluginCount) { index -> properties.getProperty("plugin.$index") },
                artworkRelativePath = properties.getProperty("artworkRelativePath")
                    ?.ifBlank { null }
                    ?: findLegacyArtwork(directory),
                installedAtEpochMillis = properties.getProperty("installedAtEpochMillis").toLong(),
            ),
            directSourcePath = directSourcePath,
        )
    }.getOrNull()

    private fun findLegacyArtwork(directory: File): String? {
        val iconDirectory = directory.listFiles()
            ?.firstOrNull { it.isDirectory && it.name.equals("icon", ignoreCase = true) }
            ?: return null
        val icon = iconDirectory.listFiles()
            ?.firstOrNull { it.isFile && it.name.equals("icon.png", ignoreCase = true) }
            ?: return null
        return icon.relativeTo(directory).invariantSeparatorsPath
    }

    private fun readGameOrder(): List<String> = runCatching {
        val properties = Properties().apply {
            FileInputStream(File(gamesRoot, GAME_ORDER_FILE)).use(::load)
        }
        require(properties.getProperty("formatVersion") == GAME_ORDER_VERSION)
        val gameCount = properties.getProperty("gameCount").toInt().coerceIn(0, MAX_ORDERED_GAMES)
        List(gameCount) { index -> properties.getProperty("game.$index") }
            .filter(SAFE_ID::matches)
            .distinct()
    }.getOrDefault(emptyList())

    companion object {
        const val METADATA_FILE = ".makerplay.properties"
        private const val METADATA_VERSION = "2"
        private const val GAME_ORDER_FILE = ".makerplay-order.properties"
        private const val GAME_ORDER_VERSION = "1"
        private const val STAGING_DIRECTORY = ".staging"
        private const val CONTROLLER_LAYOUT_FILE = "gamepad.json"
        private const val MAX_PLUGINS = 512
        private const val MAX_ORDERED_GAMES = 10_000
        private val SUPPORTED_METADATA_VERSIONS = setOf("1", METADATA_VERSION)
        private val SAFE_ID = Regex("[a-zA-Z0-9-]{1,64}")
    }

    private data class StoredGame(
        val summary: GameSummary,
        val directSourcePath: String?,
    )
}