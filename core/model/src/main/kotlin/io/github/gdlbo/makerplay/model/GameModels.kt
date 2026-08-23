package io.github.gdlbo.makerplay.model

/** Supported game formats. WOLF is a native binary engine, not an RPG Maker web deployment. */
enum class GameEngine { MV, MZ, WOLF }

/**
 * Runtime backends available for playback. WOLF games require the clean-room
 * native interpreter; they can never run through the Chromium WebView backend.
 */
enum class RuntimeBackendId { WEBVIEW, GECKO, WOLF_NATIVE }

/** Maps a detected game engine to the backend that must execute it. */
fun GameEngine.defaultBackendId(): RuntimeBackendId = when (this) {
    GameEngine.WOLF -> RuntimeBackendId.WOLF_NATIVE
    GameEngine.MV,
    GameEngine.MZ,
    -> RuntimeBackendId.WEBVIEW
}

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