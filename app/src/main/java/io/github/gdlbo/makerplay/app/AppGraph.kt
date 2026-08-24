package io.github.gdlbo.makerplay.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.webkit.WebStorage
import androidx.core.content.edit
import io.github.gdlbo.makerplay.diagnostics.AndroidRuntimeLogger
import io.github.gdlbo.makerplay.diagnostics.PersistentRuntimeLogger
import io.github.gdlbo.makerplay.feature.importer.GameCatalogRepository
import io.github.gdlbo.makerplay.feature.importer.GameInstallMode
import io.github.gdlbo.makerplay.feature.importer.GameImportWorker
import io.github.gdlbo.makerplay.feature.importer.ImportCoordinator
import io.github.gdlbo.makerplay.feature.importer.PrivateGameStore
import io.github.gdlbo.makerplay.feature.importer.StorageRoots
import io.github.gdlbo.makerplay.feature.settings.ThemeMode
import io.github.gdlbo.makerplay.model.GameSummary
import io.github.gdlbo.makerplay.model.RuntimeBackendId
import io.github.gdlbo.makerplay.runtime.api.FileGameSaveStore
import io.github.gdlbo.makerplay.runtime.api.GameRuntimeBackend
import io.github.gdlbo.makerplay.runtime.api.RuntimeEngineMode
import io.github.gdlbo.makerplay.runtime.api.RuntimeModuleSettings
import io.github.gdlbo.makerplay.runtime.api.RuntimeOrientation
import io.github.gdlbo.makerplay.runtime.api.RuntimeScaleMode
import io.github.gdlbo.makerplay.runtime.api.RuntimeSettings
import io.github.gdlbo.makerplay.runtime.api.SUPPORTED_FPS_LIMITS
import io.github.gdlbo.makerplay.runtime.wolf.WolfRuntimeBackend
import io.github.gdlbo.makerplay.runtime.webview.WebViewRuntimeBackend
import java.io.File

