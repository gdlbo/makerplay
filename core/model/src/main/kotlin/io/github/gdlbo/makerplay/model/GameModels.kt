package io.github.gdlbo.makerplay.model

enum class GameEngine { MV, MZ }

enum class RuntimeBackendId { WEBVIEW, GECKO }

data class GameSummary(
    val id: String,
    val title: String,
    val engine: GameEngine,
    val backend: RuntimeBackendId,
    val engineVersion: String? = null,
    val plugins: List<String> = emptyList(),
    val artworkRelativePath: String? = null,
    val installedAtEpochMillis: Long = 0L,
)