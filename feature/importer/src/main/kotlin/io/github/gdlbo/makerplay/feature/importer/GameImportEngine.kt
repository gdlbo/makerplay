package io.github.gdlbo.makerplay.feature.importer

import io.github.gdlbo.makerplay.model.GameSummary
import io.github.gdlbo.makerplay.model.RuntimeBackendId
import io.github.gdlbo.makerplay.vfs.GameFileIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

class GameImportEngine(
    private val detector: GameDetector = GameDetector(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun link(
        source: ImportSource,
        sourceRoot: File,
        store: PrivateGameStore,
        importId: String = UUID.randomUUID().toString(),
        onProgress: suspend (ImportProgress) -> Unit = {},
    ): ImportResult = withContext(Dispatchers.IO) {
        val entries = scanEntries(source, onProgress)
        val detected = detector.detect(entries, source.rootName ?: sourceRoot.name)
        val selected = entries
            .filter { it.relativePath.startsWith(detected.sourcePrefix, ignoreCase = true) }
            .map { entry -> entry.copy(relativePath = entry.relativePath.drop(detected.sourcePrefix.length)) }
            .filter { it.relativePath.isNotBlank() }
            .filterNot(::isReservedRootFile)
        validateEntries(selected)
        val totalBytes = selected.sumOf { it.size.coerceAtLeast(0L) }
        onProgress(
            ImportProgress(
                ImportPhase.FINALIZING,
                0L,
                totalBytes,
                0L,
                selected.size.toLong()
            )
        )

        val canonicalSource = sourceRoot.canonicalFile
        val actualSourcePrefix = entries
            .first {
                it.relativePath.equals(
                    "${detected.sourcePrefix}index.html",
                    ignoreCase = true
                )
            }
            .relativePath
            .dropLast("index.html".length)
        val contentRoot = actualSourcePrefix.removeSuffix("/")
            .takeIf(String::isNotEmpty)
            ?.let { File(canonicalSource, it).canonicalFile }
            ?: canonicalSource
        if (!contentRoot.isDirectory || !contentRoot.toPath()
                .startsWith(canonicalSource.toPath())
        ) {
            throw ImportFailure("The selected folder is no longer available.")
        }

        val staging = store.begin(importId)
        try {
            val game = detected.toGameSummary(importId)
            GameFileIndex.build(contentRoot).write(staging)
            store.writeMetadata(staging, game, directSource = contentRoot)
            val installed = store.commit(staging, game.id)
            onProgress(
                ImportProgress(
                    ImportPhase.FINALIZING,
                    totalBytes,
                    totalBytes,
                    selected.size.toLong(),
                    selected.size.toLong()
                )
            )
            ImportResult(game, installed.name)
        } catch (error: Throwable) {
            store.abort(staging)
            throw error
        }
    }

    suspend fun import(
        source: ImportSource,
        store: PrivateGameStore,
        importId: String = UUID.randomUUID().toString(),
        onProgress: suspend (ImportProgress) -> Unit = {},
    ): ImportResult = withContext(Dispatchers.IO) {
        val entries = scanEntries(source, onProgress)
        val fallbackTitle = source.rootName
        val sourceLayout = detector.detect(entries, fallbackTitle)
        val selected = entries
            .filter { it.relativePath.startsWith(sourceLayout.sourcePrefix, ignoreCase = true) }
            .map { entry -> entry.copy(relativePath = entry.relativePath.drop(sourceLayout.sourcePrefix.length)) }
            .filter { it.relativePath.isNotBlank() }
            .filterNot(::isReservedRootFile)
        validateEntries(selected)
        val totalBytes = selected.sumOf { it.size.coerceAtLeast(0L) }
        onProgress(ImportProgress(ImportPhase.COPYING, 0L, totalBytes, 0L, selected.size.toLong()))
        val staging = store.begin(importId)
        var copiedBytes = 0L
        var copiedFiles = 0L
        val progressThrottle = ProgressThrottle()
        try {
            selected.forEach { entry ->
                coroutineContext.ensureActive()
                val destination = resolveDestination(staging, entry.relativePath)
                val parent = requireNotNull(destination.parentFile)
                check(parent.isDirectory || parent.mkdirs()) { "Unable to create import directory" }
                entry.open().use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var fileBytes = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            fileBytes += read
                            copiedBytes += read
                            if (fileBytes > MAX_FILE_BYTES) {
                                throw ImportFailure("A game file exceeds the supported size.")
                            }
                            require(copiedBytes <= MAX_TOTAL_BYTES) { "Import exceeds the supported size" }
                            if (progressThrottle.shouldReport()) {
                                onProgress(
                                    ImportProgress(
                                        ImportPhase.COPYING,
                                        copiedBytes,
                                        totalBytes,
                                        copiedFiles,
                                        selected.size.toLong(),
                                    ),
                                )
                            }
                        }
                        output.fd.sync()
                    }
                }
                copiedFiles++
                if (copiedFiles == selected.size.toLong() || progressThrottle.shouldReport()) {
                    onProgress(
                        ImportProgress(
                            ImportPhase.COPYING,
                            copiedBytes,
                            totalBytes,
                            copiedFiles,
                            selected.size.toLong(),
                        ),
                    )
                }
            }
            onProgress(
                ImportProgress(
                    ImportPhase.FINALIZING,
                    copiedBytes,
                    totalBytes,
                    copiedFiles,
                    selected.size.toLong(),
                ),
            )
            val detected = detector.detect(staging.asImportSource().entries(), fallbackTitle)
            val game = detected.toGameSummary(importId)
            GameFileIndex.build(staging).write()
            store.writeMetadata(staging, game)
            val installed = store.commit(staging, game.id)
            ImportResult(game, installed.name)
        } catch (error: Throwable) {
            store.abort(staging)
            throw error
        }
    }

    private suspend fun scanEntries(
        source: ImportSource,
        onProgress: suspend (ImportProgress) -> Unit,
    ): List<ImportEntry> {
        var discoveredFiles = 0L
        var discoveredBytes = 0L
        val progressThrottle = ProgressThrottle(SCAN_PROGRESS_INTERVAL_NANOS)
        onProgress(ImportProgress(ImportPhase.SCANNING, 0L, 0L, 0L, 0L))
        val entries = source.scanEntries { entry ->
            currentCoroutineContext().ensureActive()
            discoveredFiles++
            discoveredBytes += entry.size.coerceAtLeast(0L)
            if (discoveredFiles == 1L || progressThrottle.shouldReport()) {
                onProgress(
                    ImportProgress(
                        ImportPhase.SCANNING,
                        discoveredBytes,
                        0L,
                        discoveredFiles,
                        0L,
                    ),
                )
            }
        }
        if (discoveredFiles > 0L) {
            onProgress(
                ImportProgress(
                    ImportPhase.SCANNING,
                    discoveredBytes,
                    0L,
                    discoveredFiles,
                    0L,
                ),
            )
        }
        return validateEntries(entries)
    }

    private fun validateEntries(entries: List<ImportEntry>): List<ImportEntry> {
        if (entries.isEmpty()) throw ImportFailure("The selected folder is empty.")
        val seen = HashSet<String>(entries.size)
        entries.forEach { entry ->
            validateRelativePath(entry.relativePath)
            if (
                isReservedRootFile(entry) &&
                !entry.relativePath.equals(CONTROLLER_LAYOUT_FILE, ignoreCase = true)
            ) {
                throw ImportFailure("The selected folder contains an unsafe file path.")
            }
            if (entry.size > MAX_FILE_BYTES) throw ImportFailure("A game file exceeds the supported size.")
            if (!seen.add(entry.relativePath.lowercase(Locale.ROOT))) {
                throw ImportFailure("The selected folder contains duplicate file paths.")
            }
        }
        return entries
    }

    private fun validateRelativePath(path: String) {
        val segments = path.split('/')
        if (
            path.isBlank() || path.startsWith('/') || path.contains('\\') ||
            segments.size > MAX_DEPTH || segments.any { it.isBlank() || it == "." || it == ".." } ||
            segments.any { '/' in it || '\\' in it } ||
            path.equals(PrivateGameStore.METADATA_FILE, ignoreCase = true)
        ) {
            throw ImportFailure("The selected folder contains an unsafe file path.")
        }
    }

    private fun isReservedRootFile(entry: ImportEntry): Boolean =
        '/' !in entry.relativePath &&
                entry.relativePath.lowercase(Locale.ROOT) in GameFileIndex.RESERVED_FILE_NAMES

    private fun resolveDestination(staging: File, relativePath: String): File {
        val destination = File(staging, relativePath).canonicalFile
        val rootPath = staging.canonicalFile.toPath()
        if (!destination.toPath().startsWith(rootPath)) {
            throw ImportFailure("The selected folder contains an unsafe file path.")
        }
        return destination
    }

    private fun DetectedGame.toGameSummary(gameId: String) = GameSummary(
        id = gameId,
        title = title,
        engine = engine,
        backend = RuntimeBackendId.WEBVIEW,
        engineVersion = engineVersion,
        plugins = plugins,
        artworkRelativePath = artworkRelativePath,
        installedAtEpochMillis = now(),
    )

    private fun File.asImportSource(): ImportSource = ImportSource {
        walkTopDown()
            .filter(File::isFile)
            .map { file ->
                ImportEntry(
                    relativePath = file.relativeTo(this).invariantSeparatorsPath,
                    size = file.length(),
                    open = file::inputStream,
                )
            }
            .toList()
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val CONTROLLER_LAYOUT_FILE = "gamepad.json"
        const val MAX_DEPTH = 64
        const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 16L * 1024 * 1024 * 1024
        const val SCAN_PROGRESS_INTERVAL_NANOS = 200L * 1_000_000L
    }
}

private class ProgressThrottle(
    private val intervalNanos: Long = 150L * 1_000_000L,
) {
    private var lastReportNanos = System.nanoTime()

    fun shouldReport(): Boolean {
        val now = System.nanoTime()
        if (now - lastReportNanos < intervalNanos) return false
        lastReportNanos = now
        return true
    }
}