package io.github.gdlbo.makerplay.runtime.wolf

import android.opengl.GLSurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import io.github.gdlbo.makerplay.diagnostics.RuntimeLogger
import io.github.gdlbo.makerplay.input.LogicalInputSnapshot
import io.github.gdlbo.makerplay.input.PhysicalInputNormalizer
import io.github.gdlbo.makerplay.runtime.api.CheatCatalog
import io.github.gdlbo.makerplay.runtime.api.CheatCommand
import io.github.gdlbo.makerplay.runtime.api.CheatFlags
import io.github.gdlbo.makerplay.runtime.api.GameRuntimeBackend
import io.github.gdlbo.makerplay.runtime.api.GameSaveStore
import io.github.gdlbo.makerplay.runtime.api.LaunchRequest
import io.github.gdlbo.makerplay.runtime.api.PreparedSession
import io.github.gdlbo.makerplay.runtime.api.RuntimeBackendCapability
import io.github.gdlbo.makerplay.runtime.api.RuntimeBackendDescriptor
import io.github.gdlbo.makerplay.runtime.api.RuntimeEvent
import io.github.gdlbo.makerplay.runtime.api.RuntimeSettings
import io.github.gdlbo.makerplay.runtime.api.WolfNativeBridge
import io.github.gdlbo.makerplay.wolfformat.EventCommand
import io.github.gdlbo.makerplay.wolfformat.GameDataSource
import io.github.gdlbo.makerplay.wolfformat.GameDat
import io.github.gdlbo.makerplay.wolfformat.WolfFormatException
import io.github.gdlbo.makerplay.input.GameAction
import io.github.gdlbo.makerplay.wolfformat.MapFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * Native runtime backend for WOLF RPG (Woditor) deployments.
 *
 * WOLF games ship a closed Windows `Game.exe` plus binary project data; they
 * cannot execute inside the Chromium WebView backend. This backend hosts a
 * clean-room C++ interpreter through [WolfNativeBridge]. Until the native
 * library is wired in (milestone 2 of docs/wolf-rpg-runtime.md), sessions are
 * prepared and validated but playback reports the native runtime as not yet
 * installed instead of attempting WebView execution.
 */
