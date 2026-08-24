package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.wolfformat.GameDataSource
import io.github.gdlbo.makerplay.wolfformat.WolfArchiveReader
import io.github.gdlbo.makerplay.wolfformat.WolfFormatException
import java.io.File

/**
 * Data source for deployments that store everything inside encrypted `.wolf`
 * archives (no loose data files). Plain files still win when present.
 *
 * Archives are decoded with the format's default key; plain files still win
 * when present.
 */
class WolfArchiveGameDataSource(
    private val gameRoot: File,
    private val archiveKey: String = "",
) : GameDataSource {

    init {
        if (!gameRoot.isDirectory) {
            throw WolfFormatException("Game root is not a directory: ${gameRoot.path}")
        }
    }

    private val readers: List<Pair<File, WolfArchiveReader>> by lazy {
        val archives = mutableListOf<Pair<File, WolfArchiveReader>>()
        fun scan(dir: File) {
            dir.listFiles()?.forEach { child ->
                when {
                    child.isDirectory -> scan(child)
                    child.extension.equals("wolf", true) ->
                        runCatching { WolfArchiveReader(child, archiveKey) }
                            .getOrNull()
                            ?.let { archives.add(child to it) }
                }
            }
        }
        scan(gameRoot)
        archives
    }

    private fun plainFile(relativePath: String): File? {
        val candidates = listOf(
            gameRoot.resolve(relativePath),
            gameRoot.resolve("Data").resolve(relativePath),
        )
        for (candidate in candidates) {
            val canonical = candidate.canonicalFile
            if (!canonical.toPath().startsWith(gameRoot.canonicalFile.toPath())) {
                throw WolfFormatException("Resolved path escapes game root: $relativePath")
            }
            if (canonical.isFile) return canonical
        }
        return null
    }

    override fun read(relativePath: String): ByteArray {
        plainFile(relativePath)?.let { return it.readBytes() }
        val wanted = relativePath.lowercase()
        val suffixes = listOf(wanted, "data/$wanted")
        for ((archive, reader) in readers) {
            val entry = reader.entries().firstOrNull { candidate ->
                val p = candidate.path.lowercase()
                suffixes.any { p == it || p.endsWith("/$it") }
            } ?: continue
            return reader.extract(entry)
        }
        throw WolfFormatException("Missing game data file: $relativePath")
    }

    override fun list(relativeDir: String): List<String> {
        val names = sortedSetOf(String.CASE_INSENSITIVE_ORDER)
        val plain = gameRoot.resolve(relativeDir)
        if (plain.isDirectory) {
            plain.listFiles()?.forEach { names.add(it.name) }
        }
        val dataPlain = gameRoot.resolve("Data").resolve(relativeDir)
        if (dataPlain.isDirectory) {
            dataPlain.listFiles()?.forEach { names.add(it.name) }
        }
        for ((_, reader) in readers) {
            reader.entries().forEach { entry ->
                val idx = entry.path.lastIndexOf('/')
                val parent = if (idx >= 0) entry.path.substring(0, idx) else ""
                val name = entry.path.substring(idx + 1)
                if (parent.equals(relativeDir, true)) names.add(name)
            }
        }
        return names.toList()
    }

    override fun close() = Unit
}
