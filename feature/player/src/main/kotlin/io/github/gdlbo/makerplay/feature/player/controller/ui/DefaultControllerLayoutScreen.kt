package io.github.gdlbo.makerplay.feature.player.controller.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.gdlbo.makerplay.feature.player.R
import io.github.gdlbo.makerplay.feature.player.controller.data.ControllerLayoutStore
import io.github.gdlbo.makerplay.feature.player.controller.model.ControllerLayouts
import io.github.gdlbo.makerplay.feature.player.controller.model.ControllerMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DefaultControllerLayoutScreen(
    layoutFile: File,
    onBack: () -> Unit,
) {
    val store = remember(layoutFile) { ControllerLayoutStore(layoutFile) }
    val scope = rememberCoroutineScope()
    var layouts by remember { mutableStateOf(ControllerLayouts()) }
    var original by remember { mutableStateOf(ControllerLayouts()) }
    var selectedControlId by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var keyCapture by remember { mutableStateOf<((Int) -> Unit)?>(null) }
    val focusRequester = remember { FocusRequester() }

    fun persistAndLeave(value: ControllerLayouts) {
        scope.launch {
            if (value != original) {
                withContext(Dispatchers.IO) { store.saveOrClear(value) }
            }
            onBack()
        }
    }

    LaunchedEffect(layoutFile) {
        val loadedLayouts = withContext(Dispatchers.IO) { store.load() }
        layouts = loadedLayouts
        original = loadedLayouts
        selectedControlId = loadedLayouts.activeProfile().controls.firstOrNull()?.id
        loaded = true
        runCatching { focusRequester.requestFocus() }
    }

    BackHandler {
        val capture = keyCapture
        if (capture != null) {
            keyCapture = null
            capture(KeyEvent.KEYCODE_ESCAPE)
        } else {
            persistAndLeave(layouts)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.default_controller_editor_title)) },
                navigationIcon = {
                    IconButton(onClick = { persistAndLeave(layouts) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.default_controller_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            layouts = layouts.copy(
                                mode = if (layouts.mode == ControllerMode.GAMEPAD) {
                                    ControllerMode.KEYBOARD
                                } else {
                                    ControllerMode.GAMEPAD
                                },
                            )
                            selectedControlId =
                                layouts.activeProfile().controls.firstOrNull()?.id
                        },
                    ) {
                        Icon(
                            if (layouts.mode == ControllerMode.GAMEPAD) {
                                Icons.Default.Keyboard
                            } else {
                                Icons.Default.SportsEsports
                            },
                            contentDescription = stringResource(R.string.controller_switch_mode),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .focusRequester(focusRequester)
                .focusTarget()
                .onPreviewKeyEvent { event ->
                    val capture = keyCapture ?: return@onPreviewKeyEvent false
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                    val keyCode = event.nativeKeyEvent.keyCode
                    if (
                        keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                        keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
                    ) {
                        return@onPreviewKeyEvent false
                    }
                    keyCapture = null
                    capture(keyCode)
                    true
                },
        ) {
            val editorMaxHeight = (maxHeight - 96.dp).coerceIn(48.dp, 420.dp)
            if (loaded) {
                VirtualControllerOverlay(
                    profile = layouts.activeProfile(),
                    editMode = true,
                    selectedControlId = selectedControlId,
                    onControlMoved = { controlId, deltaX, deltaY ->
                        layouts = layouts.updateActive(
                            moveVirtualControl(layouts.activeProfile(), controlId, deltaX, deltaY),
                        )
                    },
                    onControlSelected = { selectedControlId = it },
                    onSnapshotChanged = { _ -> },
                    modifier = Modifier.fillMaxSize(),
                )
                ControllerEditorPanel(
                    profile = layouts.activeProfile(),
                    selectedId = selectedControlId,
                    onProfileChanged = { layouts = layouts.updateActive(it) },
                    onSelected = { selectedControlId = it },
                    onResetProfile = {
                        val resetLayouts = layouts.resetActive()
                        layouts = resetLayouts
                        selectedControlId =
                            resetLayouts.activeProfile().controls.firstOrNull()?.id
                    },
                    onKeyCapture = { keyCapture = it },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .safeDrawingPadding()
                        .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 12.dp)
                        .widthIn(max = 380.dp)
                        .heightIn(max = editorMaxHeight),
                )
            }
        }
    }
}
