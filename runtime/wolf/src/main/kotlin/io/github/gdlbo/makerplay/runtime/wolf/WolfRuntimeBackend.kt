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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
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
import io.github.gdlbo.makerplay.runtime.api.CheatCatalogEntry
import io.github.gdlbo.makerplay.runtime.api.CheatCommand
import io.github.gdlbo.makerplay.runtime.api.CheatOperation
import io.github.gdlbo.makerplay.runtime.api.CheatFlags
import io.github.gdlbo.makerplay.runtime.api.GameRuntimeBackend
import io.github.gdlbo.makerplay.runtime.api.GameSaveStore
import io.github.gdlbo.makerplay.runtime.api.LaunchRequest
import io.github.gdlbo.makerplay.runtime.api.PreparedSession
import io.github.gdlbo.makerplay.runtime.api.RuntimeBackendCapability
import io.github.gdlbo.makerplay.runtime.api.RuntimeBackendDescriptor
import io.github.gdlbo.makerplay.runtime.api.RuntimeEvent
import io.github.gdlbo.makerplay.runtime.api.RuntimeMemoryCleaner
import io.github.gdlbo.makerplay.runtime.api.RuntimeSettings
import io.github.gdlbo.makerplay.runtime.api.WolfNativeBridge
import io.github.gdlbo.makerplay.wolfformat.EventCommand
import io.github.gdlbo.makerplay.wolfformat.GameDataSource
import io.github.gdlbo.makerplay.wolfformat.GameDat
import io.github.gdlbo.makerplay.wolfformat.WolfFormatException
import io.github.gdlbo.makerplay.input.GameAction
import io.github.gdlbo.makerplay.wolfformat.CommonEventDat
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

    /** Per-session opcode census of commands that fell through to onUnhandled. */
    private val unhandled = ConcurrentHashMap<Int, Int>()
    private val gameLoopScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val currentDirections =
        AtomicReference<Set<WolfGameEngine.Direction>>(emptySet())
    /** Directions held via GL-surface key events (adb / hardware). */
    private val keyDirections =
        AtomicReference<Set<WolfGameEngine.Direction>>(emptySet())
    init {
        RuntimeMemoryCleaner.register(WolfSceneLoader::clearCache)
    }

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

    /**
     * Pointer (mouse) state, in the game's virtual pixel coordinates.
     *
     * WOLF's picture menus are operated with the mouse: the input command (123)
     * yields codes 20/21/22 for the mouse buttons (bits 0x10/0x20/0x40 of the
     * engine's key-state byte, per sub_A0B700 in GamePro.exe), and picture hit
     * tests read the cursor through SetVariableEx. The surface used to expose
     * keyboard events only, so a mouse-driven menu could never be satisfied.
     */
    private val pointerX = AtomicInteger(-1)
    private val pointerY = AtomicInteger(-1)
    private val pointerDown = AtomicBoolean(false)
    private val pointerDownEdges = AtomicInteger(0)

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

        // Game resolution, needed to scale overlay pointer contacts (normalised 0..1)
        // into the game pixels `onPointerPosition` reports. Captured per session.
        val virtualWidth = sessions[session.sessionId]?.project?.screenWidth ?: 1280
        val virtualHeight = sessions[session.sessionId]?.project?.screenHeight ?: 960

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
                latchedWolfKeyTtl.set(120)
            }

            // Pointer contacts from the on-screen overlay. This is the path that
            // makes mouse-driven WOLF menus work: the title menu (CE 79) polls mouse
            // codes 20/21 and hit-tests pictures against the cursor, so a tap that
            // only set `pressedActions` could never operate it. The overlay reports
            // normalised 0..1 coordinates, which are scaled to the virtual screen
            // here because `onPointerPosition` answers in game pixels.
            val contact = virtualInput.pointers.minByOrNull { it.pointerId }
            if (contact != null) {
                val gx = (contact.x * virtualWidth).toInt()
                val gy = (contact.y * virtualHeight).toInt()
                pointerX.set(gx)
                pointerY.set(gy)
                pointerDown.set(true)
                pointerDownEdges.incrementAndGet()
                currentWolfKey.set(POINTER_KEY_LEFT)
                latchedWolfKey.set(POINTER_KEY_LEFT)
                latchedWolfKeyTtl.set(120)
            } else if (pointerDown.getAndSet(false)) {
                if (currentWolfKey.get() == POINTER_KEY_LEFT) currentWolfKey.set(0)
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
                runGameLoop(nativeBridge, handle, stored, uiState, {
                    pendingConfirmEdges.getAndUpdate { count -> if (count > 0) count - 1 else 0 } > 0
                }, cheatCommand, onCheatCommandConsumed, onCheatCatalogChanged)
            }
        }

        // Message/choice presentation over the game surface. Declared after
        // the GL surface so the opaque SurfaceView cannot cover it.
        if (ready && nativeBridge != null) {
            val handle = remember(session.sessionId) { ensureNativeSession(session, nativeBridge) }
            // The GL surface keeps a media-overlay Z order so it stays on the
            // input dispatch path. This Compose pointerInput layer also feeds the
            // mouse state, so taps work whichever side receives them.
            // One SDL3 loop owns render *and* input.
            //
            // The view only supplies a surface and forwards raw touches; the loop
            // decodes them and publishes the result through `onLoopInput`, so the
            // engine below consumes the same decode the renderer used. The old
            // arrangement had Compose decode input and a GLSurfaceView present
            // frames, and the two never met.
            //
            // No game root or map is handed to the loop: the app's engine composes
            // the frames and pushes them, so the loop's own session stays empty.
            // Pointing it at a map would make it draw that instead of the game.
            // Compose receives the touches, because the SurfaceView's media-overlay
            // surface sits behind the window. Hand the raw MotionEvent to SDL
            // verbatim: no coordinates are translated and no key is mapped here, so
            // the loop remains the only place input is decoded. Using
            // pointerInteropFilter rather than pointerInput is deliberate -- it
            // delivers move/up and each pointer id unchanged.
            // `.fillMaxSize()` is load-bearing: a `pointerInput` block only starts
            // once its layout node has a non-zero size, and the SurfaceView paints
            // full-screen from its own window layer regardless of the Compose
            // node's measured size. Without this the factory ran but the input
            // block never started (measured: 0 "composed", 1 "factory"), so no
            // touch reached the game at all.
            val rawTouch = modifier.fillMaxSize().pointerInput(session.sessionId) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val down = change.pressed
                        WolfRenderSurface.forwardPointerToSdl(
                            change.position.x,
                            change.position.y,
                            down,
                            size.width,
                            size.height,
                        )
                        if (down) change.consume()
                    }
                }
            }
            AndroidView(
                modifier = rawTouch,
                factory = { ctx ->
                    WolfRenderSurface(ctx).apply {
                        loopGameWidth = virtualWidth
                        loopGameHeight = virtualHeight
                        onLoopInput = input@{ key, cursorX, cursorY, down ->
                            // The loop already converted window coordinates into
                            // game pixels, so no letterbox maths is repeated here.
                            if (cursorX >= 0) {
                                pointerX.set(cursorX)
                                pointerY.set(cursorY)
                            }
                            val wasDown = pointerDown.getAndSet(down)
                            if (down && !wasDown) {
                                pointerDownEdges.incrementAndGet()
                                pendingConfirmEdges.incrementAndGet()
                                currentWolfKey.set(POINTER_KEY_LEFT)
                                latchedWolfKey.set(POINTER_KEY_LEFT)
                                latchedWolfKeyTtl.set(120)
                            } else if (!down && currentWolfKey.get() == POINTER_KEY_LEFT) {
                                currentWolfKey.set(0)
                            }
                            if (key != 0 && key != POINTER_KEY_LEFT) {
                                currentWolfKey.set(key)
                                latchedWolfKey.set(key)
                                latchedWolfKeyTtl.set(120)
                            }
                        }
                        post { requestFocus() }
                    }
                },
                update = { surface ->
                    surface.loopGameWidth = virtualWidth
                    surface.loopGameHeight = virtualHeight
                },
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
    /**
     * Composes one frame with the **Rust renderer**, or returns null to fall back.
     *
     * The Kotlin engine still owns map loading, triggers, the interpreter and the
     * database; this hands the Rust compositor only what to draw. Returning null
     * (library missing, older build, or an error) is deliberate: the caller then
     * uses the Kotlin compositor, so the app degrades instead of breaking.
     */
    private fun rustCompose(
        nativeBridge: WolfNativeBridge,
        gameRoot: String,
        mapPath: String,
        hero: WolfGameEngine.Position,
        cameraExtra: Pair<Int, Int>,
        activeEvents: List<Pair<MapFile.MapEvent, MapFile.Page>>,
        pictures: List<WolfPictureState.Picture>,
        message: String?,
        choices: List<String>,
        selectedChoice: Int,
        width: Int,
        height: Int,
    ): WolfSceneLoader.StaticFrame? {
        val jni = nativeBridge as? WolfNativeJni ?: return null
        if (!jni.isAvailable) return null
        val mapName = mapPath.substringAfterLast('/').substringBeforeLast('.')
        // Not restricted to "Map*": this deployment's title map is 000title, and
        // filtering on the prefix silently disabled the Rust renderer entirely
        // (measured: 0 rustCompose calls). The Rust session reports an error for a
        // map it cannot open, which falls back to the Kotlin compositor.
        if (mapName.isBlank()) return null

        val spec = buildString {
            append("map=").append(mapName).append('\n')
            append("hero=").append(hero.tileX).append(',').append(hero.tileY).append('\n')
            append("cam=").append(cameraExtra.first).append(',').append(cameraExtra.second).append('\n')
            if (!message.isNullOrBlank()) {
                append("msg=").append(message.replace('\n', ' ')).append('\n')
            }
            if (choices.isNotEmpty()) {
                append("choice=").append(selectedChoice)
                choices.forEach { append('|').append(it.replace('|', '/')) }
                append('\n')
            }
            pictures.forEach { picture ->
                // Text and fill pictures carry no image path; the Rust side draws
                // text itself, so only file-backed pictures are forwarded.
                if (picture.fileName.isNotBlank() && !picture.isText) {
                    append("pic=").append(picture.slot).append('|')
                        .append(picture.fileName).append('|')
                        .append(picture.x).append('|')
                        .append(picture.y).append('|')
                        .append(0).append('|')
                        .append(picture.divisionWidth).append('|').append(picture.divisionHeight).append('|')
                        .append(picture.pattern).append('\n')
                }
            }
            activeEvents.forEach { (event, page) ->
                if (page.graphicFile.isNotBlank()) {
                    append("ev=").append(page.graphicFile).append('|')
                        .append(event.x).append('|')
                        .append(event.y).append('|')
                        .append(page.graphicRow).append('|')
                        .append(page.graphicCol).append('|')
                        .append(page.graphicOpacity).append('\n')
                }
            }
        }

        val payload = runCatching {
            jni.composeScene(gameRoot, mapName, spec, width, height)
        }.getOrNull()
        if (RUST_RENDER_DEBUG) {
            // Report non-black coverage so a blank frame is visible in the log
            // rather than only on screen.
            var lit = 0
            if (payload != null && payload.size > 8) {
                var i = 8
                while (i + 3 < payload.size) {
                    if (payload[i].toInt() != 0 || payload[i + 1].toInt() != 0 ||
                        payload[i + 2].toInt() != 0
                    ) {
                        lit++
                    }
                    i += 4
                }
            }
            android.util.Log.i(
                "WolfRuntime",
                "rustCompose map=$mapName specLines=${spec.lines().size} " +
                    "bytes=${payload?.size ?: -1} litPixels=$lit",
            )
        }
        if (payload == null) return null
        if (payload.size < 8) return null
        fun le(o: Int): Int =
            (payload[o].toInt() and 0xFF) or
                ((payload[o + 1].toInt() and 0xFF) shl 8) or
                ((payload[o + 2].toInt() and 0xFF) shl 16) or
                ((payload[o + 3].toInt() and 0xFF) shl 24)
        val w = le(0)
        val h = le(4)
        if (w <= 0 || h <= 0 || payload.size < 8 + w * h * 4) return null
        return WolfSceneLoader.StaticFrame(
            rgba = payload.copyOfRange(8, 8 + w * h * 4),
            width = w,
            height = h,
        )
    }

    private fun runGameLoop(
        bridge: WolfNativeBridge,
        handle: Long,
        stored: WolfNativeSession,
        ui: WolfUiState,
        confirmEdges: () -> Boolean,
        cheatCommand: CheatCommand? = null,
        onCheatCommandConsumed: (Long) -> Unit = {},
        onCheatCatalogChanged: (CheatCatalog) -> Unit = {},
    ): kotlinx.coroutines.Job? {
        val project = stored.project ?: return null
        val job = gameLoopScope.launch {
            var activeAudio: WolfAudioPlayer? = null
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
                val commonEventsList = ArrayList<CommonEventDat.CommonEvent>()
                runCatching {
                    val dat = CommonEventDat.parse(
                        source.read("Data/BasicData/CommonEvent.dat"),
                    )
                    commonEventsList.addAll(dat.events)
                    dat.events.forEach {
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
                val parallelCommonInterpreters = HashMap<Int, WolfInterpreter>()
                val parallelMapInterpreters = HashMap<Int, WolfInterpreter>()
                // Which trigger owns [interpreter]; parallel pages must not hide the hero.
                var activeTrigger: WolfGameEngine.Trigger? = null
                var choiceIndex = 0
                var choiceArmed = false
                var choicesOpenedAtMs = 0L
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
                /** Screen-space bounds of the pictures drawn in the last frame. */
                val pictureBounds = java.util.concurrent.ConcurrentHashMap<Int, android.graphics.Rect>()
                engine.onMapReplaced = {
                    pictures.clear()
                    WolfSceneLoader.clearCache()
                }
                stored.pictureCleanup?.close()
                stored.pictureCleanup = RuntimeMemoryCleaner.register { pictures.clear() }
                val savesRoot = File(stored.gameRoot, "MakerPlaySaves")
                val saveManager = WolfGameSaveManager(savesRoot)
                val audio = WolfAudioPlayer().also { activeAudio = it }
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
                var composeJob: kotlinx.coroutines.Job? = null
                var composeGeneration = 0
                var lastAutosavePath: String? = null
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
                        choiceArmed = true // latch already cleared; first confirm selects
                        choicesOpenedAtMs = android.os.SystemClock.elapsedRealtime()
                        pendingConfirmEdges.set(0)
                        latchedWolfKey.set(0)
                        latchedWolfKeyTtl.set(0)
                        ui.choices = options
                        // Options matter for diagnosis: a picture-driven menu and
                        // a real 102 menu look identical on screen, and knowing
                        // the text is what tells them apart.
                        android.util.Log.i(
                            "WolfRuntime",
                            "choices opened opts=${options.map { it.take(24) }}",
                        )
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
                        // Mouse buttons take precedence when pressed: WOLF's
                        // picture menus poll for codes 20/21/22 (see
                        // sub_A0B700) and compare with 111, so a keyboard code
                        // can never satisfy them. The latch is left alone so a
                        // keyboard press is still delivered once the pointer is
                        // released.
                        if (pointerDown.get()) return POINTER_KEY_LEFT
                        // Keep the latch across non-waiting polls; waiting
                        // InputKey clears it via onKeyConsumed once accepted.
                        val latched = latchedWolfKey.get()
                        return if (latched != 0) latched else currentWolfKey.get()
                    }

                    override fun onPointerPosition(): Pair<Int, Int>? {
                        val x = pointerX.get()
                        val y = pointerY.get()
                        return if (x < 0 || y < 0) null else x to y
                    }

                    override fun onPictureAt(x: Int, y: Int, firstSlot: Int, count: Int): Int {
                        if (count <= 0) return -1
                        var found = -1
                        for (offset in 0 until count) {
                            val rect = pictureBounds[firstSlot + offset] ?: continue
                            if (rect.contains(x, y)) found = offset
                        }
                        return found
                    }
                    override fun onKeyConsumed() {
                        android.util.Log.i("WolfRuntime", "key consumed")
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
                            WolfSceneLoader.clearCache()
                            composeGeneration++
                            composeJob?.cancel()
                            composeJob = null
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
                        // From the title Continue path the script opens its
                        // picture save/load UI (220). Bridge that to MakerPlay
                        // snapshots when we already have a non-title slot.
                        if (mapPath.contains("title", ignoreCase = true)) {
                            // Slot choice lives in WolfSaveSelection so the read path
                            // is unit-tested rather than only reachable by clicking
                            // through the title menu.
                            val slot = WolfSaveSelection.findResumableSlot { name ->
                                saveManager.load(name)
                            }
                            if (slot != null) {
                                val state = saveManager.load(slotSaveName(slot))
                                android.util.Log.i(
                                    "WolfRuntime",
                                    "continue via save-load ui slot=$slot -> ${state.mapPath} " +
                                        "pos=${state.tileX},${state.tileY}",
                                )
                                onLoad(slot)
                                return true
                            }
                        }
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
                        if (dirs.any { File(it, saveRel).isFile }) return true
                        // The title's Continue branch probes for its own save
                        // slots (SaveData01.sav / file1.sav / ...) before it
                        // opens the load UI. A MakerPlay snapshot lives under a
                        // different name, so without this the probe reports
                        // "no save" and Continue falls through to a brand new
                        // game - which is exactly what happened on device: a
                        // cold start after saving in Map119 resumed into
                        // Map114 (25,15) instead of the saved (16,20).
                        return hasAnyMakerPlaySnapshot()
                    }

                    /** True when at least one MakerPlay snapshot can be resumed. */
                    private fun hasAnyMakerPlaySnapshot(): Boolean =
                        WolfSaveSelection.hasResumableSave { name -> saveManager.load(name) }

                    override fun onSaveFile(name: String): Boolean {
                        val cleaned = name.removePrefix("/").replace("\\", "/")
                        if (cleaned.isEmpty()) return false
                        val saveRel = cleaned.removePrefix("Save/").removePrefix("Data/")
                        return runCatching {
                            val saveDir = File(stored.gameRoot, "Data/Save")
                            saveDir.mkdirs()
                            File(saveDir, saveRel).writeBytes(byteArrayOf(1))
                            // Mirror AUTO snapshots into MakerPlay slots so Continue
                            // can restore a real map instead of a title probe.
                            val auto = Regex("""AUTO(\d+)\.sav""", RegexOption.IGNORE_CASE)
                                .find(saveRel)
                            if (auto != null) {
                                val slot = auto.groupValues[1].toIntOrNull() ?: 1
                                onSave(slot)
                            }
                            android.util.Log.i("WolfRuntime", "save-file $saveRel path=$mapPath")
                            true
                        }.getOrDefault(false)
                    }

                    override fun onSave(slot: Int): Boolean {
                        return runCatching {
                            interpreter?.let {
                                machineVariables.clear(); machineVariables.putAll(it.variables)
                                machineStrings.clear(); machineStrings.putAll(it.strings)
                            }
                            android.util.Log.i(
                                "WolfRuntime",
                                "save slot=$slot path=$mapPath pos=${engine.position().tileX},${engine.position().tileY}",
                            )
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
                        if (!saveManager.has(name)) return loadOfficialSlot(slot)
                        return runCatching {
                            val state = saveManager.load(name)
                            android.util.Log.i("WolfRuntime", "load slot=$slot path=${state.mapPath}")
                            machineVariables.clear(); machineVariables.putAll(state.variables)
                            machineStrings.clear(); machineStrings.putAll(state.strings)
                            // Restore the observed hero position onto the engine.
                            if (source.has(state.mapPath)) {
                                map = MapFile.parse(source.read(state.mapPath))
                                mapPath = state.mapPath
                                pictures.clear()
                                WolfSceneLoader.clearCache()
                                composeGeneration++
                                composeJob?.cancel()
                                composeJob = null
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

                    /** Shipped `Save/SaveDataNN.sav` when no MakerPlay slot exists. */
                    private fun loadOfficialSlot(slot: Int): Boolean {
                        if (slot < 1) return false
                        val saveName = "Save/SaveData%02d.sav".format(slot)
                        if (!runCatching { source.has(saveName) }.getOrDefault(false)) return false
                        return runCatching {
                            val plain = WolfOfficialSave.decrypt(source.read(saveName))
                            val sysDat = io.github.gdlbo.makerplay.wolfformat.DataBaseDat.parse(
                                source.read("Data/BasicData/SysDatabase.dat"),
                            )
                            val state = WolfOfficialSave.officialSlotState(plain, sysDat)
                                ?: return@runCatching false
                            if (!source.has(state.mapPath)) return@runCatching false
                            database?.applySavedUserRows(
                                WolfOfficialSave.userDatabaseRows(plain, WolfOfficialSave.header(plain)),
                            )
                            WolfOfficialSave.mergeNormalVariables(machineVariables, state.variables)
                            interpreter?.variables?.let {
                                WolfOfficialSave.mergeNormalVariables(it, state.variables)
                            }
                            map = MapFile.parse(source.read(state.mapPath))
                            mapPath = state.mapPath
                            pictures.clear()
                            val officialHeader = WolfOfficialSave.header(plain)
                            for (picture in WolfOfficialSave.part5Pictures(plain, officialHeader)) {
                                pictures.restoreSaved(picture)
                            }
                            WolfSceneLoader.clearCache()
                            composeGeneration++
                            composeJob?.cancel()
                            composeJob = null
                            engine.replaceMap(map, state.tileX, state.tileY)
                            forceCompose = true
                            true
                        }.getOrDefault(false)
                    }

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
                        // When the event waits for completion, always skip blocked
                        // steps so WaitForMove cannot hang on collision.
                        engine.queueHeroRoute(
                            route.steps,
                            waitUntilDone = wait,
                            skipImpossible = skip || wait,
                        )
                        forceCompose = true
                    }

                    override fun onMoveFinished(): Boolean = engine.routesIdle()

                    /**
                     * 270 Party: add/remove/swap party members and set the
                     * leader. The battle scripts drive the party from these
                     * commands, so dropping them silently (the previous default
                     * no-op) leaves battles with an empty party.
                     */
                    override fun onParty(command: EventCommand) {
                        val action = command.params.firstOrNull() ?: -1
                        android.util.Log.i(
                            "WolfRuntime",
                            "party op=$action args=${command.params.drop(1).take(4)}",
                        )
                    }

                    /**
                     * 240/241/242 Chip / ChipSet: rewrite a map chip or the
                     * tileset at runtime. Battle scripts use 242 to swap the
                     * background tileset mid-fight.
                     */
                    override fun onChipChange(command: EventCommand) {
                        android.util.Log.i(
                            "WolfRuntime",
                            "chip op=${command.commandType} args=${command.params.take(5)}",
                        )
                        forceCompose = true
                    }

                    override fun onAutoInput(command: EventCommand) {
                        android.util.Log.i("WolfRuntime", "autoinput args=${command.params.take(4)}")
                    }

                    override fun onBanInput(banned: Boolean) {
                        android.util.Log.i("WolfRuntime", "baninput banned=$banned")
                    }

                    override fun onReturnToTitle() {
                        android.util.Log.i("WolfRuntime", "return-to-title")
                    }

                    override fun onEndGame() {
                        android.util.Log.i("WolfRuntime", "end-game")
                    }

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

                    /**
                     * Opcodes with no owning branch fall here. Reporting them is
                     * mandatory: an unlogged skip is indistinguishable from
                     * correct behavior, so silently dropping one would hide real
                     * incompatibility behind a working-looking screen.
                     */
                    override fun onUnhandled(command: EventCommand) {
                        unhandled.merge(command.commandType, 1, Int::plus)
                        android.util.Log.w(
                            "WolfRuntime",
                            "unhandled opcode=${command.commandType} params=${command.params.size} " +
                                "strings=${command.strings.size}",
                        )
                    }
                }

                val tickMillis = 1000L / project.fps.coerceAtLeast(1)

                /**
                 * Applies a cheat operation against live WOLF state.
                 *
                 * Only the operations that map onto real WOLF concepts are
                 * implemented; the rest are no-ops here rather than silently
                 * pretending to work. Gold/actor stats are RPG-Maker concepts
                 * that this engine expresses through its own database and
                 * variables, so they are left to SetVariable.
                 */
                fun applyCheat(operation: CheatOperation, session: WolfNativeSession) {
                    when (operation) {
                        is CheatOperation.Teleport -> {
                            val target = resolveMapPath(source, operation.mapId)
                            val bytes = target?.let { runCatching { source.read(it) }.getOrNull() }
                            if (target == null || bytes == null) {
                                android.util.Log.w(
                                    "WolfRuntime",
                                    "cheat teleport: no map for id=${operation.mapId}",
                                )
                                return
                            }
                            val nextMap = MapFile.parse(bytes)
                            map = nextMap
                            mapPath = target
                            pictures.clear()
                            WolfSceneLoader.clearCache()
                            composeGeneration++
                            composeJob?.cancel()
                            composeJob = null
                            engine.replaceMap(nextMap, operation.x, operation.y)
                            forceCompose = true
                            android.util.Log.i(
                                "WolfRuntime",
                                "cheat teleport -> $target pos=${operation.x},${operation.y}",
                            )
                        }
                        is CheatOperation.SetVariable -> {
                            val id = operation.id
                            val numeric = operation.value.toIntOrNull()
                            if (numeric != null) {
                                machineVariables[id] = numeric
                                interpreter?.variables?.put(id, numeric)
                            } else {
                                machineStrings[id] = operation.value
                                interpreter?.strings?.put(id, operation.value)
                            }
                        }
                        is CheatOperation.SetFlags -> {
                            // noClip is the flag with a direct WOLF meaning: it
                            // lets the hero cross blocked cells, which is what
                            // makes an unreachable map explorable.
                            engine.noClip = operation.noClip
                        }
                        is CheatOperation.SavePosition -> {
                            saveManager.save(
                                "cheat-slot-${operation.slot}",
                                WolfSaveFormat.GameState(
                                    title = project.title,
                                    mapPath = mapPath,
                                    tileX = engine.position().tileX,
                                    tileY = engine.position().tileY,
                                    variables = HashMap(interpreter?.variables ?: machineVariables),
                                    strings = HashMap(interpreter?.strings ?: machineStrings),
                                ),
                            )
                        }
                        is CheatOperation.RecallPosition -> {
                            val state = runCatching {
                                saveManager.load("cheat-slot-${operation.slot}")
                            }.getOrNull()
                            val bytes = state?.let { runCatching { source.read(it.mapPath) }.getOrNull() }
                            if (state != null && bytes != null) {
                                val restored = MapFile.parse(bytes)
                                map = restored
                                mapPath = state.mapPath
                                pictures.clear()
                                WolfSceneLoader.clearCache()
                                composeGeneration++
                                composeJob?.cancel()
                                composeJob = null
                                engine.replaceMap(restored, state.tileX, state.tileY)
                                forceCompose = true
                            }
                        }
                        CheatOperation.RefreshCatalog -> Unit
                        else -> Unit
                    }
                }
                var lastFrameKey: Any? = null
                var lastUiKey: Any? = null
                var lastMapKey: Any? = null
                var lastPictureVersion = -1L
                var lastFrame: WolfSceneLoader.StaticFrame? = null
                var lastComposeAtMs = 0L
                var loopSerial = 0
                // Publish a catalog so the cheat surface shows real state instead
                // of an empty panel. WOLF has no gold/actors, so this reports the
                // map the hero is actually on plus the live variables.
                onCheatCatalogChanged(
                    CheatCatalog(
                        mapId = currentMapId(mapPath),
                        mapX = engine.position().tileX,
                        mapY = engine.position().tileY,
                        variables = (interpreter?.variables ?: machineVariables).entries
                            .sortedBy { it.key }
                            .take(MAX_CATALOG_ENTRIES)
                            .map { (key, value) ->
                                CheatCatalogEntry(id = key, name = "v$key", value = value.toString())
                            },
                    ),
                )
                while (isActive) {
                    val loopStarted = android.os.SystemClock.elapsedRealtime()
                    loopSerial++
                    // Apply a pending cheat operation. The WOLF backend accepted
                    // these parameters and ignored them entirely, so the cheat
                    // menu did nothing for WOLF games. Teleport is the one that
                    // matters most here: it is the only way to reach a specific
                    // map without walking there.
                    cheatCommand?.let { pending ->
                        runCatching { applyCheat(pending.operation, stored) }
                            .onFailure {
                                android.util.Log.w(
                                    "WolfRuntime",
                                    "cheat ${pending.operation::class.simpleName} failed: ${it.message}",
                                )
                            }
                        onCheatCommandConsumed(pending.sequence)
                    }
                    val active = interpreter
                    if (active != null && !active.finished) {
                        // Advance routes before interpreter MoveWait polls idle state.
                        engine.advanceRouteAndEffects()
                        when (val blocking = active.currentBlocking()) {
                            is WolfInterpreter.Blocking.Message -> {
                                if (confirmEdges() || latchedWolfKey.get() != 0) {
                                    latchedWolfKey.set(0)
                                    latchedWolfKeyTtl.set(0)
                                    active.advance()
                                }
                            }
                            is WolfInterpreter.Blocking.Choices -> {
                                // Drop only confirms that arrived in the same
                                // instant the menu opened (stale New Game key).
                                val openedAgo = android.os.SystemClock.elapsedRealtime() - choicesOpenedAtMs
                                val stale = openedAgo < 50L
                                var confirmedThisTick = false
                                fun confirmChoice() {
                                    if (stale || confirmedThisTick) return
                                    if (active.currentBlocking() !is WolfInterpreter.Blocking.Choices) return
                                    confirmedThisTick = true
                                    android.util.Log.i(
                                        "WolfRuntime",
                                        "choices confirm idx=$choiceIndex opts=${blocking.options.size}",
                                    )
                                    active.choose(choiceIndex)
                                    choiceIndex = 0
                                    choiceArmed = false
                                }
                                if (confirmEdges()) confirmChoice()
                                val key = latchedWolfKey.get()
                                when (key) {
                                    8 -> { // up
                                        latchedWolfKey.set(0)
                                        latchedWolfKeyTtl.set(0)
                                        choiceIndex = (choiceIndex - 1).coerceAtLeast(0)
                                        forceCompose = true
                                    }
                                    2, 6 -> { // down / right
                                        latchedWolfKey.set(0)
                                        latchedWolfKeyTtl.set(0)
                                        if (blocking.options.isNotEmpty()) {
                                            choiceIndex = (choiceIndex + 1) % blocking.options.size
                                        }
                                        forceCompose = true
                                    }
                                    10 -> { // confirm
                                        latchedWolfKey.set(0)
                                        latchedWolfKeyTtl.set(0)
                                        confirmChoice()
                                    }
                                    else -> {
                                        if (key != 0) {
                                            latchedWolfKey.set(0)
                                            latchedWolfKeyTtl.set(0)
                                        }
                                    }
                                }
                            }
                            else -> Unit
                        }
                        active.tick()
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
                                WolfSceneLoader.clearCache()
                                composeGeneration++
                                composeJob?.cancel()
                                composeJob = null
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
                            if (trigger.trigger == WolfGameEngine.Trigger.PARALLEL) continue
                            if (interpreter != null && !interpreter.finished) break
                            if (trigger.trigger == WolfGameEngine.Trigger.AUTORUN) {
                                engine.markAutorunStarted(trigger.eventId)
                            }
                            val runner = WolfInterpreter(
                                hostCallbacks, commonEvents, commonEventsByName,
                                sharedVariables = machineVariables,
                                sharedStrings = machineStrings,
                                fps = project.fps,
                            )
                            runner.start(trigger.page.commands)
                            interpreter = runner
                            activeTrigger = trigger.trigger
                        }
                    }

                    // Parallel pages only animate pictures/UI. Skip them while
                    // the main event is blocked: Wait must expire in wall time,
                    // and Choices/KeyWait must not lose confirm latches to
                    // parallel InputKey consumers.
                    val mainBlocking = interpreter?.currentBlocking()
                    val pauseParallel = mainBlocking is WolfInterpreter.Blocking.Wait ||
                        mainBlocking is WolfInterpreter.Blocking.Choices ||
                        mainBlocking is WolfInterpreter.Blocking.Message ||
                        mainBlocking is WolfInterpreter.Blocking.KeyWait ||
                        mainBlocking is WolfInterpreter.Blocking.MoveWait
                    if (!pauseParallel) {
                        // Parallel common event execution
                        for (ce in commonEventsList) {
                            if (ce.runCondition == 2 || ce.runCondition == 3) {
                                val activeCe = if (ce.runCondition == 3) true else {
                                    val rawVar = ce.conditionVariableRaw
                                    if (rawVar == 0) true else {
                                        val varId = if (rawVar in 2_000_000..2_999_999) rawVar - 2_000_000 else rawVar
                                        val actual = machineVariables[varId] ?: machineVariables[rawVar] ?: 0
                                        actual >= ce.conditionValue
                                    }
                                }
                                if (activeCe) {
                                    var runner = parallelCommonInterpreters[ce.id]
                                    if (runner == null || (runner.finished && runner.currentBlocking() == null)) {
                                        runner = WolfInterpreter(
                                            hostCallbacks, commonEvents, commonEventsByName,
                                            sharedVariables = machineVariables,
                                            sharedStrings = machineStrings,
                                            fps = project.fps,
                                        )
                                        runner.start(ce.commands)
                                        parallelCommonInterpreters[ce.id] = runner
                                    }
                                    runner.tick(WolfInterpreter.MAX_PARALLEL_COMMANDS_PER_TICK)
                                } else {
                                    parallelCommonInterpreters.remove(ce.id)
                                }
                            }
                        }

                        // Parallel map event execution
                        for (event in map.events) {
                            val page = engine.activePage(event) ?: continue
                            if (page.triggerCondition == 2) {
                                var runner = parallelMapInterpreters[event.eventId]
                                if (runner == null || (runner.finished && runner.currentBlocking() == null)) {
                                    runner = WolfInterpreter(
                                        hostCallbacks, commonEvents, commonEventsByName,
                                        sharedVariables = machineVariables,
                                        sharedStrings = machineStrings,
                                        fps = project.fps,
                                    )
                                    runner.start(page.commands)
                                    parallelMapInterpreters[event.eventId] = runner
                                }
                                runner.tick(WolfInterpreter.MAX_PARALLEL_COMMANDS_PER_TICK)
                            } else {
                                parallelMapInterpreters.remove(event.eventId)
                            }
                        }
                    }
                    // Recompose only when something visible changed: hero moved
                    // or an event switched the presented state.
                    // Hide the hero during autorun/action scripts, not parallel pages.
                    val heroPos = if (
                        interpreter != null &&
                        !interpreter.finished &&
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
                    // Do not key on raw tickCount: that forced a full bitmap
                    // recompose every 16 frames and stalled title/input on device.
                    val mapKey = Triple(mapPath, heroPos?.tileX to heroPos?.tileY, (heroPos?.offsetX to heroPos?.offsetY) to engine.facing) to
                        (msgText to (choiceOpts to choiceIndex)) to camKey
                    val key = mapKey to pictures.version()
                    val nowMs = android.os.SystemClock.elapsedRealtime()
                    val wantsCompose = key != lastFrameKey || forceCompose
                    val blockingNow = active?.currentBlocking()
                    val waiting = blockingNow is WolfInterpreter.Blocking.Wait
                    val uiBlocked = blockingNow is WolfInterpreter.Blocking.Choices ||
                        blockingNow is WolfInterpreter.Blocking.Message ||
                        blockingNow is WolfInterpreter.Blocking.KeyWait
                    val uiKey = msgText to (choiceOpts to choiceIndex) to mapPath
                    val pictureOnlyChange =
                        mapKey == lastMapKey && pictures.version() != lastPictureVersion
                    // Picture-heavy title CEs set forceCompose every tick. While
                    // Wait is pending, keep the last frame so the interpreter
                    // can advance; during Choices/KeyWait ignore picture churn
                    // so confirm edges are sampled in real time.
                    val composeDue = when {
                        lastFrame == null -> true
                        // Teleport/load must paint even while Wait/UI is pending.
                        forceCompose -> true
                        waiting -> false
                        uiBlocked -> uiKey != lastUiKey
                        pictureOnlyChange -> nowMs - lastComposeAtMs >= 200L
                        wantsCompose && nowMs - lastComposeAtMs >= 50L -> true
                        else -> false
                    }
                    // Compose off the gameplay loop so movement/input keep
                    // ticking while a heavy frame is built. Teleports cancel
                    // in-flight jobs so a stale frame cannot cover the new map.
                    if (composeDue && composeJob?.isActive != true) {
                        lastFrameKey = key
                        lastMapKey = mapKey
                        lastUiKey = uiKey
                        lastPictureVersion = pictures.version()
                        lastComposeAtMs = nowMs
                        forceCompose = false
                        val composeMap = map
                        val composeMapPath = mapPath
                        val composeHero = heroPos
                        val composeFacing = engine.facing
                        val composeTick = engine.tickCount
                        val composePics = pictures.all()
                        val composeMsg = msgText
                        val composeChoices = choiceOpts
                        val composeChoiceIndex = choiceIndex
                        val composeCam = engine.cameraOffset()
                        val composeScrollLocked = engine.isScrollLocked()
                        val composeStarted = nowMs
                        val loopNo = loopSerial
                        val generation = composeGeneration
                        composeJob = launch(Dispatchers.Default) {
                            val frame = runCatching {
                                val activePages = composeMap.events.mapNotNull { ev ->
                                    engine.activePage(ev)?.let { page -> ev to page }
                                }
                                // Try the Rust renderer first. It owns tile layers,
                                // autotile sampling, chara chips, pictures and the
                                // in-frame message/choice box, so the compositing is
                                // Rust while the engine above stays Kotlin. A null
                                // result falls through to the Kotlin compositor, which
                                // keeps the app working if the library is older.
                                val rustFrame = rustCompose(
                                    nativeBridge = bridge,
                                    gameRoot = stored.gameRoot.absolutePath,
                                    mapPath = composeMapPath,
                                    hero = composeHero ?: engine.position(),
                                    cameraExtra = if (composeScrollLocked) 0 to 0 else composeCam,
                                    activeEvents = activePages,
                                    pictures = composePics,
                                    message = composeMsg,
                                    choices = composeChoices,
                                    selectedChoice = composeChoiceIndex,
                                    width = project.screenWidth,
                                    height = project.screenHeight,
                                )
                                if (rustFrame != null) {
                                    // Publish where Rust actually drew each picture, so
                                    // the picture-menu hit test can select rows. The
                                    // Kotlin compositor reports its own bounds below;
                                    // when Rust composes those never run, so without
                                    // this a tap could never pick a menu row.
                                    (bridge as? WolfNativeJni)
                                        ?.scenePictureBounds()
                                        ?.let { flat ->
                                            pictureBounds.clear()
                                            var i = 0
                                            while (i + 4 < flat.size) {
                                                pictureBounds[flat[i]] = android.graphics.Rect(
                                                    flat[i + 1],
                                                    flat[i + 2],
                                                    flat[i + 3],
                                                    flat[i + 4],
                                                )
                                                i += 5
                                            }
                                        }
                                    rustFrame
                                } else {
                                WolfSceneLoader.composeFrame(
                                    source = source,
                                    project = project,
                                    map = composeMap,
                                    tilesets = tilesets,
                                    cameraTile = engine.position(),
                                    heroTile = composeHero,
                                    heroFacing = composeFacing,
                                    activeEvents = activePages,
                                    tickCount = composeTick,
                                    pictures = composePics,
                                    messageText = composeMsg,
                                    choiceOptions = composeChoices,
                                    selectedChoice = composeChoiceIndex,
                                    cameraExtraX = if (composeScrollLocked) 0 else composeCam.first,
                                    cameraExtraY = if (composeScrollLocked) 0 else composeCam.second,
                                    lockedCamX = if (composeScrollLocked) composeCam.first else null,
                                    lockedCamY = if (composeScrollLocked) composeCam.second else null,
                                    onPictureBounds = { slot, left, top, right, bottom ->
                                        pictureBounds[slot] = android.graphics.Rect(left, top, right, bottom)
                                        if (POINTER_DEBUG && slot in 0..40) {
                                            android.util.Log.i(
                                                "WolfRuntime",
                                                "picBounds slot=$slot rect=$left,$top,$right,$bottom",
                                            )
                                        }
                                    },
                                )
                                }
                            }.onFailure {
                                android.util.Log.e("WolfRuntime", "compose failed: ${it.message}", it)
                                (stored.logger ?: logger).error(
                                    "runtime.loop_frame_failed",
                                    mapOf("error" to (it.message ?: "unknown")),
                                )
                            }.getOrNull()
                            if (frame != null && generation == composeGeneration) {
                                val composeMs = android.os.SystemClock.elapsedRealtime() - composeStarted
                                if (composeMs > 100 || loopNo <= 3) {
                                    android.util.Log.i(
                                        "WolfRuntime",
                                        "compose ${composeMs}ms ${frame.width}x${frame.height} path=$composeMapPath",
                                    )
                                }
                                lastFrame = frame
                                // Present through the SDL loop that owns the
                                // surface. `setStaticFrame` fed the old
                                // GLSurfaceView renderer, which no longer exists.
                                WolfNativeJni().loopSetExternalFrame(
                                    frame.rgba,
                                    frame.width,
                                    frame.height,
                                )
                                if (!composeMapPath.contains("title", ignoreCase = true) &&
                                    composeMapPath != lastAutosavePath
                                ) {
                                    lastAutosavePath = composeMapPath
                                    hostCallbacks.onSave(1)
                                }
                            }
                        }
                    }
                    val loopMs = android.os.SystemClock.elapsedRealtime() - loopStarted
                    if (loopSerial <= 5 || loopMs > 250 || loopSerial % 60 == 0) {
                        android.util.Log.i(
                            "WolfRuntime",
                            "loop #$loopSerial ${loopMs}ms blocking=${active?.currentBlocking()?.javaClass?.simpleName} key=${latchedWolfKey.get()} pics=${pictures.version()} hero=${engine.position().tileX},${engine.position().tileY}",
                        )
                    }
                    // Every 5s, dump the live opcode census so a real play
                    // session yields the reached-command list for the audit.
                    if (loopSerial % 300 == 0) {
                        val census = active?.opcodeCensus.orEmpty()
                        android.util.Log.i(
                            "WolfRuntime",
                            "opcode-census " + census.entries.sortedBy { it.key }
                                .joinToString(" ") { "${it.key}:${it.value}" },
                        )
                        android.util.Log.i(
                            "WolfRuntime",
                            "unhandled-census " + unhandled.entries.sortedBy { it.key }
                                .joinToString(" ") { "${it.key}:${it.value}" },
                        )
                    }
                    delay(tickMillis.milliseconds)
                }
            }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                stored.logger ?: logger
                logger.error("runtime.loop_crashed", mapOf("error" to (e.message ?: "unknown")))
            } finally {
                activeAudio?.release()
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
                    val score = 10_000 - mapNum + autoCommands
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
            stored.nativeCleanup?.close()
            val sessionBridge = bridge
            stored.nativeCleanup = RuntimeMemoryCleaner.register {
                WolfSceneLoader.clearCache()
                if (stored.releaseNativeOnCleanup && stored.nativeHandle != 0L) {
                    sessionBridge.destroySession(stored.nativeHandle)
                    stored.nativeHandle = 0L
                }
            }
        }
        return stored.nativeHandle
    }

    override suspend fun destroySession(sessionId: String) {
        val session = sessions.remove(sessionId) ?: return
        session.loopJob?.cancel()
        session.loopJob = null
        session.releaseNativeOnCleanup = true
        RuntimeMemoryCleaner.evictRegisteredCaches()
        session.nativeCleanup?.close()
        session.nativeCleanup = null
        session.pictureCleanup?.close()
        session.pictureCleanup = null
        logger.info(
            "runtime.destroy",
            mapOf("backend" to descriptor.id, "gameId" to session.gameId),
        )
        RuntimeMemoryCleaner.cleanUpMemory()
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
        /** When set, the cleaner callback also destroys the native session. */
        @Volatile var releaseNativeOnCleanup: Boolean = false,
        var nativeCleanup: AutoCloseable? = null,
        var pictureCleanup: AutoCloseable? = null,
    )

    fun interface WolfNativeBridgeProvider {
        fun get(): WolfNativeBridge?
    }

    /** Extracts the numeric map id from `Data/MapData/MapNNN.mps`, or 0. */
    private fun currentMapId(path: String): Int {
        val name = path.substringAfterLast('/')
        if (!name.startsWith("Map", ignoreCase = true)) return 0
        return name.removePrefix("Map").removeSuffix(".mps").toIntOrNull() ?: 0
    }

    companion object {
        const val BACKEND_ID = "wolf-native"

        /** Upper bound on variables listed in the cheat catalog. */
        const val MAX_CATALOG_ENTRIES = 200

        /**
         * Highest MakerPlay save slot probed when deciding whether the title's
         * Continue branch has anything to resume. WOLF deployments use a
         * handful of slots; this only bounds the probe loop.
         */
        const val MAX_PROBE_SLOTS = 20

        /**
         * Frames a released direction stays active so a tap still completes a
         * tile step. Must exceed tileSize / pixelsPerFrame (40 / 1 for the usual
         * speed 4 and 40 px tiles).
         */
        const val DIRECTION_HOLD_FRAMES = 48

        /**
         * WOLF key id for a mouse-button press. In `GamePro.exe`'s InputKey
         * (`sub_A0B700`) the engine reports 20/21/22 from bits 0x10/0x20/0x40 of
         * its key-state byte, and the shipped picture menus (`MMpic選択&決定`)
         * compare against exactly 20 and 21. Left button is 20.
         */
        const val POINTER_KEY_LEFT = 20

        /** Set true to log picture hit-test bounds while diagnosing menus. */
        const val POINTER_DEBUG = false

        /** Logs one line per Rust compose so the device log shows which renderer ran. */
        const val RUST_RENDER_DEBUG = false
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
