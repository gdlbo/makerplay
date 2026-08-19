package io.github.gdlbo.makerplay.feature.importer

import io.github.gdlbo.makerplay.model.GameEngine
import io.github.gdlbo.makerplay.model.GameSummary
import java.io.InputStream

data class ImportRequest(
    val sourceKind: ImportSourceKind,
    val sourceLocation: String,
    val importId: String,
)

enum class ImportSourceKind {
    SAF_TREE,
    FILE_DIRECTORY,
}

enum class GameInstallMode {
    COPY,
    DIRECT,
}

data class ImportProgress(
    val phase: ImportPhase = ImportPhase.SCANNING,
    val copiedBytes: Long,
    val totalBytes: Long,
    val copiedFiles: Long,
    val totalFiles: Long,
)

enum class ImportPhase {
    SCANNING,
    COPYING,
    FINALIZING,
}

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data class Running(val progress: ImportProgress) : ImportUiState
    data class Succeeded(val gameId: String) : ImportUiState
    data class Failed(val userMessage: String) : ImportUiState
}

data class ImportEntry(
    val relativePath: String,
    val size: Long,
    val open: () -> InputStream,
)

fun interface ImportSource {
    fun entries(): List<ImportEntry>

    suspend fun scanEntries(onEntryDiscovered: suspend (ImportEntry) -> Unit): List<ImportEntry> =
        entries().also { entries -> entries.forEach { onEntryDiscovered(it) } }

    val rootName: String?
        get() = null
}

data class DetectedGame(
    val sourcePrefix: String,
    val engine: GameEngine,
    val title: String,
    val engineVersion: String?,
    val plugins: List<String>,
    val artworkRelativePath: String?,
)

class ImportFailure(
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(userMessage, cause)

data class ImportResult(
    val game: GameSummary,
    val installedDirectoryName: String,
)