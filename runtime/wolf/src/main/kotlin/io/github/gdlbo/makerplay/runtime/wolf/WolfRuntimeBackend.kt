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
    /** Directions held via GL-surface key events (adb / hardware). */
    private val keyDirections =
        AtomicReference<Set<WolfGameEngine.Direction>>(emptySet())
    /** Frames to keep a released direction pressed so short keyevents register. */
    private val keyDirectionHoldTtl =
        AtomicReference<Map<WolfGameEngine.Direction, Int>>(emptyMap())
    private val currentConfirm = AtomicBoolean(false)
    /** Latched confirm presses so short taps cannot be missed between loop samples. */
    private val pendingConfirmEdges = AtomicInteger(0)

    /** Latest pressed WOLF key id (0 none; 1-4 dirs, 5 decide, 6 cancel, 7 shift),
     *  published by the input LaunchedEffect for the interpreter's InputKey poll. */
    private val currentWolfKey = AtomicInteger(0)
    /** Latched key id held across short taps / paired 123 polls. */
    private val latchedWolfKey = AtomicInteger(0)
    /** Frames remaining before [latchedWolfKey] expires. */
    private val latchedWolfKeyTtl = AtomicInteger(0)

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
                runtimeProfile = io.github.gdlbo.makerplay.runtime.api.unavailableRuntimeProfile(request.settings),
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
            runtimeProfile = io.github.gdlbo.makerplay.runtime.api.unavailableRuntimeProfile(request.settings),
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

        // Map logical/virtual controller actions onto engine directions.
        LaunchedEffect(virtualInput) {
            // Virtual controller buttons dispatch key codes (e.g. Enter = 66);
            // map them through the same table as physical keyboard input.
            val pressed = virtualInput.pressedActions +
                virtualInput.pressedKeyCodes.mapNotNull { PhysicalInputNormalizer.keyMap[it] }
            val dirs = buildSet {
                if (GameAction.UP in pressed) add(WolfGameEngine.Direction.UP)
                if (GameAction.DOWN in pressed) add(WolfGameEngine.Direction.DOWN)
                if (GameAction.LEFT in pressed) add(WolfGameEngine.Direction.LEFT)
                if (GameAction.RIGHT in pressed) add(WolfGameEngine.Direction.RIGHT)
            }
            currentDirections.set(dirs)
            val confirmDown = GameAction.OK in pressed
            if (confirmDown && !currentConfirm.getAndSet(true)) {
                pendingConfirmEdges.incrementAndGet()
            } else if (!confirmDown) {
                currentConfirm.set(false)
            }
            val wolfKey = when {
                GameAction.OK in pressed -> 10 // 決定
                GameAction.CANCEL in pressed || GameAction.ESCAPE in pressed -> 11 // キャンセル
                GameAction.UP in pressed -> 8
                GameAction.DOWN in pressed -> 2
                GameAction.LEFT in pressed -> 4
                GameAction.RIGHT in pressed -> 6
                GameAction.SHIFT in pressed -> 7
                GameAction.MENU in pressed -> 17
                else -> 0
            }
            currentWolfKey.set(wolfKey)
            if (wolfKey != 0) {
                latchedWolfKey.set(wolfKey)
                latchedWolfKeyTtl.set(30)
            }
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
                runGameLoop(nativeBridge, handle, stored, uiState) {
                    pendingConfirmEdges.getAndUpdate { count -> if (count > 0) count - 1 else 0 } > 0
                }
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
                        onKeyAction = onKeyAction@{ keyCode, down ->
                            val action = PhysicalInputNormalizer.keyMap[keyCode] ?: return@onKeyAction false
                            val dir = when (action) {
                                GameAction.UP -> WolfGameEngine.Direction.UP
                                GameAction.DOWN -> WolfGameEngine.Direction.DOWN
                                GameAction.LEFT -> WolfGameEngine.Direction.LEFT
                                GameAction.RIGHT -> WolfGameEngine.Direction.RIGHT
                                else -> null
                            }
                            if (dir != null) {
                                val next = keyDirections.get().toMutableSet()
                                val hold = keyDirectionHoldTtl.get().toMutableMap()
                                if (down) {
                                    next.add(dir)
                                    hold.remove(dir)
                                } else {
                                    // Keep the direction briefly so tap keyevents
                                    // still produce movement samples.
                                    hold[dir] = 16
                                }
                                keyDirections.set(next)
                                keyDirectionHoldTtl.set(hold)
                            }
                            val wolfKey = when (action) {
                                GameAction.OK -> 10
                                GameAction.CANCEL, GameAction.ESCAPE -> 11
                                GameAction.UP -> 8
                                GameAction.DOWN -> 2
                                GameAction.LEFT -> 4
                                GameAction.RIGHT -> 6
                                GameAction.SHIFT -> 7
                                GameAction.MENU -> 17
                                else -> 0
                            }
                            if (action == GameAction.OK) {
                                if (down && !currentConfirm.getAndSet(true)) {
                                    pendingConfirmEdges.incrementAndGet()
                                } else if (!down) {
                                    currentConfirm.set(false)
                                }
                            }
                            if (down) {
                                if (wolfKey != 0) {
                                    currentWolfKey.set(wolfKey)
                                    latchedWolfKey.set(wolfKey)
                                    latchedWolfKeyTtl.set(30)
                                }
                            } else if (currentWolfKey.get() == wolfKey) {
                                currentWolfKey.set(0)
                            }
                            true
                        }
                        post { requestFocus() }
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
            // Clear host input latches left over from a prior session.
            currentDirections.set(emptySet())
            keyDirections.set(emptySet())
            keyDirectionHoldTtl.set(emptyMap())
            currentConfirm.set(false)
            pendingConfirmEdges.set(0)
            latchedWolfKey.set(0)
            latchedWolfKeyTtl.set(0)
            currentWolfKey.set(0)
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
                        if (it.title.isNotEmpty()) {
                            commonEventsByName[it.title] = it.commands
                        }
                    }
                }.onFailure {
                    logger.error("runtime.commont_events_failed", mapOf("error" to (it.message ?: "unknown")))
                }
                var map = initialMap
                val tilesets = io.github.gdlbo.makerplay.wolfformat.TileSetData.parse(
                    source.read("Data/BasicData/TileSetData.dat"),
                )
                var interpreter: WolfInterpreter? = null
                // Which trigger owns [interpreter]; parallel pages must not hide the hero.
                var activeTrigger: WolfGameEngine.Trigger? = null
                var choiceIndex = 0
                var choiceArmed = false
                // Persisted machine state: saves snapshot the live interpreter,
                // loads seed every subsequently created interpreter.
                val machineVariables = HashMap<Int, Int>()
                // Common 9M system vars used by fade squares for screen size.
                machineVariables[-1_000_116] = project.screenWidth
                machineVariables[-1_000_117] = project.screenHeight
                val machineStrings = HashMap<Int, String>()
                val startTile = titleMenuStartTile(initialMap)
                val engine = WolfGameEngine(
                    project, map, tilesets,
                    initialX = startTile?.first ?: 0,
                    initialY = startTile?.second ?: 0,
                    readVariable = { key ->
                        interpreter?.variables?.get(key) ?: machineVariables[key] ?: 0
                    },
                )
                val pictures = WolfPictureState()
                val savesRoot = File(stored.gameRoot, "MakerPlaySaves")
                val saveManager = WolfGameSaveManager(savesRoot)
                val audio = WolfAudioPlayer()
                // Load databases lazily: full user DB files are multi-megabyte
                // and must not block the first rendered frames.
                var database: WolfDatabase? = null
                fun ensureDatabase(): WolfDatabase {
                    val existing = database
                    if (existing != null) return existing
                    val loaded = runCatching { WolfDatabase.load(source) }
                        .onFailure {
                            logger.error(
                                "runtime.database_load_failed",
                                mapOf("error" to (it.message ?: "unknown")),
                            )
                        }
                        .getOrElse { WolfDatabase.empty() }
                    database = loaded
                    return loaded
                }
                fun decodeNumberRef(raw: Int): Int = when (raw) {
                    in 2_000_000..2_999_999 -> raw - 2_000_000
                    in 8_000_000..8_999_999 -> -(raw - 8_000_000) - 1
                    in 1_600_000..1_699_999 -> -1_000_000 - (raw - 1_600_000)
                    else -> raw
                }
                fun decodeStringRef(raw: Int): Int = when (raw) {
                    in 3_000_000..3_999_999 -> raw - 3_000_000
                    in 1_600_000..1_699_999 -> -1_000_000 - (raw - 1_600_000)
                    else -> raw
                }
                fun readNumberRef(raw: Int): Int {
                    val active = interpreter
                    val key = decodeNumberRef(raw)
                    return active?.variables?.get(key) ?: machineVariables[key] ?: 0
                }
                fun writeNumberRef(raw: Int, value: Int) {
                    val key = decodeNumberRef(raw)
                    interpreter?.variables?.set(key, value)
                    machineVariables[key] = value
                }
                fun readStringRef(raw: Int): String {
                    val key = decodeStringRef(raw)
                    return interpreter?.strings?.get(key) ?: machineStrings[key] ?: ""
                }
                fun writeStringRef(raw: Int, value: String) {
                    val key = decodeStringRef(raw)
                    interpreter?.strings?.set(key, value)
                    machineStrings[key] = value
                }
                var forceCompose = true
                val hostCallbacks = object : WolfInterpreter.Host {
                    override fun onMessage(text: String) {
                        // Drop confirms that opened this window so it is not
                        // dismissed on the same press.
                        pendingConfirmEdges.set(0)
                        latchedWolfKey.set(0)
                        latchedWolfKeyTtl.set(0)
                        ui.message = text
                    }
                    override fun onChoices(options: List<String>) {
                        choiceIndex = 0
                        choiceArmed = false
                        pendingConfirmEdges.set(0)
                        latchedWolfKey.set(0)
                        latchedWolfKeyTtl.set(0)
                        ui.choices = options
                    }
                    override fun expandText(text: String): String {
                        val active = interpreter
                        return WolfText.interpolate(
                            text,
                            variables = active?.variables ?: machineVariables,
                            strings = active?.strings ?: machineStrings,
                            database = database,
                        )
                    }
                    override fun onKeyPoll(): Int {
                        // Keep the latch across non-waiting polls; waiting
                        // InputKey clears it via onKeyConsumed once accepted.
                        val latched = latchedWolfKey.get()
                        return if (latched != 0) latched else currentWolfKey.get()
                    }
                    override fun onKeyConsumed() {
                        latchedWolfKey.set(0)
                        latchedWolfKeyTtl.set(0)
                    }
                    override fun onDatabase(command: EventCommand) {
                        runCatching {
                            ensureDatabase().execute(
                                command,
                                readNumber = ::readNumberRef,
                                writeNumber = ::writeNumberRef,
                                readString = ::readStringRef,
                                writeString = ::writeStringRef,
                            )
                        }.onFailure {
                            android.util.Log.e("WolfRuntime", "database op failed code=${command.commandType}", it)
                        }
                    }
                    override fun onTeleport(mapId: Int, tileX: Int, tileY: Int) {
                        // WOLF applies a move-place immediately: the map and
                        // hero change at the teleport point, and the calling
                        // event continues on the new map (fade/inits run after).
                        val target = resolveMapPath(source, mapId)
                        val bytes = target?.let { runCatching { source.read(it) }.getOrNull() }
                        android.util.Log.i(
                            "WolfRuntime",
                            "teleport mapId=$mapId pos=$tileX,$tileY path=$target ok=${bytes != null}",
                        )
                        if (target != null && bytes != null) {
                            val nextMap = MapFile.parse(bytes)
                            map = nextMap
                            mapPath = target
                            pictures.clear()
                            forceCompose = true
                            engine.replaceMap(nextMap, tileX, tileY)
                        } else {
                            // Missing destination (common in partial deployments):
                            // drop overlays so the current map stays playable.
                            pictures.clear()
                            forceCompose = true
                            engine.queueTransfer(mapId, tileX, tileY)
                        }
                    }
                    override fun onSaveLoad(): Boolean {
                        // 220 SaveLoad opens the save/load selection; the
                        // consuming script then performs 221/222 itself, so no
                        // host action is required here.
                        return true
                    }

                    override fun onFileExists(name: String): Boolean {
                        val cleaned = name.removePrefix("/").replace("\\", "/")
                        if (cleaned.isEmpty()) return false
                        // Save scripts probe Data/Save/<name> for slots; also
                        // check the host saves root for the same-relative file.
                        val saveRel = cleaned.removePrefix("Save/").removePrefix("Data/")
                        val dataPath = "Data/Save/$saveRel"
                        if (runCatching { source.has(dataPath) }.getOrDefault(false)) return true
                        val root = stored.gameRoot
                        val dirs = listOf(
                            File(root, "Data/Save"),
                            File(root, "Save"),
                            File(root, "MakerPlaySaves"),
                        )
                        return dirs.any { File(it, saveRel).isFile }
                    }

                    override fun onSave(slot: Int): Boolean {
                        return runCatching {
                            interpreter?.let {
                                machineVariables.clear(); machineVariables.putAll(it.variables)
                                machineStrings.clear(); machineStrings.putAll(it.strings)
                            }
                            val ok = saveManager.save(
                                slotSaveName(slot),
                                WolfSaveFormat.GameState(
                                    title = project.title,
                                    mapPath = mapPath,
                                    tileX = engine.position().tileX,
                                    tileY = engine.position().tileY,
                                    variables = HashMap(machineVariables),
                                    strings = HashMap(machineStrings),
                                ),
                            )
                            // Mirror an existence marker into the game's Data/Save
                            // so its own load screen probes pass (AUTO{n}/Manual{n}).
                            runCatching {
                                val saveDir = File(stored.gameRoot, "Data/Save")
                                saveDir.mkdirs()
                                val name = when (slot) {
                                    0 -> "System.sav"
                                    else -> "AUTO$slot.sav"
                                }
                                File(saveDir, name).writeBytes(byteArrayOf(0))
                            }
                            ok
                        }.isSuccess
                    }

                    override fun onLoad(slot: Int): Boolean {
                        val name = slotSaveName(slot)
                        if (!saveManager.has(name)) return false
                        return runCatching {
                            val state = saveManager.load(name)
                            android.util.Log.i("WolfRuntime", "load slot=$slot path=${state.mapPath}")
                            machineVariables.clear(); machineVariables.putAll(state.variables)
                            machineStrings.clear(); machineStrings.putAll(state.strings)
                            // Restore the observed hero position onto the engine.
                            if (source.has(state.mapPath)) {
                                map = MapFile.parse(source.read(state.mapPath))
                                mapPath = state.mapPath
                                engine.replaceMap(map, state.tileX, state.tileY)
                            }
                            forceCompose = true
                            true
                        }.onFailure {
                            android.util.Log.w("WolfRuntime", "load slot=$slot failed: ${it.message}")
                        }.getOrDefault(false)
                    }

                    private fun slotSaveName(slot: Int): String =
                        if (slot == 0) "slot-0" else "slot-$slot"

                    override fun onPicture(command: EventCommand) {
                        // File-from-string-var forms ship an empty strings[] and
                        // point at a CSelf/string ref in params (title_back etc).
                        var resolved = command
                        if (command.strings.all { it.isBlank() }) {
                            // CSelf string slots (1.6M) or low string-vars (3.0M);
                            // skip params[0] so packed options like 3149840 are
                            // not treated as string ids.
                            val strRef = command.params.drop(1).firstOrNull {
                                it in 1_600_000..1_699_999 || it in 3_000_000..3_000_999
                            }
                            if (strRef != null) {
                                val path = readStringRef(strRef).ifBlank {
                                    interpreter?.strings?.get(strRef)
                                        ?: machineStrings[strRef]
                                        ?: ""
                                }
                                if (path.isNotBlank()) {
                                    resolved = command.copy(strings = listOf(path))
                                }
                            }
                        }
                        val expanded = if (resolved.strings.any { it.contains('\\') }) {
                            resolved.copy(strings = resolved.strings.map { expandText(it) })
                        } else {
                            resolved
                        }
                        pictures.apply(expanded)
                    }

                    override fun onScreenEffect(command: EventCommand) {
                        // Color/transition commands adjust the framebuffer; they
                        // must not wipe picture slots (title menus keep art
                        // through tone changes and fade setup).
                        forceCompose = true
                    }

                    override fun onMove(command: EventCommand) {
                        val target = command.params.firstOrNull() ?: return
                        val route = command.route ?: return
                        // -1/-2 = hero; other ids are map events (not yet moved).
                        if (target != -1 && target != -2) return
                        val wait = (route.routeOptionsRaw and 0b00000100) != 0
                        val skip = (route.routeOptionsRaw and 0b00000010) != 0
                        engine.queueHeroRoute(route.steps, waitUntilDone = wait, skipImpossible = skip)
                        forceCompose = true
                    }

                    override fun onMoveFinished(): Boolean = engine.routesIdle()

                    override fun onMapEffect(command: EventCommand) {
                        // 280 MapShake: low nibble is power, next nibble speed.
                        val options = command.params.getOrNull(0) ?: return
                        val duration = command.params.getOrNull(1) ?: 0
                        engine.startShake(
                            power = (options and 0x0F),
                            durationFrames = duration,
                        )
                        forceCompose = true
                    }

                    override fun onScroll(command: EventCommand) {
                        // 281: [options, x, y]
                        val options = command.params.getOrNull(0) ?: return
                        val x = command.params.getOrNull(1) ?: 0
                        val y = command.params.getOrNull(2) ?: 0
                        val op = options and 0x0F
                        val pixelUnits = ((options ushr 8) and 0b10) != 0
                        val ts = project.tileSize
                        when (op) {
                            0 -> { // MoveScreen; speed controls animation rate, not distance.
                                val dx = if (pixelUnits) x else x * ts
                                val dy = if (pixelUnits) y else y * ts
                                engine.scrollBy(dx, dy)
                            }
                            1 -> engine.unlockScroll() // BackToHero
                            2 -> engine.setScrollLock(true) // LockScroll
                            3 -> engine.unlockScroll() // UnlockScroll
                        }
                        forceCompose = true
                    }

                    override fun onEffect(command: EventCommand) {
                        val options = command.params.getOrNull(0) ?: return
                        val targetKind = options and 0x0F
                        val effectType = (options ushr 4) and 0x0F
                        when {
                            targetKind == 0 -> pictures.applyEffect(command)
                            // Character/map shake has the same visible result
                            // at this renderer boundary when it targets the hero.
                            targetKind == 2 && effectType == 1 -> engine.startShake(
                                power = kotlin.math.abs(command.params.getOrNull(4) ?: 0),
                                durationFrames = command.params.getOrNull(1) ?: 0,
                            )
                            targetKind == 1 && effectType == 1 &&
                                (command.params.getOrNull(2) == -1 || command.params.getOrNull(2) == -2) ->
                                engine.startShake(
                                    power = kotlin.math.abs(command.params.getOrNull(4) ?: 0),
                                    durationFrames = command.params.getOrNull(1) ?: 0,
                                )
                        }
                        forceCompose = true
                    }

                    override fun onSound(command: EventCommand) {
                        // Sound command layout varies by editor revision; the
                        // first string, when present, names the media file.
                        val raw = command.strings.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
                        android.util.Log.d("WolfRuntime", "sound raw=$raw")
                        val mediaName = if (raw.contains('\\')) expandText(raw) else raw
                        if (mediaName.contains('\\')) return
                        val name = mediaName.substringAfterLast('/').removePrefix("/")
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
                var lastFrame: WolfSceneLoader.StaticFrame? = null
                while (isActive) {
                    val active = interpreter
                    if (active != null && !active.finished) {
                        when (val blocking = active.currentBlocking()) {
                            is WolfInterpreter.Blocking.Message -> {
                                if (confirmEdges() || latchedWolfKey.get() != 0) {
                                    latchedWolfKey.set(0)
                                    latchedWolfKeyTtl.set(0)
                                    active.advance()
                                }
                            }
                            is WolfInterpreter.Blocking.Choices -> {
                                // Consume a stale confirm latch from the prior
                                // menu so New Game does not auto-pick option 0.
                                val key = latchedWolfKey.get()
                                when (key) {
                                    8 -> { // up
                                        latchedWolfKey.set(0)
                                        latchedWolfKeyTtl.set(0)
                                        choiceIndex = (choiceIndex - 1).coerceAtLeast(0)
                                        forceCompose = true
                                    }
                                    2 -> { // down
                                        latchedWolfKey.set(0)
                                        latchedWolfKeyTtl.set(0)
                                        choiceIndex =
                                            (choiceIndex + 1).coerceAtMost(blocking.options.size - 1)
                                        forceCompose = true
                                    }
                                    10 -> { // confirm
                                        latchedWolfKey.set(0)
                                        latchedWolfKeyTtl.set(0)
                                        if (choiceArmed) {
                                            active.choose(choiceIndex)
                                            choiceIndex = 0
                                            choiceArmed = false
                                        } else {
                                            choiceArmed = true
                                        }
                                    }
                                    else -> {
                                        if (key != 0) {
                                            latchedWolfKey.set(0)
                                            latchedWolfKeyTtl.set(0)
                                        }
                                        if (confirmEdges() && choiceArmed) {
                                            active.choose(choiceIndex)
                                            choiceIndex = 0
                                            choiceArmed = false
                                        } else if (confirmEdges()) {
                                            choiceArmed = true
                                        }
                                    }
                                }
                            }
                            else -> Unit
                        }
                        active.tick()
                        engine.advanceRouteAndEffects()
                        if (active.finished && active.currentBlocking() == null) {
                            machineVariables.clear(); machineVariables.putAll(active.variables)
                            machineStrings.clear(); machineStrings.putAll(active.strings)
                            interpreter = null
                            activeTrigger = null
                        }
                    } else {
                        if (latchedWolfKeyTtl.decrementAndGet() <= 0) {
                            latchedWolfKeyTtl.set(0)
                            latchedWolfKey.set(0)
                        }
                        engine.pendingTransfer?.let { (mapId, pos) ->
                            val target = resolveMapPath(source, mapId)
                            if (target == null) {
                                engine.clearPendingTransfer()
                                pictures.clear()
                                forceCompose = true
                                return@let
                            }
                            val bytes = runCatching { source.read(target) }.getOrNull()
                            if (bytes != null) {
                                val nextMap = MapFile.parse(bytes)
                                map = nextMap
                                mapPath = target
                                pictures.clear()
                                forceCompose = true
                                engine.replaceMap(nextMap, pos.first, pos.second)
                            } else {
                                engine.clearPendingTransfer()
                            }
                        }
                        val heldDirs = keyDirections.get().toMutableSet()
                        val hold = keyDirectionHoldTtl.get().toMutableMap()
                        if (hold.isNotEmpty()) {
                            val iter = hold.entries.iterator()
                            while (iter.hasNext()) {
                                val (dir, ttl) = iter.next()
                                if (ttl <= 1) {
                                    iter.remove()
                                    heldDirs.remove(dir)
                                } else {
                                    hold[dir] = ttl - 1
                                    heldDirs.add(dir)
                                }
                            }
                            keyDirections.set(heldDirs)
                            keyDirectionHoldTtl.set(hold)
                        }
                        val confirmForMap = currentConfirm.get() || pendingConfirmEdges.get() > 0
                        engine.setInput(
                            currentDirections.get() + heldDirs,
                            confirmForMap,
                        )
                        engine.tick()
                        if (confirmForMap && pendingConfirmEdges.get() > 0) {
                            // Consume one latched edge used for map confirm.
                            pendingConfirmEdges.updateAndGet { n -> if (n > 0) n - 1 else 0 }
                        }
                        val fired = engine.drainFiredTriggers()
                        for (trigger in fired) {
                            if (interpreter != null && !interpreter!!.finished) break
                            if (trigger.trigger == WolfGameEngine.Trigger.AUTORUN) {
                                engine.markAutorunStarted(trigger.eventId)
                            }
                            val runner = WolfInterpreter(
                                hostCallbacks, commonEvents, commonEventsByName,
                                initialVariables = machineVariables,
                                initialStrings = machineStrings,
                            )
                            runner.start(trigger.page.commands)
                            interpreter = runner
                            activeTrigger = trigger.trigger
                        }
                    }
                    // Recompose only when something visible changed: hero moved
                    // or an event switched the presented state.
                    // Hide the hero during autorun/action scripts, not parallel pages.
                    val heroPos = if (
                        interpreter != null &&
                        !interpreter!!.finished &&
                        activeTrigger != WolfGameEngine.Trigger.PARALLEL
                    ) {
                        null
                    } else {
                        engine.position()
                    }
                    val msgText = (interpreter?.currentBlocking() as? WolfInterpreter.Blocking.Message)?.text
                    val choiceOpts = (interpreter?.currentBlocking() as? WolfInterpreter.Blocking.Choices)?.options
                        ?: emptyList()
                    // Recompose while routes/shakes animate even if hero tile is unchanged.
                    if (!engine.routesIdle()) forceCompose = true
                    val camKey = engine.cameraOffset()
                    val key = Triple(mapPath, heroPos?.tileX to heroPos?.tileY, heroPos?.offsetX) to
                        pictures.version() to (msgText to (choiceOpts to choiceIndex)) to camKey
                    val frame = if (key != lastFrameKey || forceCompose) {
                        lastFrameKey = key
                        runCatching {
                            withContext(Dispatchers.Default) {
                                val cam = engine.cameraOffset()
                                WolfSceneLoader.composeFrame(
                                    source, project, map, tilesets, heroTile = heroPos,
                                    pictures = pictures.all(),
                                    messageText = msgText,
                                    choiceOptions = choiceOpts,
                                    selectedChoice = choiceIndex,
                                    cameraExtraX = if (engine.isScrollLocked()) 0 else cam.first,
                                    cameraExtraY = if (engine.isScrollLocked()) 0 else cam.second,
                                    lockedCamX = if (engine.isScrollLocked()) cam.first else null,
                                    lockedCamY = if (engine.isScrollLocked()) cam.second else null,
                                )
                            }
                        }.onFailure {
                            android.util.Log.e("WolfRuntime", "compose failed: ${it.message}", it)
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
                        forceCompose = false
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

    /**
     * Title maps often park the cursor on a dense row of confirm-key options.
     * Start there so New Game is reachable without a long walk from (0,0).
     */
    private fun titleMenuStartTile(map: MapFile): Pair<Int, Int>? {
        val options = map.events.filter { event ->
            event.pages.any { it.triggerCondition == 0 && it.commands.isNotEmpty() }
        }
        if (options.size < 3) return null
        val densestRow = options.groupBy { it.y }.maxByOrNull { it.value.size } ?: return null
        if (densestRow.value.size < 3) return null
        val leftmost = densestRow.value.minByOrNull { it.x } ?: return null
        return leftmost.x to leftmost.y
    }

    /** Resolves a map id to a Data/MapData path across common filename shapes. */
    private fun resolveMapPath(source: GameDataSource, mapId: Int): String? {
        val candidates = listOf(
            "Data/MapData/Map%03d.mps".format(mapId),
            "Data/MapData/Map%04d.mps".format(mapId),
            "Data/MapData/Map%d.mps".format(mapId),
        )
        candidates.firstOrNull { runCatching { source.has(it) }.getOrDefault(false) }?.let { return it }
        // Only accept Map<number>.mps — loose digit matching wrongly binds
        // map id 1 to names like SampleMapA_1.mps.
        val mapName = Regex("""^Map0*(\d+)\.mps$""", RegexOption.IGNORE_CASE)
        return source.list("Data/MapData")
            .filter { it.endsWith(".mps", true) }
            .firstOrNull { name ->
                val n = mapName.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                n == mapId
            }?.let { "Data/MapData/$it" }
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
        val mapNames = source.list("Data/MapData")
            .filter { it.endsWith(".mps", true) }
        // Only the conventional boot alias is forced. "TitleMap.mps" can be a
        // field-style menu map that is not the real opening script.
        val candidates = mapNames
            .filter { it.equals("000title.mps", true) }
            .sorted()
            .plus(mapNames.filterNot { it.equals("000title.mps", true) }.sorted())
        val errors = mutableListOf<String>()
        var fallback: Pair<String, MapFile>? = null
        var bestAutorun: Pair<String, MapFile>? = null
        var bestAutorunCommands = -1
        for (name in candidates) {
            val path = "Data/MapData/$name"
            try {
                val parsed = MapFile.parse(source.read(path))
                if (name.equals("000title.mps", true)) {
                    return path to parsed
                }
                // Prefer early non-stub autorun maps. Alphabetical order alone
                // picks redirect stubs; global max-command picks mid-game maps.
                val autoPages = parsed.events.flatMap { it.pages }.filter { it.triggerCondition == 1 }
                if (autoPages.isEmpty()) {
                    if (fallback == null) fallback = path to parsed
                    continue
                }
                val autoCommands = autoPages.maxOf { it.commands.size }
                val mapNum = Regex("""^Map0*(\d+)\.mps$""", RegexOption.IGNORE_CASE)
                    .find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val callsTitleCe = autoPages.any { page ->
                    page.commands.any { it.commandType == 300 || it.commandType == 210 }
                }
                val stub = autoPages.all { page ->
                    val meaningful = page.commands.count { it.commandType != 0 && it.commandType != 103 }
                    meaningful <= 3 && page.commands.any { it.commandType == 300 }
                }
                // Map001-style boots are often only "call title CE + erase".
                if (stub && mapNum == 1 && callsTitleCe) {
                    return path to parsed
                }
                if (stub) {
                    if (fallback == null) fallback = path to parsed
                    continue
                }
                // Compact "title" maps that only autorun a common-event menu
                // (few events, moderate script) beat early numbered field maps.
                // Large multi-event title maps are field menus — skip the boost.
                val titleScript = name.contains("title", ignoreCase = true) &&
                    parsed.events.size <= 3 &&
                    autoCommands in 10..200 &&
                    callsTitleCe
                if (titleScript) {
                    return path to parsed
                }
                // Prefer the earliest small-id opening map with real content over
                // later maps that merely have longer autorun scripts.
                val earlyOpening = mapNum != null && mapNum in 1..15 && autoCommands >= 10
                if (earlyOpening) {
                    val score = 10_000 - mapNum!! + autoCommands
                    if (score > bestAutorunCommands) {
                        bestAutorunCommands = score
                        bestAutorun = path to parsed
                    }
                } else if (bestAutorun == null && autoCommands >= 10) {
                    bestAutorunCommands = autoCommands
                    bestAutorun = path to parsed
                }
                if (fallback == null) fallback = path to parsed
            } catch (e: WolfFormatException) {
                errors += "$name: ${e.message?.take(60)}"
            }
        }
        return bestAutorun ?: fallback ?: throw WolfFormatException(
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
