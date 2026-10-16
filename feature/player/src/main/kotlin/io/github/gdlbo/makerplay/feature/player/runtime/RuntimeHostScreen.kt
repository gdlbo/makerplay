package io.github.gdlbo.makerplay.feature.player.runtime

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.gdlbo.makerplay.feature.player.R
import io.github.gdlbo.makerplay.feature.player.controller.data.ControllerLayoutStore
import io.github.gdlbo.makerplay.feature.player.controller.model.ControllerLayouts
import io.github.gdlbo.makerplay.feature.player.controller.model.ControllerMode
import io.github.gdlbo.makerplay.feature.player.controller.ui.ControllerEditorPanel
import io.github.gdlbo.makerplay.feature.player.controller.ui.VirtualControllerOverlay
import io.github.gdlbo.makerplay.feature.player.controller.ui.moveVirtualControl
import io.github.gdlbo.makerplay.feature.player.controller.ui.overlayPageOffsetPx
import io.github.gdlbo.makerplay.feature.player.runtime.components.CheatOverlay
import io.github.gdlbo.makerplay.feature.player.runtime.components.PlayerToolbar
import io.github.gdlbo.makerplay.feature.player.runtime.components.RuntimeFailureScreen
import io.github.gdlbo.makerplay.feature.player.runtime.components.RuntimeFailureUi
import io.github.gdlbo.makerplay.feature.player.runtime.components.RuntimeOverlayTheme
import io.github.gdlbo.makerplay.feature.player.runtime.components.RuntimePreparing
import io.github.gdlbo.makerplay.feature.player.runtime.components.buildRuntimeFailureReport
import io.github.gdlbo.makerplay.input.GameAction
import io.github.gdlbo.makerplay.input.LogicalInputSnapshot
import io.github.gdlbo.makerplay.input.PhysicalInputNormalizer
import io.github.gdlbo.makerplay.runtime.api.CheatCatalog
import io.github.gdlbo.makerplay.runtime.api.CheatCommand
import io.github.gdlbo.makerplay.runtime.api.CheatFlags
import io.github.gdlbo.makerplay.runtime.api.CheatOperation
import io.github.gdlbo.makerplay.runtime.api.GameRuntimeBackend
import io.github.gdlbo.makerplay.runtime.api.LaunchRequest
import io.github.gdlbo.makerplay.runtime.api.PreparedSession
import io.github.gdlbo.makerplay.runtime.api.RuntimeEvent
import io.github.gdlbo.makerplay.runtime.api.RuntimeMemoryCleaner
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

