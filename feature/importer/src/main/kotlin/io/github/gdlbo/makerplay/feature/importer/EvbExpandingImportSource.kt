package io.github.gdlbo.makerplay.feature.importer

import io.github.gdlbo.makerplay.wolfformat.EvbVirtualFileSystem
import java.io.File

/**
 * Decorates any [ImportSource] so that packed launcher executables are
 * transparently expanded into synthetic entries.
 *
 * The packed format requires random access, so each candidate exe is spooled once into
 * [spoolDir]; synthesized entries stream their payloads from the spool. The
 * caller owns [spoolDir] and deletes it when the import finishes. Loose files
 * always win over packed copies on path clash, and a successfully expanded exe
 * is dropped from the listing (its content lives in the extracted entries).
 */
class EvbExpandingImportSource(
    private val upstream: ImportSource,
    private val spoolDir: File,
) : ImportSource {

    override val rootName: String?
        get() = upstream.rootName

    override fun entries(): List<ImportEntry> = runCatchingScan()

    private fun runCatchingScan(): List<ImportEntry> =
        kotlinx.coroutines.runBlocking { upstream.scanEntries {} }.let(::expand)

    override suspend fun scanEntries(
        onEntryDiscovered: suspend (ImportEntry) -> Unit,
    ): List<ImportEntry> = upstream.scanEntries(onEntryDiscovered).let(::expand)

    private fun expand(entries: List<ImportEntry>): List<ImportEntry> {
        val candidates = entries.filter(::isCandidate)
        if (candidates.isEmpty()) return entries
        check(spoolDir.isDirectory || spoolDir.mkdirs()) { "Unable to create EVB spool directory" }

        val seenPaths = entries.mapTo(mutableSetOf()) { it.relativePath.lowercase() }
        val expandedExes = mutableSetOf<String>()
        val synthesized = mutableListOf<ImportEntry>()
        var spoolIndex = 0
        for (entry in candidates) {
            val spoolFile = File(spoolDir, "image-${spoolIndex++}.bin")
            try {
                entry.open().use { input ->
                    spoolFile.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
                }
            } catch (error: Throwable) {
                spoolFile.delete()
                throw error
            }
            val virtualFiles = try {
                EvbVirtualFileSystem.open(spoolFile).use { vfs -> vfs.entries() }
            } catch (error: Exception) {
                emptyList() // not a supported packed image; leave untouched
            }
            if (virtualFiles.isEmpty()) {
                spoolFile.delete()
                continue
            }
            expandedExes.add(entry.relativePath.lowercase())
            val lastSlash = entry.relativePath.lastIndexOf('/')
            val baseDir = if (lastSlash >= 0) entry.relativePath.substring(0, lastSlash + 1) else ""
            for (file in virtualFiles) {
                val normalized = (baseDir + file.path)
                if (!seenPaths.add(normalized.lowercase())) continue
                synthesized.add(
                    ImportEntry(
                        relativePath = normalized,
                        size = file.originalSize.toLong(),
                        open = { EvbVirtualFileSystem.openEntryStream(spoolFile, file) },
                    ),
                )
            }
        }
        if (expandedExes.isEmpty()) return entries
        return entries
            .filterNot { it.relativePath.lowercase() in expandedExes }
            .plus(synthesized)
    }

    private fun isCandidate(entry: ImportEntry): Boolean =
        entry.relativePath.endsWith(".exe", ignoreCase = true) &&
            entry.size >= MIN_EVB_IMAGE_BYTES

    private companion object {
        const val MIN_EVB_IMAGE_BYTES = 1L * 1024 * 1024
        const val COPY_BUFFER_BYTES = 256 * 1024
    }
}