class WolfRuntimeBackend(
    private val logger: RuntimeLogger,
    private val gameDirectory: (String) -> File? = { null },
    private val bridge: WolfNativeBridgeProvider = WolfNativeBridgeProvider { WolfNativeJni.tryCreate() },
    /** Creates a per-game file logger when [LaunchRequest.settings.recordLogs] is set. */
    private val gameLoggerFactory: ((File) -> RuntimeLogger)? = null,
    @Suppress("unused") private val saveStore: GameSaveStore? = null,
) : GameRuntimeBackend {

    override val descriptor: RuntimeBackendDescriptor by lazy {
        RuntimeBackendDescriptor(
            id = BACKEND_ID,
            displayName = "WOLF RPG Native",
            capability = if (bridge.get() != null) {
                RuntimeBackendCapability.AVAILABLE
            } else {
                RuntimeBackendCapability.NOT_INSTALLED
            },
        )
    }

    private val sessions = ConcurrentHashMap<String, WolfNativeSession>()
    private val gameLoopScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val currentDirections =
        AtomicReference<Set<WolfGameEngine.Direction>>(emptySet())
    private val currentConfirm = AtomicBoolean(false)

    /** Latest pressed WOLF key id (0 none; 1-4 dirs, 5 decide, 6 cancel, 7 shift),
     *  published by the input LaunchedEffect for the interpreter's InputKey poll. */
    private val currentWolfKey = AtomicInteger(0)

    override suspend fun prepare(request: LaunchRequest): PreparedSession {
        logger.info(
            "runtime.prepare",
            mapOf("backend" to descriptor.id, "gameId" to request.gameId),
        )
        if (request.smokeTest) {
            return PreparedSession(
                sessionId = request.gameId,
                startUrl = NATIVE_SMOKE_URL,
                allowedOrigin = NATIVE_ORIGIN,
                settings = request.settings,
            )
        }
        val root = gameDirectory(request.gameId)
            ?: throw IllegalArgumentException("The imported game is unavailable.")
        val archiveOnly = hasWolfArchives(root)
        if (!archiveOnly) validateWolfDeployment(root)
        val project = loadProjectSettings(root, archiveOnly)
        val sessionLogger =
            if (request.settings.recordLogs) gameLoggerFactory?.invoke(root) ?: logger else logger
        val sessionId = UUID.randomUUID().toString()
        sessions[sessionId] = WolfNativeSession(
            id = sessionId,
            gameId = request.gameId,
            gameRoot = root,
            settings = request.settings,
            project = project,
            logger = sessionLogger,
        )
        return PreparedSession(
            sessionId = sessionId,
            startUrl = NATIVE_SESSION_URL,
            allowedOrigin = NATIVE_ORIGIN,
            settings = request.settings,
        )
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
        // Stable across recompositions so effects keyed on it do not churn.
        val nativeBridge = remember { bridge.get() }

        // Static boot pipeline (milestone 4): compose the initial map frame,
        // hand it to the native renderer, then present via GLSurfaceView.
        var ready by remember(session.sessionId) { mutableStateOf(false) }
        var loadError by remember(session.sessionId) { mutableStateOf<String?>(null) }
        val uiState = remember(session.sessionId) { WolfUiState() }
        var confirmWasDown by remember(session.sessionId) { mutableStateOf(false) }

        // Rising-edge detection for the OK action drives message advancement.
        fun consumeConfirmEdge(): Boolean {
            val down = currentConfirm.get()
            val edge = down && !confirmWasDown
            confirmWasDown = down
            return edge
        }

        // Map logical/virtual controller actions onto engine directions.
        LaunchedEffect(virtualInput) {
            // Virtual controller buttons dispatch key codes (e.g. Enter = 66);
            // map them through the same table as physical keyboard input.
            val pressed = virtualInput.pressedActions +
                virtualInput.pressedKeyCodes.mapNotNull { PhysicalInputNormalizer.keyMap[it] }
            logger.info(
                "runtime.debug_input",
                mapOf(
                    "actions" to virtualInput.pressedActions.toString(),
                    "keys" to virtualInput.pressedKeyCodes.toString(),
                ),
            )
            val dirs = buildSet {
                if (GameAction.UP in pressed) add(WolfGameEngine.Direction.UP)
                if (GameAction.DOWN in pressed) add(WolfGameEngine.Direction.DOWN)
                if (GameAction.LEFT in pressed) add(WolfGameEngine.Direction.LEFT)
                if (GameAction.RIGHT in pressed) add(WolfGameEngine.Direction.RIGHT)
            }
            currentDirections.set(dirs)
            currentConfirm.set(GameAction.OK in pressed)
            currentWolfKey.set(
                when {
                    GameAction.OK in pressed -> 10 // 決定
                    GameAction.CANCEL in pressed || GameAction.ESCAPE in pressed -> 11 // キャンセル
                    GameAction.UP in pressed -> 8
                    GameAction.DOWN in pressed -> 2
                    GameAction.LEFT in pressed -> 4
                    GameAction.RIGHT in pressed -> 6
                    GameAction.SHIFT in pressed -> 7
                    else -> 0
                },
            )
        }
        LaunchedEffect(session.sessionId, nativeBridge) {
            onReadyChanged(false)
            loadError = null
            if (nativeBridge == null) {
                loadError = "The WOLF native runtime is unavailable."
                logger.error(
                    "runtime.native_unavailable",
                    mapOf("backend" to descriptor.id, "sessionId" to session.sessionId),
                )
                return@LaunchedEffect
            }
            val stored = sessions[session.sessionId]
            if (stored == null) {
                loadError = "The WOLF session is no longer available."
                return@LaunchedEffect
            }
            if (stored.project == null) {
                loadError = "The WOLF project settings could not be loaded."
                return@LaunchedEffect
            }
            val frame = runCatching {
                withContext(Dispatchers.IO) {
                    val project = stored.project
                    openDataSource(stored).use { source ->
                        WolfSceneLoader.loadStaticFrame(source, project)
                    }
                }
            }.onFailure {
                loadError = it.message?.takeIf(String::isNotBlank)
                    ?: it::class.simpleName
                    ?: "Unknown WOLF loading error"
                logger.error("runtime.static_frame_failed", mapOf("error" to (it.message ?: "unknown")))
            }.getOrNull()
            if (frame != null) {
                val handle = ensureNativeSession(session, nativeBridge)
                nativeBridge.setStaticFrame(handle, frame.rgba, frame.width, frame.height)
                ready = true
                onReadyChanged(true)
                runGameLoop(nativeBridge, handle, stored, uiState) { consumeConfirmEdge() }
            }
        }

        // Message/choice presentation over the game surface. Declared after
        // the GL surface so the opaque SurfaceView cannot cover it.
        if (ready && nativeBridge != null) {
            val handle = remember(session.sessionId) { ensureNativeSession(session, nativeBridge) }
            AndroidView(
                modifier = modifier,
                factory = { ctx ->
                    WolfRenderSurface(ctx).apply {
                        setEGLContextClientVersion(2)
                        // Render above the Compose window: the game frame is the
                        // primary content and carries messages/choices in-frame.
                        setZOrderOnTop(true)
                        holder.setFormat(android.graphics.PixelFormat.OPAQUE)
                        setRenderer(WolfFrameRenderer(nativeBridge, handle))
                        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    }
                },
                update = { surface -> surface.setHandle(ensureNativeSession(session, nativeBridge)) },
            )
        } else {
            Box(
                modifier = modifier.background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (loadError != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "WOLF RPG game could not be displayed",
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = loadError.orEmpty(),
                            color = Color.White,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
        // Message/choices render in-frame (composeFrame): the GL surface is
        // ZOrderOnTop, so Compose overlays would be hidden beneath it.
    }

    /**
     * Milestone-5 game loop: advances the deterministic engine at the project's
     * logical fps and pushes recomposed frames to the native presenter.
     */
    private fun runGameLoop(
        bridge: WolfNativeBridge,
        handle: Long,
        stored: WolfNativeSession,
        ui: WolfUiState,
        confirmEdges: () -> Boolean,
    ): kotlinx.coroutines.Job? {
        val project = stored.project ?: return null
        val job = gameLoopScope.launch {
            try {
            openDataSource(stored).use { source ->
                val (initialPath, initialMap) = initialMapPath(source)
                var mapPath = initialPath
                val commonEvents = HashMap<Int, List<EventCommand>>()
                val commonEventsByName = HashMap<String, List<EventCommand>>()
                runCatching {
                    io.github.gdlbo.makerplay.wolfformat.CommonEventDat.parse(
                        source.read("Data/BasicData/CommonEvent.dat"),
                    ).events.forEach {
                        commonEvents[it.id] = it.commands
                        commonEventsByName[it.title] = it.commands
                    }
                }.onFailure {
                    logger.error("runtime.commont_events_failed", mapOf("error" to (it.message ?: "unknown")))
                }
                logger.info(
                    "runtime.debug_ce",
                    mapOf("byId" to commonEvents.size.toString(), "byName" to commonEventsByName.size.toString()),
                )
                var map = initialMap
                val tilesets = io.github.gdlbo.makerplay.wolfformat.TileSetData.parse(
                    source.read("Data/BasicData/TileSetData.dat"),
                )
                val engine = WolfGameEngine(project, map, tilesets)

                var interpreter: WolfInterpreter? = null
                val pictures = WolfPictureState()
                val savesRoot = File(stored.gameRoot, "MakerPlaySaves")
                val saveManager = WolfGameSaveManager(savesRoot)
                val audio = WolfAudioPlayer()
                // Persisted machine state: saves snapshot the live interpreter,
                // loads seed every subsequently created interpreter.
                val machineVariables = HashMap<Int, Int>()
                val machineStrings = HashMap<Int, String>()
                val hostCallbacks = object : WolfInterpreter.Host {
                    override fun onMessage(text: String) { ui.message = text }
                    override fun onChoices(options: List<String>) { ui.choices = options }
                    override fun onKeyPoll(): Int = currentWolfKey.get()
                    override fun onCondition(command: EventCommand, satisfied: Boolean) {
                        if (command.params.firstOrNull() == 20) {
                            logger.info(
                                "runtime.debug_condition",
                                mapOf("params" to command.params.joinToString(), "satisfied" to satisfied.toString()),
                            )
                        }
                    }
                    override fun onTeleport(mapId: Int, tileX: Int, tileY: Int) {
                        logger.info(
                            "runtime.debug_teleport",
                            mapOf("mapId" to mapId.toString(), "x" to tileX.toString(), "y" to tileY.toString()),
                        )
                        // WOLF applies a move-place immediately: the map and
                        // hero change at the teleport point, and the calling
                        // event continues on the new map (fade/inits run after).
                        val target = "Data/MapData/Map%03d.mps".format(mapId)
                        val bytes = runCatching { source.read(target) }.getOrNull()
                        if (bytes != null) {
                            val nextMap = MapFile.parse(bytes)
                            map = nextMap
                            mapPath = target
                            logger.info("runtime.debug_transfer", mapOf("path" to target))
                            pictures.clear()
                            engine.replaceMap(nextMap, tileX, tileY)
                        } else {
                            engine.queueTransfer(mapId, tileX, tileY)
                        }
                    }
                    override fun onSave(slot: Int): Boolean {
                        return runCatching {
                            interpreter?.let {
                                machineVariables.clear(); machineVariables.putAll(it.variables)
                                machineStrings.clear(); machineStrings.putAll(it.strings)
                            }
                            saveManager.save(
                                "slot-$slot",
                                WolfSaveFormat.GameState(
                                    title = project.title,
                                    mapPath = mapPath,
                                    tileX = engine.position().tileX,
                                    tileY = engine.position().tileY,
                                    variables = HashMap(machineVariables),
                                    strings = HashMap(machineStrings),
                                ),
                            )
                        }.isSuccess
                    }

                    override fun onLoad(slot: Int): Boolean {
                        return runCatching {
                            val state = saveManager.load("slot-$slot")
                            machineVariables.clear(); machineVariables.putAll(state.variables)
                            machineStrings.clear(); machineStrings.putAll(state.strings)
                            if (source.has(state.mapPath)) {
                                map = MapFile.parse(source.read(state.mapPath))
                                mapPath = state.mapPath
                            }
                            true
                        }.getOrDefault(false)
                    }

                    override fun onPicture(command: EventCommand) {
                        pictures.apply(command)
                    }
                    override fun onCommand(command: EventCommand) {
                        logger.info(
                            "runtime.debug_command",
                            mapOf("op" to command.commandType.toString(), "params" to command.params.joinToString()),
                        )
                    }

                    override fun onScreenEffect(command: EventCommand) {
                        // Transitions/color changes reset overlays; recompose.
                        pictures.clear()
                    }

                    override fun onEffect(command: EventCommand) {
                        // Effect commands may load mask pictures; recompose.
                        pictures.apply(command)
                    }

                    override fun onSound(command: EventCommand) {
                        // Sound command layout varies by editor revision; the
                        // first string, when present, names the media file.
                        val raw = command.strings.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
                        if (raw.contains('\\')) return // unresolved escape tag
                        val name = raw.substringAfterLast('/').removePrefix("/")
                        val bgmPath = resolveMedia(source, "Data/BGM", name)
                        val sePath = resolveMedia(source, "Data/SE", name)
                        when {
                            bgmPath != null -> audio.playBgm(File(stored.gameRoot, bgmPath))
                            sePath != null -> audio.playSe(File(stored.gameRoot, sePath))
                        }
                    }
                }

                val tickMillis = 1000L / project.fps.coerceAtLeast(1)
                var lastFrameKey: Any? = null
                var lastBlockingKey: String? = null
                var lastRunningPc = -1
                var samePcTicks = 0
                var lastFrame: WolfSceneLoader.StaticFrame? = null
                var forceCompose = true
                while (isActive) {
                    val active = interpreter
                    if (active != null && !active.finished) {
                        val blocking = active.currentBlocking()
                        val stateKey = blocking?.let { it::class.simpleName ?: "?" } ?: "running"
                        if (currentConfirm.get()) {
                            (stored.logger ?: logger).info(
                                "runtime.debug_confirm",
                                mapOf("state" to stateKey, "pc" to (active.currentPc().toString())),
                            )
                        }
                        if (stateKey != lastBlockingKey) {
                            (stored.logger ?: logger).info(
                                "runtime.debug_block",
                                mapOf("state" to stateKey, "pc" to (active.currentPc().toString())),
                            )
                            lastBlockingKey = stateKey
                        } else if (stateKey == "running" && active.currentPc() == lastRunningPc) {
                            samePcTicks++
                            if (samePcTicks % 120 == 0) {
                                (stored.logger ?: logger).info(
                                    "runtime.debug_stuck",
                                    mapOf("pc" to lastRunningPc.toString(), "ticks" to samePcTicks.toString()),
                                )
                            }
                        } else {
                            lastRunningPc = active.currentPc()
                            samePcTicks = 0
                        }
                        when (blocking) {
                            is WolfInterpreter.Blocking.Message -> {
                                if (confirmEdges()) active.advance()
                            }
                            is WolfInterpreter.Blocking.Choices -> {
                                if (confirmEdges()) active.choose(0)
                            }
                            else -> Unit
                        }
                        active.tick()
                        if (active.finished && blocking == null) interpreter = null
                    } else {
                        engine.pendingTransfer?.let { (mapId, pos) ->
                            val target = "Data/MapData/Map%03d.mps".format(mapId)
                            val bytes = runCatching { source.read(target) }.getOrNull()
                                ?: runCatching { source.read("Data/" + mapPath.removePrefix("Data/")) }.getOrNull()
                            if (bytes != null) {
                                val nextMap = MapFile.parse(bytes)
                                map = nextMap
                                mapPath = target
                                logger.info("runtime.debug_transfer", mapOf("path" to target))
                                pictures.clear()
                                engine.replaceMap(nextMap, pos.first, pos.second)
                            }
                        }
                        engine.setInput(currentDirections.get(), currentConfirm.get())
                        engine.tick()
                        val fired = engine.drainFiredTriggers()
                        for (trigger in fired) {
                            if (interpreter != null && !interpreter!!.finished) break
                            val runner = WolfInterpreter(
                                hostCallbacks, commonEvents, commonEventsByName,
                                initialVariables = machineVariables,
                                initialStrings = machineStrings,
                            )
                            runner.start(trigger.page.commands)
                            logger.info(
                                "runtime.debug_fire",
                                mapOf(
                                    "eventId" to trigger.eventId.toString(),
                                    "cmds" to trigger.page.commands.size.toString(),
                                    "first" to (trigger.page.commands.firstOrNull()?.commandType?.toString() ?: "none"),
                                ),
                            )
                            interpreter = runner
                        }
                    }
                    // Recompose only when something visible changed: hero moved
                    // or an event switched the presented state.
                    val heroPos = if (interpreter?.finished != true) null else engine.position()
                    val msgText = (interpreter?.currentBlocking() as? WolfInterpreter.Blocking.Message)?.text
                    val choiceOpts = (interpreter?.currentBlocking() as? WolfInterpreter.Blocking.Choices)?.options
                        ?: emptyList()
                    val key = Triple(mapPath, heroPos?.tileX to heroPos?.tileY, heroPos?.offsetX) to
                        pictures.version() to (msgText to choiceOpts)
                    val frame = if (key != lastFrameKey || forceCompose) {
                        lastFrameKey = key
                        runCatching {
                            withContext(Dispatchers.Default) {
                                WolfSceneLoader.composeFrame(
                                    source, project, map, tilesets, heroTile = heroPos,
                                    pictures = pictures.all(),
                                    messageText = msgText,
                                    choiceOptions = choiceOpts,
                                )
                            }
                        }.onFailure {
                            (stored.logger ?: logger).error(
                                "runtime.loop_frame_failed",
                                mapOf("error" to (it.message ?: "unknown")),
                            )
                        }.getOrNull()
                    } else {
                        lastFrame
                    }
                    if (frame != null) {
                        bridge.setStaticFrame(handle, frame.rgba, frame.width, frame.height)
                    }
                    lastFrame = frame
                    delay(tickMillis.milliseconds)
                }
            }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                stored.logger ?: logger
                logger.error("runtime.loop_crashed", mapOf("error" to (e.message ?: "unknown")))
            } finally {
                stored.loopJob = null
            }
        }
        stored.loopJob = job
        return job
    }

    /** Finds a media file by base name (extension- and case-insensitive). */
    private fun resolveMedia(
        source: GameDataSource,
        dir: String,
        name: String,
    ): String? {
        val lower = name.lowercase()
        val base = lower.substringBeforeLast('.')
        return source.list(dir).firstOrNull {
            val entry = it.lowercase()
            entry == lower || entry.substringBeforeLast('.') == base
        }?.let { "$dir/$it" }
    }

    /**
     * Picks the first map file whose bytes actually parse. Some deployments
     * ship special pseudo-maps (000title.mps) or format variants that the
     * parser rejects; skipping them lets compatible maps still boot.
     */
    /** Picks the first map file whose bytes actually parse, returning the parsed map. */
    private fun initialMapPath(source: GameDataSource): Pair<String, MapFile> {
        val candidates = source.list("Data/MapData")
            .filter { it.endsWith(".mps", true) }
            .sorted()
        val errors = mutableListOf<String>()
        var fallback: Pair<String, MapFile>? = null
        for (name in candidates) {
            val path = "Data/MapData/$name"
            try {
                val parsed = MapFile.parse(source.read(path))
                // Boot into the game's real start map: games' opening maps
                // carry an autorun (triggerCondition 1) page, while test/sample
                // maps (alphabetically first) may be empty of events entirely.
                if (parsed.events.any { e -> e.pages.any { it.triggerCondition == 1 } }) {
                    return path to parsed
                }
                if (fallback == null) fallback = path to parsed
            } catch (e: WolfFormatException) {
                errors += "$name: ${e.message?.take(60)}"
            }
        }
        return fallback ?: throw WolfFormatException(
            "No parseable map found under Data/MapData (${errors.size} tried)" +
                if (errors.isNotEmpty()) ": ${errors.first()}" else "",
        )
    }

    private fun ensureNativeSession(
        session: PreparedSession,
        bridge: WolfNativeBridge,
    ): Long {
        val stored = sessions[session.sessionId] ?: return 0L
        if (stored.nativeHandle == 0L) {
            stored.nativeHandle = bridge.loadGame(stored.gameId, stored.gameRoot.absolutePath)
        }
        return stored.nativeHandle
    }

    override suspend fun destroySession(sessionId: String) {
        val session = sessions.remove(sessionId) ?: return
        session.loopJob?.cancel()
        session.loopJob = null
        bridge.get()?.destroySession(session.nativeHandle)
        session.nativeHandle = 0L
        logger.info(
            "runtime.destroy",
            mapOf("backend" to descriptor.id, "gameId" to session.gameId),
        )
    }

    /** Picks the right data source: plain files, or encrypted .wolf archives. */
    internal fun openDataSource(stored: WolfNativeSession): GameDataSource {
        val hasArchives = stored.gameRoot.walkTopDown()
            .any { it.isFile && it.extension.equals("wolf", true) }
        return if (hasArchives) {
            WolfArchiveGameDataSource(stored.gameRoot)
        } else {
            GameDataSource.open(stored.gameRoot)
        }
    }

    internal fun loadProjectSettings(
        root: File,
        archiveOnly: Boolean = false,
    ): GameDat = try {
        val source = if (archiveOnly) {
            WolfArchiveGameDataSource(root)
        } else {
            GameDataSource.open(root)
        }
        source.use { ds ->
            val bytes = runCatching { ds.read(GameDataSource.GAME_DAT) }
                .getOrElse { ds.read(GameDataSource.DATA_GAME_DAT) }
            GameDat.parse(bytes)
        }
    } catch (e: WolfFormatException) {
        throw IllegalArgumentException("The imported WOLF game data is invalid.", e)
    }

    private fun hasWolfArchives(root: File): Boolean =
        root.walkTopDown().any { it.isFile && it.extension.equals("wolf", true) }

    /** Rejects non-WOLF roots early so misrouted MV/MZ games fail loudly here. */
    internal fun validateWolfDeployment(root: File) {
        if (!root.isDirectory) {
            throw IllegalArgumentException("The imported game directory is missing.")
        }
        // Game.exe is optional (some distributions omit it); the data files
        // are the authoritative WOLF signature, matching the detector.
        val hasGameData = root.resolve(GAME_DATA).isFile ||
            root.resolve(DATA_BASIC_GAME_DATA).isFile
        if (!hasGameData) {
            throw IllegalArgumentException(
                "The imported game is not a WOLF RPG deployment.",
            )
        }
    }

    internal data class WolfNativeSession(
        val id: String,
        val gameId: String,
        val gameRoot: File,
        val settings: RuntimeSettings,
        val project: GameDat? = null,
        val logger: RuntimeLogger? = null,
        var nativeHandle: Long = 0L,
        /** Set while the per-session game loop is running; cancelled on destroy. */
        @Volatile var loopJob: kotlinx.coroutines.Job? = null,
    )

    fun interface WolfNativeBridgeProvider {
        fun get(): WolfNativeBridge?
    }

    companion object {
        const val BACKEND_ID = "wolf-native"
        const val GAME_EXECUTABLE = "Game.exe"
        const val GAME_DATA = "Game.dat"
        const val DATA_BASIC_GAME_DATA = "Data/BasicData/Game.dat"
        const val NATIVE_ORIGIN = "wolf-native://makerplay"
        const val NATIVE_SMOKE_URL = "$NATIVE_ORIGIN/smoke"
        const val NATIVE_SESSION_URL = "$NATIVE_ORIGIN/session"
    }
}


/** Presentation state emitted by the running event interpreter. */
class WolfUiState {
    var message: String? by mutableStateOf(null)
    var choices: List<String> by mutableStateOf(emptyList())
}
