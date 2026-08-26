package io.github.gdlbo.makerplay.runtime.webview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.gdlbo.makerplay.diagnostics.RuntimeLogger
import io.github.gdlbo.makerplay.input.LogicalInputSnapshot
import io.github.gdlbo.makerplay.runtime.api.CheatCatalog
import io.github.gdlbo.makerplay.runtime.api.CheatCommand
import io.github.gdlbo.makerplay.runtime.api.CheatFlags
import io.github.gdlbo.makerplay.runtime.api.GameRuntimeBackend
import io.github.gdlbo.makerplay.runtime.api.GameSaveStore
import io.github.gdlbo.makerplay.runtime.api.LaunchRequest
import io.github.gdlbo.makerplay.runtime.api.PreparedSession
import io.github.gdlbo.makerplay.runtime.api.RuntimeBackendCapability
import io.github.gdlbo.makerplay.runtime.api.RuntimeBackendDescriptor
import io.github.gdlbo.makerplay.runtime.api.RuntimeEngineMode
import io.github.gdlbo.makerplay.runtime.api.RuntimeEvent
import io.github.gdlbo.makerplay.vfs.GameFileSystem
import io.github.gdlbo.makerplay.vfs.RpgMakerGameMount
import io.github.gdlbo.makerplay.vfs.VfsOpenResult
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WebViewRuntimeBackend(
    private val logger: RuntimeLogger,
    private val saveStore: GameSaveStore? = null,
    private val gameIndexDirectory: ((String) -> File?)? = null,
    private val gameLoggerFactory: ((File) -> RuntimeLogger)? = null,
    private val commonJsDataDirectory: (String) -> File? = { null },
    private val gameDirectory: (String) -> File? = { null },
) : GameRuntimeBackend {
    private val deploymentInspector = DeploymentInspector()
    private val sessions = ConcurrentHashMap<String, RuntimeSession>()
    private val mvSaveStores = ConcurrentHashMap<String, WriteBehindGameSaveStore>()
    override val descriptor = RuntimeBackendDescriptor(
        id = "webview",
        displayName = "Chromium WebView",
        capability = RuntimeBackendCapability.AVAILABLE,
    )

    override suspend fun prepare(request: LaunchRequest): PreparedSession {
        if (request.smokeTest) {
            logger.info(
                "runtime.prepare",
                mapOf("backend" to descriptor.id, "gameId" to request.gameId),
            )
            return PreparedSession(
                sessionId = request.gameId,
                startUrl = SMOKE_URL,
                allowedOrigin = ASSET_ORIGIN,
                settings = request.settings,
                runtimeProfile = io.github.gdlbo.makerplay.runtime.api.unavailableRuntimeProfile(request.settings),
            )
        }
        val importedRoot = gameDirectory(request.gameId)
            ?: throw IllegalArgumentException("The imported game is unavailable.")
        if (looksLikeWolfDeployment(importedRoot)) {
            throw IllegalArgumentException(
                "WOLF RPG games require the native WOLF runtime; Chromium WebView cannot execute Game.exe.",
            )
        }
        val root = File(importedRoot, "www").takeIf { File(it, "index.html").isFile } ?: importedRoot
        val gameLogger = if (request.settings.recordLogs) {
            gameLoggerFactory?.invoke(root) ?: logger
        } else {
            logger
        }
        var sessionRegistered = false
        try {
            gameLogger.info(
                "runtime.prepare",
                mapOf("backend" to descriptor.id, "gameId" to request.gameId),
            )
            val sessionId = UUID.randomUUID().toString()
            val gameOrigin = gameOrigin(request.gameId)
            val fileSystem =
                RpgMakerGameMount.open(root, gameIndexDirectory?.invoke(request.gameId) ?: root)
            val fingerprint = deploymentInspector.inspect(fileSystem).copy(
                deploymentLayout = if (root != importedRoot) io.github.gdlbo.makerplay.runtime.api.DeploymentLayout.WWW
                else io.github.gdlbo.makerplay.runtime.api.DeploymentLayout.ROOT,
            )
            val runtimeProfile = RuntimeProfileResolver.resolve(
                fingerprint = fingerprint,
                settings = request.settings,
            )
            gameLogger.info("runtime.profile", mapOf(
                "engine" to runtimeProfile.fingerprint.engine.name,
                "layout" to runtimeProfile.fingerprint.deploymentLayout.name,
                "selectedEngine" to runtimeProfile.selectedEngine.name,
                "modules" to runtimeProfile.moduleDecisions.entries.joinToString(",") { "${it.key}:${it.value}" },
            ))
            val forcedEngineAvailable = when (request.settings.engineMode) {
                RuntimeEngineMode.AUTO -> true
                RuntimeEngineMode.MV -> runtimeProfile.useMvNativeSaves
                RuntimeEngineMode.MZ -> runtimeProfile.useMzNativeSaves
            }
            if (!forcedEngineAvailable) {
                throw IllegalArgumentException(
                    "The selected RPG Maker engine does not match this game's files.",
                )
            }
            val commonJsDataRoot = commonJsDataDirectory(request.gameId)
            var writeBehindStore: WriteBehindGameSaveStore? = null
            val reportSaveFailure: (String, String?) -> Unit = { code, failureClass ->
                gameLogger.error(
                    "runtime.javascript.bridge_call_failed",
                    mapOf(
                        "gameId" to request.gameId,
                        "code" to code,
                        "failureClass" to failureClass.orEmpty(),
                    ),
                )
            }
            val saveBridge = if (saveStore != null) {
                when {
                    runtimeProfile.useMzNativeSaves -> {
                        val folderStore = GameFolderSaveStore(root, ".rmmzsave")
                        RuntimeSaveBridgeSession(
                            gameOrigin,
                            SaveBridgeProtocol(request.gameId, folderStore, reportSaveFailure),
                        )
                    }

                    runtimeProfile.useMvNativeSaves -> {
                        val folderStore = GameFolderSaveStore(root, ".rpgsave")
                        val bufferedStore = mvSaveStores.computeIfAbsent(request.gameId) {
                            WriteBehindGameSaveStore(
                                gameId = request.gameId,
                                delegate = folderStore,
                                onPersistenceFailure = { error ->
                                    reportSaveFailure("storage", error.javaClass.name)
                                },
                            ).also { it.preload() }
                        }
                        writeBehindStore = bufferedStore
                        MvRuntimeSaveBridgeSession(
                            allowedOrigin = gameOrigin,
                            protocol = SaveBridgeProtocol(
                                request.gameId,
                                bufferedStore,
                                reportSaveFailure
                            ),
                            token = newBridgeToken(),
                        )
                    }

                    else -> null
                }
            } else null
            val nodeProtocol = commonJsDataRoot?.let { dataRoot ->
                NodeFileProtocol(
                    gameFileSystem = fileSystem,
                    dataRoot = dataRoot,
                    gameId = request.gameId.takeIf { writeBehindStore != null },
                    saveStore = writeBehindStore,
                )
            }
            val responder = GameOriginResponder(
                host = gameOrigin.removePrefix("https://"),
                sessionId = sessionId,
                fileSystem = fileSystem,
                ignoreMissingFiles = request.settings.ignoreMissingFiles,
                onMissingFileIgnored = { path, mimeType ->
                    gameLogger.info(
                        "runtime.resource.missing_fallback",
                        mapOf(
                            "gameId" to request.gameId,
                            "resource" to path,
                            "mimeType" to mimeType,
                        ),
                    )
                },
                overlayAsset = nodeProtocol?.let { protocol -> protocol::overlayAsset },
            )
            val commonJs = nodeProtocol?.let {
                CommonJsRuntimeConfiguration(
                    allowedOrigin = gameOrigin,
                    token = newBridgeToken(),
                    protocol = it,
                )
            }
            val runtimeSession = RuntimeSession(
                gameId = request.gameId,
                responder = responder,
                commonJs = commonJs,
                saveBridge = saveBridge,
                writeBehindStore = writeBehindStore,
                logger = gameLogger,
            )
            synchronized(sessions) {
                check(sessions.size < MAX_ACTIVE_SESSIONS) { "Too many active runtime sessions" }
                check(
                    sessions.putIfAbsent(
                        sessionId,
                        runtimeSession
                    ) == null
                ) { "Runtime session collision" }
            }
            sessionRegistered = true
            return PreparedSession(
                sessionId = sessionId,
                startUrl = "$gameOrigin/session/$sessionId/asset/index.html",
                allowedOrigin = gameOrigin,
                settings = request.settings,
                runtimeProfile = runtimeProfile,
            )
        } finally {
            if (!sessionRegistered && gameLogger !== logger) {
                (gameLogger as? AutoCloseable)?.close()
            }
        }
    }

    @Composable
    override fun RuntimeContent(
        session: PreparedSession,
        modifier: Modifier,
        onEvent: (RuntimeEvent) -> Unit,
        inputEnabled: Boolean,
        virtualInput: LogicalInputSnapshot,
        cheatFlags: CheatFlags,
        cheatCommand: CheatCommand?,
        onCheatCommandConsumed: (Long) -> Unit,
        onCheatCatalogChanged: (CheatCatalog) -> Unit,
        onReadyChanged: (Boolean) -> Unit,
    ) {
        val runtimeSession = sessions[session.sessionId]
        check(session.startUrl == SMOKE_URL || runtimeSession != null) { "Runtime session is no longer active" }
        RuntimeWebView(
            startUrl = session.startUrl,
            responder = runtimeSession?.responder,
            commonJs = runtimeSession?.commonJs,
            saveBridge = runtimeSession?.saveBridge,
            onRuntimeError = { event, fields ->
                val sessionFields = fields + ("sessionId" to session.sessionId)
                val sessionLogger = runtimeSession?.logger ?: logger
                if (event == "runtime.javascript.console" || event == "runtime.renderer_responsive") {
                    sessionLogger.info(event, sessionFields)
                } else {
                    sessionLogger.error(event, sessionFields)
                }
            },
            inputEnabled = inputEnabled,
            virtualInput = virtualInput,
            onRendererGone = { didCrash ->
                (runtimeSession?.logger ?: logger).info(
                    "runtime.renderer_gone",
                    mapOf("sessionId" to session.sessionId, "didCrash" to didCrash.toString()),
                )
                onEvent(RuntimeEvent.RendererProcessGone(session.sessionId, didCrash))
            },
            onCloseRequested = {
                onEvent(RuntimeEvent.ExitRequested(session.sessionId))
            },
            onWebGlContextChanged = { restored ->
                (runtimeSession?.logger ?: logger).info(
                    if (restored) "runtime.webgl_context_restored" else "runtime.webgl_context_lost",
                    mapOf("sessionId" to session.sessionId),
                )
                onEvent(RuntimeEvent.WebGlContextChanged(session.sessionId, restored))
            },
            onCheatAvailabilityChanged = { available ->
                onEvent(RuntimeEvent.CheatAvailabilityChanged(session.sessionId, available))
            },
            cheatFlags = cheatFlags,
            cheatCommand = cheatCommand,
            onCheatCommandConsumed = onCheatCommandConsumed,
            onCheatCatalogChanged = onCheatCatalogChanged,
            onReadyChanged = onReadyChanged,
            runtimeSettings = session.settings,
            runtimeProfile = session.runtimeProfile,
            modifier = modifier,
        )
    }

    override suspend fun destroySession(sessionId: String) {
        val (session, storeToClose) = synchronized(sessions) {
            val removed = sessions.remove(sessionId)
            val store = removed?.writeBehindStore
            val orphanedStore = store?.takeIf { candidate ->
                sessions.values.none { it.writeBehindStore === candidate }
            }
            if (orphanedStore != null) {
                mvSaveStores.remove(removed.gameId, orphanedStore)
            }
            removed to orphanedStore
        }
        storeToClose?.close()
        val sessionLogger = session?.logger ?: logger
        sessionLogger.info(
            "runtime.destroy",
            buildMap {
                put("backend", descriptor.id)
                put("sessionId", sessionId)
                session?.let { put("gameId", it.gameId) }
            },
        )
        if (sessionLogger !== logger) {
            (sessionLogger as? AutoCloseable)?.close()
        }
    }

    companion object {
        const val ASSET_ORIGIN = "https://appassets.androidplatform.net"
        const val GAME_ORIGIN_SUFFIX = ".game.local"
        const val SMOKE_URL = "$ASSET_ORIGIN/assets/smoke/index.html"
        private const val MAX_ACTIVE_SESSIONS = 8

        fun gameOrigin(gameId: String): String {
            return "https://g-${gameStorageKey(gameId)}$GAME_ORIGIN_SUFFIX"
        }

        fun gameStorageKey(gameId: String): String = MessageDigest.getInstance("SHA-256")
            .digest(gameId.toByteArray(StandardCharsets.UTF_8))
            .take(16)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private data class RuntimeSession(
        val gameId: String,
        val responder: GameOriginResponder,
        val commonJs: CommonJsRuntimeConfiguration?,
        val saveBridge: RuntimeSaveBridgeConfiguration?,
        val writeBehindStore: WriteBehindGameSaveStore?,
        val logger: RuntimeLogger,
    )
}

private fun newBridgeToken(): String = ByteArray(32)
    .also(SecureRandom()::nextBytes)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun supportsMzNativeSaves(fileSystem: GameFileSystem): Boolean =
    when (
        val result = fileSystem.open("js/rmmz_managers.js")
    ) {
        is VfsOpenResult.Found -> {
            result.stream.close()
            true
        }

        else -> bundledEngineName(fileSystem) == "MZ"
    }

internal fun supportsMvNativeSaves(fileSystem: GameFileSystem): Boolean =
    when (
        val result = fileSystem.open("js/rpg_managers.js")
    ) {
        is VfsOpenResult.Found -> {
            result.stream.close()
            true
        }

        else -> bundledEngineName(fileSystem) == "MV"
    }

private fun bundledEngineName(fileSystem: GameFileSystem): String? {
    val result = fileSystem.open("js/game.js")
    if (result !is VfsOpenResult.Found) return null
    val head = result.stream.use { stream ->
        String(stream.readUpTo(BUNDLED_ENGINE_PROBE_BYTES), StandardCharsets.UTF_8)
    }
    return BUNDLED_ENGINE_NAME_PATTERN.find(head)?.groupValues?.get(1)
}

private const val BUNDLED_ENGINE_PROBE_BYTES = 256 * 1024
private val BUNDLED_ENGINE_NAME_PATTERN =
    Regex("""RPGMAKER_NAME\s*=\s*["']([^"']+)["']""")

private fun InputStream.readUpTo(limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var total = 0
    while (total < buffer.size) {
        val read = read(buffer, total, buffer.size - total)
        if (read < 0) break
        total += read
    }
    return buffer.copyOf(total)
}

private fun looksLikeWolfDeployment(root: File): Boolean =
    File(root, "Game.exe").isFile && File(root, "Game.dat").isFile &&
        (File(root, "CommonEvent.dat").isFile || File(root, "Data/BasicData/Game.dat").isFile)
