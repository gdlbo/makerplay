package io.github.gdlbo.makerplay.feature.player.runtime

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.gdlbo.makerplay.feature.player.R
import io.github.gdlbo.makerplay.feature.player.controller.data.ControllerLayoutStore
import io.github.gdlbo.makerplay.feature.player.controller.model.ControllerLayouts
import io.github.gdlbo.makerplay.feature.player.controller.model.ControllerMode
import io.github.gdlbo.makerplay.feature.player.controller.ui.ControllerEditorPanel
import io.github.gdlbo.makerplay.feature.player.controller.ui.VirtualControllerOverlay
import io.github.gdlbo.makerplay.feature.player.controller.ui.moveVirtualControl
import io.github.gdlbo.makerplay.feature.player.runtime.components.CheatOverlay
import io.github.gdlbo.makerplay.feature.player.runtime.components.PlayerBackButton
import io.github.gdlbo.makerplay.feature.player.runtime.components.PlayerToolbar
import io.github.gdlbo.makerplay.feature.player.runtime.components.RuntimeFailureScreen
import io.github.gdlbo.makerplay.feature.player.runtime.components.RuntimeOverlayTheme
import io.github.gdlbo.makerplay.feature.player.runtime.components.RuntimeFailureUi
import io.github.gdlbo.makerplay.feature.player.runtime.components.RuntimePreparing
import io.github.gdlbo.makerplay.feature.player.runtime.components.buildRuntimeFailureReport
import io.github.gdlbo.makerplay.input.LogicalInputSnapshot
import io.github.gdlbo.makerplay.runtime.api.CheatCatalog
import io.github.gdlbo.makerplay.runtime.api.CheatCommand
import io.github.gdlbo.makerplay.runtime.api.CheatFlags
import io.github.gdlbo.makerplay.runtime.api.CheatOperation
import io.github.gdlbo.makerplay.runtime.api.GameRuntimeBackend
import io.github.gdlbo.makerplay.runtime.api.LaunchRequest
import io.github.gdlbo.makerplay.runtime.api.PreparedSession
import io.github.gdlbo.makerplay.runtime.api.RuntimeEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    var cheatFlags by remember(request.gameId) { mutableStateOf(CheatFlags()) }
    var cheatCommand by remember(request.gameId) { mutableStateOf<CheatCommand?>(null) }
    var cheatCatalog by remember(request.gameId) { mutableStateOf(CheatCatalog()) }
    var cheatSequence by remember { mutableLongStateOf(0L) }
    var showCheats by remember(request.gameId) { mutableStateOf(false) }
    var cheatsAvailable by remember(request.gameId) { mutableStateOf<Boolean?>(null) }
    var showControls by remember(request.gameId) { mutableStateOf(true) }
    var editControls by remember(request.gameId) { mutableStateOf(false) }
    var confirmExit by rememberSaveable(request.gameId) { mutableStateOf(false) }
    var layouts by remember(request.gameId) { mutableStateOf(ControllerLayouts()) }
    var layoutLoaded by remember(request.gameId) { mutableStateOf(layoutFile == null) }
    var selectedControlId by remember(request.gameId) { mutableStateOf<String?>(null) }
    var virtualInput by remember(request.gameId) {
        mutableStateOf(
            LogicalInputSnapshot(
                emptySet(),
                emptySet()
            )
        )
    }
    val layoutStore = remember(layoutFile) { layoutFile?.let(::ControllerLayoutStore) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val preparationFailure = stringResource(R.string.runtime_preparation_failed)
    val rendererCrashed = stringResource(R.string.renderer_stopped_unexpectedly)
    val rendererStopped = stringResource(R.string.renderer_stopped_by_system)
    val gameCouldNotStart = stringResource(R.string.game_could_not_start)
    val gameStopped = stringResource(R.string.game_stopped)
    val preparationFailureReason = stringResource(R.string.preparation_failure_reason)
    val rendererCrashReason = stringResource(R.string.renderer_crash_reason)
    val rendererStoppedReason = stringResource(R.string.renderer_stopped_reason)
    val technicalDetailsLabel = stringResource(R.string.technical_details)
    val runtimeLogsLabel = stringResource(R.string.runtime_logs)
    val logsUnavailable = stringResource(R.string.logs_unavailable)

    RuntimeDisplayEffect(request.settings, enabled = session != null)

    fun handleBack() {
        when {
            failure != null -> onBack()
            showCheats -> showCheats = false
            editControls -> {
                editControls = false
                virtualInput = LogicalInputSnapshot(emptySet(), emptySet())
                scope.launch(Dispatchers.IO) { layoutStore?.save(layouts) }
            }

            else -> confirmExit = true
        }
    }
    BackHandler(onBack = ::handleBack)

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
        layoutLoaded = layoutStore == null
        selectedControlId = null
        if (layoutStore != null) {
            layouts = withContext(Dispatchers.IO) { layoutStore.load() }
            layoutLoaded = true
            selectedControlId = layouts.activeProfile().controls.firstOrNull()?.id
        }
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
            }
        }
    }

    fun copyLogs() {
        val currentFailure = failure ?: return
        scope.launch {
            val logs = withContext(Dispatchers.IO) {
                runCatching {
                    logFile
                        ?.takeIf { request.settings.recordLogs }
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

    val runtimeModifier = Modifier
        .fillMaxSize()
        .then(if (request.settings.immersiveMode) Modifier else Modifier.safeDrawingPadding())
        .background(Color.Black)
    BoxWithConstraints(modifier = runtimeModifier) {
        val compactHeight = maxHeight < 480.dp
        when {
            session != null -> backend.RuntimeContent(
                session = session!!,
                modifier = Modifier.fillMaxSize(),
                inputEnabled = gameReady && !showCheats && !editControls,
                virtualInput = if (showControls && !editControls) virtualInput else LogicalInputSnapshot(
                    emptySet(),
                    emptySet()
                ),
                cheatFlags = cheatFlags,
                cheatCommand = cheatCommand,
                onCheatCommandConsumed = { sequence ->
                    if (cheatCommand?.sequence == sequence) cheatCommand = null
                },
                onCheatCatalogChanged = { cheatCatalog = it },
                onReadyChanged = { gameReady = it },
                onEvent = { event ->
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

                        else -> rendererFailure(session?.sessionId, event)?.let { rendererFailure ->
                            val failedSessionId = session?.sessionId ?: return@let
                            session = null
                            gameReady = false
                            cheatCommand = null
                            showCheats = false
                            logsCopied = false
                            failureLogsReady = false
                            failure = RuntimeFailureUi(
                                title = gameStopped,
                                reason = if (rendererFailure == RendererFailure.CRASHED) {
                                    rendererCrashReason
                                } else {
                                    rendererStoppedReason
                                },
                                technicalDetails = if (rendererFailure == RendererFailure.CRASHED) {
                                    rendererCrashed
                                } else {
                                    rendererStopped
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
        if (session != null && gameReady) {
            // The WOLF game surface is an opaque GLSurfaceView (ZOrderOnTop),
            // which covers every Compose sibling in this window. Host the
            // controller chrome in its own always-on-top window so buttons are
            // visible above the surface and receive taps.
            androidx.compose.ui.window.Popup(
                alignment = Alignment.TopStart,
                onDismissRequest = {},
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
            ) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        if (session != null && gameReady && showControls && layoutLoaded && !showCheats) {
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
                            deltaY
                        )
                    )
                },
                onControlSelected = { selectedControlId = it },
                onSnapshotChanged = { virtualInput = it },
                modifier = Modifier.fillMaxSize(),
            )
            if (editControls) {
                RuntimeOverlayTheme {
                    ControllerEditorPanel(
                        profile = layouts.activeProfile(),
                        selectedId = selectedControlId,
                        onProfileChanged = { layouts = layouts.updateActive(it) },
                        onSelected = { selectedControlId = it },
                        onResetProfile = {
                            val resetLayouts = layouts.resetActive()
                            layouts = resetLayouts
                            selectedControlId = resetLayouts.activeProfile().controls.firstOrNull()?.id
                            scope.launch(Dispatchers.IO) { layoutStore?.save(resetLayouts) }
                        },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .safeDrawingPadding()
                            .padding(start = 12.dp, top = if (compactHeight) 56.dp else 64.dp, end = 12.dp)
                            .widthIn(max = 600.dp)
                            .heightIn(max = if (compactHeight) 320.dp else 480.dp),
                    )
                }
            }
        }
        if (session != null && gameReady && !showCheats) {
            PlayerToolbar(
                showControls = showControls,
                editControls = editControls,
                cheatsAvailable = request.settings.modules.cheatBridge && cheatsAvailable == true,
                layoutLoaded = layoutLoaded,
                controllerMode = layouts.mode,
                loggingEnabled = loggingEnabled,
                onToggleLogging = onToggleLogging,
                onToggleControls = {
                    if (showControls && editControls) scope.launch(Dispatchers.IO) {
                        layoutStore?.save(
                            layouts
                        )
                    }
                    showControls = !showControls
                    editControls = false
                    virtualInput = LogicalInputSnapshot(emptySet(), emptySet())
                },
                onToggleEditing = {
                    if (!showControls) showControls = true
                    val enteringEditing = !editControls
                    editControls = enteringEditing
                    if (enteringEditing && layouts.activeProfile().controls.none { it.id == selectedControlId }) {
                        selectedControlId = layouts.activeProfile().controls.firstOrNull()?.id
                    }
                    virtualInput = LogicalInputSnapshot(emptySet(), emptySet())
                    if (!editControls) scope.launch(Dispatchers.IO) { layoutStore?.save(layouts) }
                },
                onOpenCheats = {
                    if (request.settings.modules.cheatBridge) {
                        if (editControls) scope.launch(Dispatchers.IO) { layoutStore?.save(layouts) }
                        editControls = false
                        virtualInput = LogicalInputSnapshot(emptySet(), emptySet())
                        showCheats = true
                    }
                },
                onSwitchMode = {
                    layouts =
                        layouts.copy(mode = if (layouts.mode == ControllerMode.GAMEPAD) ControllerMode.KEYBOARD else ControllerMode.GAMEPAD)
                    selectedControlId = layouts.activeProfile().controls.firstOrNull()?.id
                    showControls = true
                    virtualInput = LogicalInputSnapshot(emptySet(), emptySet())
                    scope.launch(Dispatchers.IO) { layoutStore?.save(layouts) }
                },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        if (!showCheats && failure == null) {
            PlayerBackButton(onBack = ::handleBack, modifier = Modifier.align(Alignment.TopEnd))
        }
        if (session != null && gameReady && showCheats) {
            CheatOverlay(
                flags = cheatFlags,
                catalog = cheatCatalog,
                onFlagsChanged = { flags ->
                    cheatFlags = flags
                    sendCheat(
                        CheatOperation.SetFlags(
                            godMode = flags.godMode,
                            infiniteHp = flags.infiniteHp,
                            infiniteMp = flags.infiniteMp,
                            playerSpeedMultiplier = flags.playerSpeedMultiplier,
                            noClip = flags.noClip,
                        )
                    )
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
}