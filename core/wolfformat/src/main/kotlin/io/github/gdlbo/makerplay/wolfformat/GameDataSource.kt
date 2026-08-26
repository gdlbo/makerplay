package io.github.gdlbo.makerplay.wolfformat

import java.io.File

/**
 * Unified read access to a WOLF deployment's data files, whether they ship as
 * plain directories under `Data/` or inside `.wolf` archives.
 */
interface GameDataSource : AutoCloseable {
    /** Returns the raw bytes of [relativePath] (slash-separated, root-relative). */
    fun read(relativePath: String): ByteArray

    /** Lists file names directly inside [relativeDir]; empty when absent. */
    fun list(relativeDir: String): List<String>

    fun has(relativePath: String): Boolean = runCatching { read(relativePath) }.isSuccess

    companion object {
        const val GAME_DAT: String = "Game.dat"
        const val DATA_GAME_DAT: String = "Data/BasicData/Game.dat"

        fun open(gameRoot: File): GameDataSource = DirectoryGameDataSource(gameRoot)
    }
}

/** Plain-directory source; the layout used by unencrypted deployments. */
class DirectoryGameDataSource(private val gameRoot: File) : GameDataSource {
    init {
        if (!gameRoot.isDirectory) {
            throw WolfFormatException("Game root is not a directory: ${gameRoot.path}")
        }
    }

    private fun resolveWithinRoot(relativePath: String): File {
        val candidates = listOf(
            gameRoot.resolve(relativePath),
            // Some layouts nest everything under Data/.
            gameRoot.resolve("Data").resolve(relativePath),
        )
        for (candidate in candidates) {
            val canonical = candidate.canonicalFile
            if (!canonical.toPath().startsWith(gameRoot.canonicalFile.toPath())) {
                throw WolfFormatException("Resolved path escapes game root: $relativePath")
            }
            if (canonical.exists()) return canonical
        }
        throw WolfFormatException("Missing game data file: $relativePath")
    }

    override fun read(relativePath: String): ByteArray {
        val canonical = resolveWithinRoot(relativePath)
        if (canonical.isFile) return canonical.readBytes()
        throw WolfFormatException("Missing game data file: $relativePath")
    }

    override fun list(relativeDir: String): List<String> {
        val dir = runCatching { resolveWithinRoot(relativeDir) }.getOrNull() ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        // Include directories so callers can resolve case-insensitive folder names.
        return dir.listFiles()?.map { it.name }.orEmpty()
    }

    override fun has(relativePath: String): Boolean =
        runCatching { resolveWithinRoot(relativePath).isFile }.getOrDefault(false)

    override fun close() = Unit
}
