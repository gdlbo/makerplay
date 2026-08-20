package io.github.gdlbo.makerplay.runtime.api

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.gdlbo.makerplay.input.LogicalInputSnapshot

enum class RuntimeBackendCapability { AVAILABLE, NOT_INSTALLED }

data class RuntimeBackendDescriptor(
    val id: String,
    val displayName: String,
    val capability: RuntimeBackendCapability,
)

enum class RuntimeOrientation { AUTO, PORTRAIT, LANDSCAPE }

enum class RuntimeScaleMode { FIT, INTEGER, STRETCH }

enum class RuntimeEngineMode { AUTO, MV, MZ }

val SUPPORTED_FPS_LIMITS = listOf(30, 60, 90, 120, 144)

data class RuntimeModuleSettings(
    val steamCompatibility: Boolean = true,
    val limitWorkerCount: Boolean = false,
    val performanceOptimization: Boolean = true,
    val cheatBridge: Boolean = true,
    val diagnosticsBridge: Boolean = true,
)

data class RuntimeSettings(
    val orientation: RuntimeOrientation = RuntimeOrientation.LANDSCAPE,
    val scaleMode: RuntimeScaleMode = RuntimeScaleMode.FIT,
    val pixelSmoothing: Boolean = true,
    val immersiveMode: Boolean = true,
    val pauseOnBackground: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val engineMode: RuntimeEngineMode = RuntimeEngineMode.AUTO,
    val webGlEnabled: Boolean = true,
    val legacyCompatibility: Boolean = true,
    val ignoreMissingFiles: Boolean = true,
    val fpsLimit: Int? = null,
    val showFpsCounter: Boolean = false,
    val recordLogs: Boolean = false,
    val modules: RuntimeModuleSettings = RuntimeModuleSettings(),
) {
    init {
        require(fpsLimit == null || fpsLimit in SUPPORTED_FPS_LIMITS)
    }
}

data class LaunchRequest(
    val gameId: String,
    val smokeTest: Boolean = false,
    val settings: RuntimeSettings = RuntimeSettings(),
)

data class PreparedSession(
    val sessionId: String,
    val startUrl: String,
    val allowedOrigin: String,
    val settings: RuntimeSettings = RuntimeSettings(),
)

sealed interface RuntimeEvent {
    data class ExitRequested(
        val sessionId: String,
    ) : RuntimeEvent

    data class RendererProcessGone(
        val sessionId: String,
        val didCrash: Boolean,
    ) : RuntimeEvent

    data class WebGlContextChanged(
        val sessionId: String,
        val restored: Boolean,
    ) : RuntimeEvent

    data class CheatAvailabilityChanged(
        val sessionId: String,
        val available: Boolean,
    ) : RuntimeEvent
}

interface GameRuntimeBackend {
    val descriptor: RuntimeBackendDescriptor
    suspend fun prepare(request: LaunchRequest): PreparedSession

    @Composable
    fun RuntimeContent(
        session: PreparedSession,
        modifier: Modifier,
        onEvent: (RuntimeEvent) -> Unit = {},
        inputEnabled: Boolean = true,
        virtualInput: LogicalInputSnapshot = LogicalInputSnapshot(emptySet(), emptySet()),
        cheatFlags: CheatFlags = CheatFlags(),
        cheatCommand: CheatCommand? = null,
        onCheatCommandConsumed: (Long) -> Unit = {},
        onCheatCatalogChanged: (CheatCatalog) -> Unit = {},
        onReadyChanged: (Boolean) -> Unit = {},
    )

    suspend fun destroySession(sessionId: String)
}