@Composable
fun RuntimeHostScreen(
    backend: GameRuntimeBackend,
    request: LaunchRequest,
    onBack: () -> Unit,
    layoutFile: File? = null,
    defaultLayoutFile: File? = null,
    logFile: File? = null,
    loggingEnabled: Boolean = false,
    onToggleLogging: () -> Unit = {},
) {
    var session by remember { mutableStateOf<PreparedSession?>(null) }
    var gameReady by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<RuntimeFailureUi?>(null) }
    var failureLogsReady by remember { mutableStateOf(false) }
    var logsCopied by remember { mutableStateOf(false) }
    var stopCurrentSession by remember { mutableStateOf<(() -> Unit)?>(null) }
    var launchAttempt by remember { mutableIntStateOf(0) }
    var bootRecoveries by remember { mutableIntStateOf(0) }
    var cheatFlags by remember(request.gameId) { mutableStateOf(CheatFlags()) }
    var cheatCommand by remember(request.gameId) { mutableStateOf<CheatCommand?>(null) }
    var cheatCatalog by remember(request.gameId) { mutableStateOf(CheatCatalog()) }
    var cheatSequence by remember { mutableLongStateOf(0L) }
    var showCheats by remember(request.gameId) { mutableStateOf(false) }
    val cheatStateHolder = rememberSaveableStateHolder()
    var cheatsAvailable by remember(request.gameId) { mutableStateOf<Boolean?>(null) }
    var showControls by remember(request.gameId) { mutableStateOf(true) }
    var editControls by remember(request.gameId) { mutableStateOf(false) }
    var confirmExit by rememberSaveable(request.gameId) { mutableStateOf(false) }
    var showRestartConfirm by rememberSaveable(request.gameId) { mutableStateOf(false) }
    var layouts by remember(request.gameId) { mutableStateOf(ControllerLayouts()) }
    var layoutLoaded by remember(request.gameId) { mutableStateOf(layoutFile == null) }
    var selectedControlId by remember(request.gameId) { mutableStateOf<String?>(null) }
    var keyCapture by remember { mutableStateOf<((Int) -> Unit)?>(null) }
    var virtualInput by remember(request.gameId) {
        mutableStateOf(
            LogicalInputSnapshot(
                emptySet(),
                emptySet()
            )
        )
    }
    val layoutStore = remember(layoutFile) { layoutFile?.let(::ControllerLayoutStore) }
    val defaultStore = remember(defaultLayoutFile) { defaultLayoutFile?.let(::ControllerLayoutStore) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val savedAsDefaultMessage = stringResource(R.string.controller_saved_as_default)
    fun persistGameLayouts(value: ControllerLayouts) {
        scope.launch(Dispatchers.IO) {
            val inherited = defaultStore?.load() ?: ControllerLayouts()
            layoutStore?.saveOrClear(value, inherited)
        }
    }
    fun persistDefaultLayouts(value: ControllerLayouts) {
        scope.launch(Dispatchers.IO) {
            defaultStore?.saveOrClear(value)
        }
    }
    val clipboard = LocalClipboardManager.current
    val preparationFailure = stringResource(R.string.runtime_preparation_failed)
    val rendererCrashed = stringResource(R.string.renderer_stopped_unexpectedly)
    val rendererStopped = stringResource(R.string.renderer_stopped_by_system)
    val rendererUnresponsive = stringResource(R.string.renderer_became_unresponsive)
    val gameCouldNotStart = stringResource(R.string.game_could_not_start)
    val gameStopped = stringResource(R.string.game_stopped)
    val preparationFailureReason = stringResource(R.string.preparation_failure_reason)
    val javascriptBootReason = stringResource(R.string.javascript_boot_failure_reason)
    val rendererCrashReason = stringResource(R.string.renderer_crash_reason)
    val rendererStoppedReason = stringResource(R.string.renderer_stopped_reason)
    val rendererUnresponsiveReason = stringResource(R.string.renderer_unresponsive_reason)
    val technicalDetailsLabel = stringResource(R.string.technical_details)
    val runtimeLogsLabel = stringResource(R.string.runtime_logs)
    val logsUnavailable = stringResource(R.string.logs_unavailable)

    RuntimeDisplayEffect(request.settings, enabled = session != null)
    RuntimeMediaVolumeEffect(enabled = session != null)

    DisposableEffect(Unit) {
        onDispose {
            RuntimeMemoryCleaner.cleanUpMemory()
        }
    }

    fun handleBack() {
        when {
            failure != null -> onBack()
            showCheats -> showCheats = false
            editControls -> {
                editControls = false
                virtualInput = LogicalInputSnapshot(emptySet(), emptySet())
                persistGameLayouts(layouts)
            }

            else -> confirmExit = true
        }
    }
    fun sendEscapeToGame() {
        scope.launch {
            val down = LogicalInputSnapshot(
                pressedActions = setOf(GameAction.ESCAPE),
                pointers = emptySet(),
                pressedKeyCodes = setOf(AndroidKeyEvent.KEYCODE_ESCAPE),
            )
            virtualInput = down
            delay(48)
            if (virtualInput == down) virtualInput = LogicalInputSnapshot(emptySet(), emptySet())
        }
    }
    BackHandler {
        val capture = keyCapture
        when {
            // Emulator and many keyboards report Esc as Back. Bind it while
            // the editor is waiting, otherwise give it to the game as Escape
            // so RPG Maker can open the menu.
            capture != null -> {
                keyCapture = null
                capture(AndroidKeyEvent.KEYCODE_ESCAPE)
            }
            failure != null || showCheats || editControls || !gameReady -> handleBack()
            else -> sendEscapeToGame()
        }
    }

    fun sendCheat(operation: CheatOperation) {
        if (!request.settings.modules.cheatBridge) return
        cheatSequence += 1
        cheatCommand = CheatCommand(cheatSequence, operation)
    }

    LaunchedEffect(backend, request, launchAttempt) {
        session = null
        gameReady = false
        failure = null
        failureLogsReady = false
        logsCopied = false
        stopCurrentSession = null
        cheatCommand = null
        cheatCatalog = CheatCatalog()
        showCheats = false
        cheatsAvailable = null
        showControls = true
        editControls = false
        virtualInput = LogicalInputSnapshot(emptySet(), emptySet())
        layouts = ControllerLayouts()
        layoutLoaded = false
        selectedControlId = null
        val inherited = withContext(Dispatchers.IO) { defaultStore?.load() ?: ControllerLayouts() }
        layouts = withContext(Dispatchers.IO) { layoutStore?.load(inherited) ?: inherited }
        layoutLoaded = true
        selectedControlId = layouts.activeProfile().controls.firstOrNull()?.id
        val prepared = runCatching {
            withContext(Dispatchers.IO) { backend.prepare(request) }
        }.getOrElse { cause ->
            failure = RuntimeFailureUi(
                title = gameCouldNotStart,
                reason = preparationFailureReason,
                technicalDetails = "${cause.javaClass.simpleName}: ${cause.message ?: preparationFailure}",
            )
            failureLogsReady = true
            return@LaunchedEffect
        }
        val sessionEnd = CompletableDeferred<Unit>()
        stopCurrentSession = { sessionEnd.complete(Unit) }
        session = prepared
        try {
            sessionEnd.await()
        } finally {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    backend.destroySession(prepared.sessionId)
                }
            } finally {
                stopCurrentSession = null
                if (failure?.sessionId == prepared.sessionId) {
                    failureLogsReady = true
                }
                RuntimeMemoryCleaner.cleanUpMemory()
            }
        }
    }

    fun copyLogs() {
        val currentFailure = failure ?: return
        scope.launch {
            val logs = withContext(Dispatchers.IO) {
                runCatching {
                    logFile
                        ?.takeIf { it.isFile && !Files.isSymbolicLink(it.toPath()) }
                        ?.readText()
                        .orEmpty()
                }.getOrDefault("")
            }
            clipboard.setText(
                AnnotatedString(
                    buildRuntimeFailureReport(
                        failure = currentFailure,
                        logs = logs,
                        technicalDetailsLabel = technicalDetailsLabel,
                        logsLabel = runtimeLogsLabel,
                        logsUnavailable = logsUnavailable,
                    ),
                ),
            )
            logsCopied = true
        }
    }

    val focusRequester = remember { FocusRequester() }
    var runtimeViewport by remember { mutableStateOf(IntSize.Zero) }
    var runtimeSurfacePositionOnScreen by remember { mutableStateOf(IntOffset.Zero) }
    var overlayPositionOnScreen by remember { mutableStateOf(IntOffset.Zero) }
    val density = LocalDensity.current
    LaunchedEffect(gameReady, session?.sessionId) {
        if (gameReady && session != null) {
            runCatching { focusRequester.requestFocus() }
        }
    }
    val runtimeModifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
        .focusRequester(focusRequester)
        .focusTarget()
        .onKeyEvent { event ->
            val keyCode = event.nativeKeyEvent.keyCode
            // Never consume volume keys — returning true here blocked STREAM_MUSIC
            // adjustments from the side volume gesture / rocker.
            if (
                keyCode == AndroidKeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == AndroidKeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == AndroidKeyEvent.KEYCODE_VOLUME_MUTE
            ) {
                return@onKeyEvent false
            }
            if (keyCapture != null && event.type == KeyEventType.KeyDown) {
                val capture = keyCapture
                keyCapture = null
                capture?.invoke(keyCode)
                return@onKeyEvent true
            }
            if (editControls) return@onKeyEvent true
            val action = PhysicalInputNormalizer.keyMap[keyCode]
            when (event.type) {
                KeyEventType.KeyDown -> {
                    val actions = virtualInput.pressedActions.toMutableSet()
                    val keys = virtualInput.pressedKeyCodes.toMutableSet()
                    if (action != null) actions.add(action)
                    keys.add(keyCode)
                    virtualInput = LogicalInputSnapshot(actions, virtualInput.pointers, keys)
                    true
                }
                KeyEventType.KeyUp -> {
                    val actions = virtualInput.pressedActions.toMutableSet()
                    val keys = virtualInput.pressedKeyCodes.toMutableSet()
                    if (action != null) actions.remove(action)
                    keys.remove(keyCode)
                    virtualInput = LogicalInputSnapshot(actions, virtualInput.pointers, keys)
                    true
                }
                else -> false
            }
        }
    BoxWithConstraints(modifier = runtimeModifier.onSizeChanged { runtimeViewport = it }) {
        val editorMaxHeight = (maxHeight - 96.dp).coerceIn(48.dp, 420.dp)
        // Hide the touch-capturing gamepad while the system IME is up so soft-keyboard
        // taps are not stolen by the always-on-top overlay Popup.
        val imeBottomPx = WindowInsets.ime.getBottom(density)
        val imeVisible = imeBottomPx > 0
        // Overlay Popup is anchored at the window origin and must share the same
        // client coordinate space as the WebView canvas mapping in input-bridge.js.
        when {
            session != null -> backend.RuntimeContent(
                session = session!!,
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coordinates ->
                        val position = coordinates.positionOnScreen()
                        val offset = IntOffset(position.x.roundToInt(), position.y.roundToInt())
                        if (runtimeSurfacePositionOnScreen != offset) {
                            runtimeSurfacePositionOnScreen = offset
                        }
                    },
                inputEnabled = gameReady && !showCheats && !editControls,
                // Always forward keyboard/gamepad keys. When the on-screen
                // pad is hidden, drop only pointer hits — not key state.
                virtualInput = when {
                    editControls -> LogicalInputSnapshot(emptySet(), emptySet())
                    showControls -> virtualInput
                    else -> LogicalInputSnapshot(
                        pressedActions = virtualInput.pressedActions,
                        pointers = emptySet(),
                        pressedKeyCodes = virtualInput.pressedKeyCodes,
                    )
                },
                cheatFlags = cheatFlags,
                cheatCommand = cheatCommand,
                onCheatCommandConsumed = { sequence ->
                    if (cheatCommand?.sequence == sequence) cheatCommand = null
                },
                onCheatCatalogChanged = { cheatCatalog = it },
                onReadyChanged = {
                    gameReady = it
                    if (it) bootRecoveries = 0
                },
                onEvent = eventHandler@{ event ->
                    when (event) {
                        is RuntimeEvent.ExitRequested -> if (event.sessionId == session?.sessionId) {
                            onBack()
                        }

                        is RuntimeEvent.CheatAvailabilityChanged -> if (event.sessionId == session?.sessionId) {
                            cheatsAvailable = event.available
                            if (!event.available) {
                                showCheats = false
                                cheatCommand = null
                            }
                        }

                        is RuntimeEvent.JavaScriptError -> {
                            val failedSessionId = session?.sessionId ?: return@eventHandler
                            if (
                                event.sessionId != failedSessionId ||
                                gameReady ||
                                !isFatalJavaScriptBootError(event.message)
                            ) {
                                return@eventHandler
                            }
                            session = null
                            gameReady = false
                            cheatCommand = null
                            showCheats = false
                            logsCopied = false
                            failureLogsReady = false
                            val location = buildString {
                                if (event.source.isNotBlank()) append(event.source)
                                if (event.line > 0) {
                                    if (isNotEmpty()) append(':')
                                    append(event.line)
                                }
                            }
                            failure = RuntimeFailureUi(
                                title = gameCouldNotStart,
                                reason = javascriptBootReason,
                                technicalDetails = listOfNotNull(
                                    location.takeIf { it.isNotBlank() },
                                    event.message.takeIf { it.isNotBlank() },
                                ).joinToString("\n").ifBlank { event.message },
                                sessionId = failedSessionId,
                            )
                            stopCurrentSession?.invoke()
                        }

                        else -> shouldFailRendererEvent(
                            session?.sessionId,
                            gameReady,
                            event,
                        )?.let { rendererFailure ->
                            val failedSessionId = session?.sessionId ?: return@let
                            if (!gameReady && bootRecoveries < MAX_BOOT_RECOVERIES) {
                                bootRecoveries += 1
                                session = null
                                cheatCommand = null
                                showCheats = false
                                stopCurrentSession?.invoke()
                                launchAttempt++
                                return@let
                            }
                            session = null
                            gameReady = false
                            cheatCommand = null
                            showCheats = false
                            logsCopied = false
                            failureLogsReady = false
                            failure = RuntimeFailureUi(
                                title = if (rendererFailure == RendererFailure.UNRESPONSIVE) {
                                    gameCouldNotStart
                                } else {
                                    gameStopped
                                },
                                reason = when (rendererFailure) {
                                    RendererFailure.CRASHED -> rendererCrashReason
                                    RendererFailure.STOPPED -> rendererStoppedReason
                                    RendererFailure.UNRESPONSIVE -> rendererUnresponsiveReason
                                },
                                technicalDetails = when (rendererFailure) {
                                    RendererFailure.CRASHED -> rendererCrashed
                                    RendererFailure.STOPPED -> rendererStopped
                                    RendererFailure.UNRESPONSIVE -> rendererUnresponsive
                                },
                                sessionId = failedSessionId,
                            )
                            stopCurrentSession?.invoke()
                        }
                    }
                },
            )

            failure != null -> RuntimeFailureScreen(
                failure = failure!!,
                actionsEnabled = failureLogsReady,
                logsCopied = logsCopied,
                onRestart = { launchAttempt++ },
                onCopyLogs = ::copyLogs,
                onExit = onBack,
                modifier = Modifier.fillMaxSize(),
            )

            else -> RuntimePreparing(modifier = Modifier.align(Alignment.Center))
        }
        if (session != null && !gameReady) {
            RuntimePreparing(modifier = Modifier.align(Alignment.Center))
        }
        LaunchedEffect(imeVisible) {
            if (imeVisible) {
                editControls = false
                virtualInput = LogicalInputSnapshot(
                    pressedActions = emptySet(),
                    pointers = emptySet(),
                    pressedKeyCodes = virtualInput.pressedKeyCodes,
                )
            }
        }
        val overlayVisible = session != null &&
            shouldHostRuntimeOverlay(gameReady, runtimeViewport, imeVisible)
        val overlayNeedsPopup = overlayVisible &&
            shouldHostRuntimeOverlayPopup(
                backendId = backend.descriptor.id,
                gameReady = gameReady,
                runtimeViewport = runtimeViewport,
                imeVisible = imeVisible,
            )
        @Composable
        fun OverlayChrome(modifier: Modifier) {
            Box(
                modifier.onGloballyPositioned { coordinates ->
                    val position = coordinates.positionOnScreen()
                    val offset = IntOffset(position.x.roundToInt(), position.y.roundToInt())
                    if (overlayPositionOnScreen != offset) overlayPositionOnScreen = offset
                },
            ) {
                if (showControls && layoutLoaded && !showCheats) {
                    val pageOffset = overlayPageOffsetPx(
                        overlayPositionOnScreen = overlayPositionOnScreen,
                        runtimeSurfacePositionOnScreen = runtimeSurfacePositionOnScreen,
                    )
                    VirtualControllerOverlay(
                        profile = layouts.activeProfile(),
                        editMode = editControls,
                        selectedControlId = selectedControlId,
                        onControlMoved = { controlId, deltaX, deltaY ->
                            layouts = layouts.updateActive(
                                moveVirtualControl(
                                    layouts.activeProfile(),
                                    controlId,
                                    deltaX,
                                    deltaY,
                                )
                            )
                        },
                        onControlSelected = { selectedControlId = it },
                        onSnapshotChanged = { virtualInput = it },
                        pointerPageOffsetX = pageOffset.first,
                        pointerPageOffsetY = pageOffset.second,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (editControls) {
                        RuntimeOverlayTheme {
                            ControllerEditorPanel(
                                profile = layouts.activeProfile(),
                                selectedId = selectedControlId,
                                onProfileChanged = { layouts = layouts.updateActive(it) },
                                onSelected = { selectedControlId = it },
                                onKeyCapture = { keyCapture = it },
                                onResetProfile = {
                                    scope.launch {
                                        val inherited = withContext(Dispatchers.IO) {
                                            defaultStore?.load() ?: ControllerLayouts()
                                        }
                                        val resetLayouts = layouts.resetActive(inherited)
                                        layouts = resetLayouts
                                        selectedControlId =
                                            resetLayouts.activeProfile().controls.firstOrNull()?.id
                                        persistGameLayouts(resetLayouts)
                                    }
                                },
                                onSaveAsDefault = defaultStore?.let {
                                    {
                                        persistDefaultLayouts(layouts)
                                        android.widget.Toast.makeText(
                                            context,
                                            savedAsDefaultMessage,
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .safeDrawingPadding()
                                    .padding(start = 12.dp, top = 76.dp, bottom = 12.dp, end = 12.dp)
                                    .widthIn(max = 380.dp)
                                    .heightIn(max = editorMaxHeight),
                            )
                        }
                    }
                }
                if (!showCheats) {
                    PlayerToolbar(
                        showControls = showControls,
                        editControls = editControls,
                        cheatsAvailable = request.settings.modules.cheatBridge &&
                            cheatsAvailable == true,
                        layoutLoaded = layoutLoaded,
                        controllerMode = layouts.mode,
                        onToggleControls = {
                            if (showControls && editControls) {
                                persistGameLayouts(layouts)
                            }
                            showControls = !showControls
                            editControls = false
                            virtualInput = LogicalInputSnapshot(emptySet(), emptySet())
                        },
                        onToggleEditing = {
                            if (!showControls) showControls = true
                            val enteringEditing = !editControls
                            android.util.Log.i(
                                "MakerPlay",
                                "runtime.controls.edit entering=$enteringEditing",
                            )
                            editControls = enteringEditing
                            if (enteringEditing &&
                                layouts.activeProfile().controls.none { it.id == selectedControlId }
                            ) {
                                selectedControlId =
                                    layouts.activeProfile().controls.firstOrNull()?.id
                            }
                            virtualInput = LogicalInputSnapshot(emptySet(), emptySet())
                            if (!editControls) {
                                persistGameLayouts(layouts)
                            }
                        },
                        onOpenCheats = {
                            if (request.settings.modules.cheatBridge) {
                                if (editControls) {
                                    persistGameLayouts(layouts)
                                }
                                editControls = false
                                virtualInput = LogicalInputSnapshot(emptySet(), emptySet())
                                showCheats = true
                            }
                        },
                        onSwitchMode = {
                            layouts = layouts.copy(
                                mode = if (layouts.mode == ControllerMode.GAMEPAD) {
                                    ControllerMode.KEYBOARD
                                } else {
                                    ControllerMode.GAMEPAD
                                },
                            )
                            selectedControlId =
                                layouts.activeProfile().controls.firstOrNull()?.id
                            showControls = true
                            virtualInput = LogicalInputSnapshot(emptySet(), emptySet())
                            persistGameLayouts(layouts)
                        },
                        onRestart = { showRestartConfirm = true },
                        onBack = ::handleBack,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
        if (overlayNeedsPopup) {
            // WOLF's GLSurfaceView is ZOrderOnTop and covers Compose siblings.
            Popup(
                alignment = Alignment.TopStart,
                onDismissRequest = {
                    if (editControls) {
                        editControls = false
                        virtualInput = LogicalInputSnapshot(emptySet(), emptySet())
                        persistGameLayouts(layouts)
                    }
                },
                properties = PopupProperties(
                    // Never steal window focus from the game surface. Toggling
                    // focusable here on Edit recreates the popup and deadlocks IME.
                    focusable = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                ),
            ) {
                OverlayChrome(
                    Modifier.requiredSize(
                        with(density) { runtimeViewport.width.toDp() },
                        with(density) { runtimeViewport.height.toDp() },
                    ),
                )
            }
        } else if (overlayVisible) {
            OverlayChrome(Modifier.fillMaxSize().zIndex(1f))
        }

        // Cheats need a real dialog window so IME insets/focus work. Keeping them inside
        // the controller Popup made the panel jump away when the keyboard opened.
        if (session != null && gameReady && showCheats) {
            Dialog(
                onDismissRequest = { showCheats = false },
                properties = DialogProperties(
                    dismissOnBackPress = true,
                    dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = true,
                ),
            ) {
                RuntimeOverlayTheme {
                    cheatStateHolder.SaveableStateProvider("cheat-menu-${request.gameId}") {
                        CheatOverlay(
                            flags = cheatFlags,
                            catalog = cheatCatalog,
                            onFlagsChanged = { flags ->
                                cheatFlags = flags
                                sendCheat(flags.toSetFlags())
                            },
                            onOperation = ::sendCheat,
                            onClose = { showCheats = false },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.exit_game_title)) },
            text = { Text(stringResource(R.string.exit_game_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    onBack()
                }) {
                    Text(stringResource(R.string.exit_game))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) {
                    Text(stringResource(R.string.continue_playing))
                }
            },
        )
    }

    if (showRestartConfirm) {
        AlertDialog(
            onDismissRequest = { showRestartConfirm = false },
            icon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
            title = { Text(stringResource(R.string.restart_game)) },
            text = { Text(stringResource(R.string.exit_game_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showRestartConfirm = false
                        bootRecoveries = 0
                        virtualInput = LogicalInputSnapshot(emptySet(), emptySet())
                        launchAttempt++
                    },
                ) {
                    Text(stringResource(R.string.restart_game))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestartConfirm = false }) {
                    Text(stringResource(R.string.continue_playing))
                }
            },
        )
    }
}

internal const val WEBVIEW_BACKEND_ID = "webview"
internal const val MAX_BOOT_RECOVERIES = 2

internal fun shouldHostRuntimeOverlay(
    gameReady: Boolean,
    runtimeViewport: IntSize,
    imeVisible: Boolean,
): Boolean = gameReady && runtimeViewport != IntSize.Zero && !imeVisible

internal fun shouldHostRuntimeOverlayPopup(
    backendId: String,
    gameReady: Boolean,
    runtimeViewport: IntSize,
    imeVisible: Boolean,
): Boolean = backendId != WEBVIEW_BACKEND_ID &&
    shouldHostRuntimeOverlay(gameReady, runtimeViewport, imeVisible)