class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    private val preferences =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    val logger = AndroidRuntimeLogger("MakerPlay")
    private val gameStore =
        PrivateGameStore(File(context.filesDir, GameImportWorker.GAMES_DIRECTORY))
    private val saveStore = FileGameSaveStore(File(context.filesDir, "saves"))
    private val commonJsDataRoot = File(context.filesDir, "node-data")

    val catalog = GameCatalogRepository(gameStore)
    val importCoordinator = ImportCoordinator(context, catalog)

    /** Routes to the backend recorded for the game; WOLF never reaches WebView. */
    fun runtimeBackend(gameId: String): GameRuntimeBackend {
        val backendId = catalog.games.value.firstOrNull { it.id == gameId }?.backend
        return when (backendId) {
            RuntimeBackendId.WOLF_NATIVE -> wolfRuntimeBackend
            else -> webViewRuntimeBackend
        }
    }
    private val wolfRuntimeBackend: GameRuntimeBackend = WolfRuntimeBackend(
        logger = logger,
        gameDirectory = ::resolveGameDirectory,
        gameLoggerFactory = { gameRoot ->
            PersistentRuntimeLogger(gameRoot, AndroidRuntimeLogger("MakerPlay"))
        },
    )
    private val webViewRuntimeBackend: GameRuntimeBackend = WebViewRuntimeBackend(
        logger = logger,
        gameDirectory = ::resolveGameDirectory,
        commonJsDataDirectory = ::commonJsDataDirectory,
        gameIndexDirectory = gameStore::findIndexDirectory,
        saveStore = saveStore,
        gameLoggerFactory = { gameRoot ->
            PersistentRuntimeLogger(gameRoot, AndroidRuntimeLogger("MakerPlay"))
        },
    )

    var defaultGameFolder: String
        get() = preferences.getString(DEFAULT_GAME_FOLDER, defaultDownloadsFolder()).orEmpty()
        set(value) {
            preferences.edit { putString(DEFAULT_GAME_FOLDER, value) }
        }

    var runtimeSettings: RuntimeSettings
        get() = readRuntimeSettings()
        set(value) {
            writeRuntimeSettings(value)
        }

    var themeMode: ThemeMode
        get() = preferences.enum(THEME_MODE, ThemeMode.SYSTEM)
        set(value) {
            preferences.edit { putString(THEME_MODE, value.name) }
        }

    fun gameRuntimeSettings(gameId: String): RuntimeSettings? {
        val prefix = gameSettingsPrefix(gameId)
        return if (preferences.getBoolean(prefix + USE_COMMON_SETTINGS, true)) {
            null
        } else {
            readRuntimeSettings(prefix)
        }
    }

    var defaultInstallMode: GameInstallMode
        get() = preferences.enum(DEFAULT_INSTALL_MODE, GameInstallMode.COPY)
        set(value) {
            preferences.edit { putString(DEFAULT_INSTALL_MODE, value.name) }
        }

    fun savedGameRuntimeSettings(gameId: String): RuntimeSettings? {
        val prefix = gameSettingsPrefix(gameId)
        return if (preferences.contains(prefix + ORIENTATION)) readRuntimeSettings(prefix) else null
    }

    fun setGameRuntimeSettings(gameId: String, value: RuntimeSettings?) {
        val prefix = gameSettingsPrefix(gameId)
        if (value == null) {
            preferences.edit { putBoolean(prefix + USE_COMMON_SETTINGS, true) }
        } else {
            writeRuntimeSettings(value, prefix, useCommonSettings = false)
        }
    }

    fun artworkFile(game: GameSummary): File? {
        val relativePath = game.artworkRelativePath ?: return null
        val gameDirectory = resolveGameDirectory(game.id) ?: return null
        val artwork = File(gameDirectory, relativePath).canonicalFile
        return artwork.takeIf { it.isFile && it.toPath().startsWith(gameDirectory.toPath()) }
    }

    fun controllerLayoutFile(gameId: String): File? {
        return gameStore.controllerLayoutFile(gameId)
    }

    fun runtimeLogFile(gameId: String): File? {
        return resolveGameDirectory(gameId)?.let { File(it, PersistentRuntimeLogger.CURRENT_FILE) }
    }

    fun clearGameWebData(gameId: String) {
        WebStorage.getInstance().deleteOrigin(WebViewRuntimeBackend.gameOrigin(gameId))
        runCatching { saveStore.deleteGame(gameId) }
        runCatching {
            resolveGameDirectory(gameId)?.let { root ->
                val saveDirectory = File(root, "save").canonicalFile
                if (saveDirectory.parentFile == root.canonicalFile) {
                    saveDirectory.deleteRecursively()
                }
            }
        }
        runCatching { commonJsDataDirectory(gameId).deleteRecursively() }
    }

    fun deleteGame(gameId: String): Boolean {
        val deleted = catalog.deleteGame(gameId)
        if (deleted) {
            runCatching { saveStore.deleteGame(gameId) }
            runCatching { commonJsDataDirectory(gameId).deleteRecursively() }
            clearGameRuntimeSettings(gameId)
        }
        return deleted
    }

    private fun readRuntimeSettings(prefix: String = ""): RuntimeSettings = RuntimeSettings(
        orientation = preferences.enum(prefix + ORIENTATION, RuntimeOrientation.LANDSCAPE),
        scaleMode = preferences.enum(prefix + SCALE_MODE, RuntimeScaleMode.FIT),
        pixelSmoothing = preferences.getBoolean(prefix + PIXEL_SMOOTHING, true),
        immersiveMode = preferences.getBoolean(prefix + IMMERSIVE_MODE, true),
        pauseOnBackground = preferences.getBoolean(prefix + PAUSE_ON_BACKGROUND, true),
        vibrationEnabled = preferences.getBoolean(prefix + VIBRATION_ENABLED, true),
        engineMode = preferences.enum(prefix + ENGINE_MODE, RuntimeEngineMode.AUTO),
        webGlEnabled = preferences.getBoolean(prefix + WEBGL_ENABLED, true),
        legacyCompatibility = preferences.getBoolean(prefix + LEGACY_COMPATIBILITY, true),
        ignoreMissingFiles = preferences.getBoolean(prefix + IGNORE_MISSING_FILES, true),
        fpsLimit = preferences.getInt(prefix + FPS_LIMIT, FPS_AUTO)
            .takeIf { it in SUPPORTED_FPS_LIMITS },
        showFpsCounter = preferences.getBoolean(prefix + SHOW_FPS_COUNTER, false),
        recordLogs = preferences.getBoolean(prefix + RECORD_LOGS, false),
        modules = RuntimeModuleSettings(
            steamCompatibility = preferences.getBoolean(prefix + MODULE_STEAM, true),
            limitWorkerCount = preferences.getBoolean(prefix + LIMIT_BACKGROUND_LOAD, false),
            performanceOptimization = preferences.getBoolean(prefix + MODULE_PERFORMANCE, true),
            cheatBridge = preferences.getBoolean(prefix + MODULE_CHEATS, true),
            diagnosticsBridge = preferences.getBoolean(prefix + MODULE_DIAGNOSTICS, true),
        ),
    )

    private fun writeRuntimeSettings(
        value: RuntimeSettings,
        prefix: String = "",
        useCommonSettings: Boolean? = null,
    ) {
        preferences.edit {
            putString(prefix + ORIENTATION, value.orientation.name)
            putString(prefix + SCALE_MODE, value.scaleMode.name)
            putBoolean(prefix + PIXEL_SMOOTHING, value.pixelSmoothing)
            putBoolean(prefix + IMMERSIVE_MODE, value.immersiveMode)
            putBoolean(prefix + PAUSE_ON_BACKGROUND, value.pauseOnBackground)
            putBoolean(prefix + VIBRATION_ENABLED, value.vibrationEnabled)
            putString(prefix + ENGINE_MODE, value.engineMode.name)
            putBoolean(prefix + WEBGL_ENABLED, value.webGlEnabled)
            putBoolean(prefix + LEGACY_COMPATIBILITY, value.legacyCompatibility)
            putBoolean(prefix + IGNORE_MISSING_FILES, value.ignoreMissingFiles)
            putInt(prefix + FPS_LIMIT, value.fpsLimit ?: FPS_AUTO)
            putBoolean(prefix + SHOW_FPS_COUNTER, value.showFpsCounter)
            putBoolean(prefix + RECORD_LOGS, value.recordLogs)
            putBoolean(prefix + MODULE_STEAM, value.modules.steamCompatibility)
            putBoolean(prefix + LIMIT_BACKGROUND_LOAD, value.modules.limitWorkerCount)
            putBoolean(prefix + MODULE_PERFORMANCE, value.modules.performanceOptimization)
            putBoolean(prefix + MODULE_CHEATS, value.modules.cheatBridge)
            putBoolean(prefix + MODULE_DIAGNOSTICS, value.modules.diagnosticsBridge)
            useCommonSettings?.let { putBoolean(prefix + USE_COMMON_SETTINGS, it) }
        }
    }

    private fun clearGameRuntimeSettings(gameId: String) {
        val prefix = gameSettingsPrefix(gameId)
        preferences.edit {
            remove(prefix + USE_COMMON_SETTINGS)
            RUNTIME_SETTING_KEYS.forEach { remove(prefix + it) }
        }
    }

    private fun gameSettingsPrefix(gameId: String): String = "game_runtime_${gameId}_"

    private fun commonJsDataDirectory(gameId: String): File =
        File(commonJsDataRoot, WebViewRuntimeBackend.gameStorageKey(gameId))

    private fun resolveGameDirectory(gameId: String): File? {
        val directory = gameStore.findInstalledGame(gameId)
        if (directory == null) return null
        if (!gameStore.isDirectGame(gameId)) return directory
        if (!Environment.isExternalStorageManager()) return null
        return directory.takeIf { source ->
            StorageRoots.available(appContext).any { root ->
                runCatching {
                    source.toPath().startsWith(root.canonicalFile.toPath())
                }.getOrDefault(false)
            }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "makerplay_settings"
        const val DEFAULT_GAME_FOLDER = "default_game_folder"
        const val DEFAULT_INSTALL_MODE = "default_install_mode"
        const val THEME_MODE = "theme_mode"
        const val ORIENTATION = "runtime_orientation"
        const val SCALE_MODE = "runtime_scale_mode"
        const val PIXEL_SMOOTHING = "runtime_pixel_smoothing"
        const val IMMERSIVE_MODE = "runtime_immersive_mode"
        const val PAUSE_ON_BACKGROUND = "runtime_pause_on_background"
        const val VIBRATION_ENABLED = "runtime_vibration_enabled"
        const val ENGINE_MODE = "runtime_engine_mode"
        const val WEBGL_ENABLED = "runtime_webgl_enabled"
        const val LEGACY_COMPATIBILITY = "runtime_legacy_compatibility"
        const val IGNORE_MISSING_FILES = "runtime_ignore_missing_files"
        const val FPS_LIMIT = "runtime_fps_limit"
        const val SHOW_FPS_COUNTER = "runtime_show_fps_counter"
        const val RECORD_LOGS = "runtime_record_logs"
        const val MODULE_STEAM = "runtime_module_steam"
        const val LIMIT_BACKGROUND_LOAD = "runtime_limit_background_load"
        const val MODULE_PERFORMANCE = "runtime_module_performance"
        const val MODULE_CHEATS = "runtime_module_cheats"
        const val MODULE_DIAGNOSTICS = "runtime_module_diagnostics"
        const val USE_COMMON_SETTINGS = "use_common_settings"
        const val FPS_AUTO = -1
        val RUNTIME_SETTING_KEYS = listOf(
            ORIENTATION,
            SCALE_MODE,
            PIXEL_SMOOTHING,
            IMMERSIVE_MODE,
            PAUSE_ON_BACKGROUND,
            VIBRATION_ENABLED,
            ENGINE_MODE,
            WEBGL_ENABLED,
            LEGACY_COMPATIBILITY,
            IGNORE_MISSING_FILES,
            FPS_LIMIT,
            SHOW_FPS_COUNTER,
            RECORD_LOGS,
            MODULE_STEAM,
            LIMIT_BACKGROUND_LOAD,
            MODULE_PERFORMANCE,
            MODULE_CHEATS,
            MODULE_DIAGNOSTICS,
        )

        fun defaultDownloadsFolder(): String =
            File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS).path
    }
}

private inline fun <reified T : Enum<T>> SharedPreferences.enum(
    key: String,
    fallback: T,
): T = getString(key, null)?.let { value ->
    enumValues<T>().firstOrNull { it.name == value }
} ?: fallback