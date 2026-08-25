package io.github.gdlbo.makerplay.feature.player.controller.ui

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.gdlbo.makerplay.feature.player.R
import io.github.gdlbo.makerplay.input.GameAction
import io.github.gdlbo.makerplay.input.InputStateReducer
import io.github.gdlbo.makerplay.input.LogicalInputSnapshot
import io.github.gdlbo.makerplay.input.VirtualControl
import io.github.gdlbo.makerplay.input.VirtualControlShape
import io.github.gdlbo.makerplay.input.VirtualControlType
import io.github.gdlbo.makerplay.input.VirtualControllerProfile
import io.github.gdlbo.makerplay.input.VirtualControllerProfileValidator
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Composable
internal fun VirtualControllerOverlay(
    profile: VirtualControllerProfile,
    editMode: Boolean,
    selectedControlId: String? = null,
    onControlMoved: (String, Float, Float) -> Unit,
    onControlSelected: (String) -> Unit = {},
    onSnapshotChanged: (LogicalInputSnapshot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducer = remember(profile.id) { InputStateReducer() }
    val latestOnControlMoved = rememberUpdatedState(onControlMoved)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(editMode) {
        if (editMode) {
            reducer.clearAll()
            onSnapshotChanged(reducer.snapshot())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            reducer.clearAll()
            onSnapshotChanged(reducer.snapshot())
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                            val p = event.changes.firstOrNull()?.position ?: androidx.compose.ui.geometry.Offset.Zero
                            android.util.Log.i("OverlayTouch", "press x=${p.x.toInt()} y=${p.y.toInt()} size=$canvasSize")
                        }
                    }
                }
            },
    ) {
        keyboardFrame(profile)?.let { frame ->
            Surface(
                modifier = Modifier
                    .offset(x = maxWidth * frame.left, y = maxHeight * frame.top)
                    .size(width = maxWidth * frame.width, height = maxHeight * frame.height),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF181A1E).copy(alpha = .72f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .12f)),
            ) {}
        }
        profile.controls.forEach { control ->
            key(control.id) {
                val placement = Modifier
                    .offset(x = maxWidth * control.x, y = maxHeight * control.y)
                    .size(maxWidth * control.width, maxHeight * control.height)
                    .then(
                        if (editMode) {
                            Modifier
                                .pointerInput(control.id, canvasSize) {
                                    detectTapGestures(onTap = { onControlSelected(control.id) })
                                }
                                .pointerInput(control.id, canvasSize) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        onControlSelected(control.id)
                                        if (canvasSize.width == 0 || canvasSize.height == 0) {
                                            return@detectDragGestures
                                        }
                                        latestOnControlMoved.value(
                                            control.id,
                                            dragAmount.x / canvasSize.width,
                                            dragAmount.y / canvasSize.height,
                                        )
                                    }
                                }
                        } else {
                            Modifier
                        },
                    )

                if (control.type == VirtualControlType.D_PAD) {
                    VirtualDPad(
                        control = control,
                        editMode = editMode,
                        selected = selectedControlId == control.id,
                        reducer = reducer,
                        onSnapshotChanged = onSnapshotChanged,
                        modifier = placement,
                    )
                } else {
                    VirtualButton(
                        control = control,
                        editMode = editMode,
                        selected = selectedControlId == control.id,
                        reducer = reducer,
                        onSnapshotChanged = onSnapshotChanged,
                        modifier = placement,
                    )
                }
            }
        }
    }
}

@Composable
private fun VirtualButton(
    control: VirtualControl,
    editMode: Boolean,
    selected: Boolean,
    reducer: InputStateReducer,
    onSnapshotChanged: (LogicalInputSnapshot) -> Unit,
    modifier: Modifier,
) {
    val source = "virtual:${control.id}"
    val controlColor = Color(control.color)
    val darkControl = controlColor.luminance() < .45f
    val inputModifier = if (editMode) {
        Modifier
    } else {
        Modifier.pointerInput(control.id) {
            detectTapGestures(onPress = {
                val keyCode = control.keyCode
                if (keyCode != null) reducer.pressKeyCode(
                    source,
                    keyCode
                ) else reducer.press(source, control.action)
                onSnapshotChanged(reducer.snapshot())
                try {
                    tryAwaitRelease()
                } finally {
                    if (keyCode != null) reducer.releaseKeyCode(
                        source,
                        keyCode
                    ) else reducer.release(source, control.action)
                    onSnapshotChanged(reducer.snapshot())
                }
            })
        }
    }

    Surface(
        modifier = modifier.then(inputModifier),
        shape = if (control.shape == VirtualControlShape.CIRCLE) CircleShape else RoundedCornerShape(
            8.dp
        ),
        color = controlColor.copy(alpha = control.opacity.coerceIn(.2f, 1f)),
        contentColor = if (darkControl) Color.White else Color.Black,
        border = controlBorder(editMode, selected, darkControl),
        tonalElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(controlDisplayLabel(control))
        }
    }
}

@Composable
private fun VirtualDPad(
    control: VirtualControl,
    editMode: Boolean,
    selected: Boolean,
    reducer: InputStateReducer,
    onSnapshotChanged: (LogicalInputSnapshot) -> Unit,
    modifier: Modifier,
) {
    val source = "virtual:${control.id}"
    val controlColor = Color(control.color)
    val darkControl = controlColor.luminance() < .45f
    var activeActions by remember(control.id) { mutableStateOf(emptySet<GameAction>()) }
    val inputModifier = if (editMode) {
        Modifier
    } else {
        Modifier.pointerInput(control.id) {
            awaitEachGesture {
                var currentActions = emptySet<GameAction>()

                fun updateActions(nextActions: Set<GameAction>) {
                    if (nextActions == currentActions) return
                    reducer.clearSource(source)
                    nextActions.forEach { reducer.press(source, it) }
                    currentActions = nextActions
                    activeActions = nextActions
                    onSnapshotChanged(reducer.snapshot())
                }

                try {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    updateActions(
                        dPadActionsForPosition(
                            down.position.x / size.width,
                            down.position.y / size.height
                        )
                    )
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        updateActions(
                            dPadActionsForPosition(
                                change.position.x / size.width,
                                change.position.y / size.height
                            ),
                        )
                        change.consume()
                    }
                } finally {
                    reducer.clearSource(source)
                    activeActions = emptySet()
                    onSnapshotChanged(reducer.snapshot())
                }
            }
        }
    }

    Surface(
        modifier = modifier.then(inputModifier),
        shape = CircleShape,
        color = controlColor.copy(alpha = control.opacity.coerceIn(.2f, 1f)),
        contentColor = if (darkControl) Color.White else Color.Black,
        border = controlBorder(editMode, selected, darkControl),
        tonalElevation = 4.dp,
    ) {
        DPadFace(activeActions = activeActions)
    }
}

@Composable
private fun DPadFace(activeActions: Set<GameAction>) {
    val activeColor = MaterialTheme.colorScheme.primary.copy(alpha = .42f)
    val dividerColor = Color.White.copy(alpha = .18f)
    val activeSector = dPadSectorForActions(activeActions)

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            if (activeSector != null) {
                drawArc(
                    color = activeColor,
                    startAngle = activeSector * 45f - 22.5f,
                    sweepAngle = 45f,
                    useCenter = true,
                )
            }
            repeat(8) { index ->
                val radians = (index * 45f - 22.5f) * PI.toFloat() / 180f
                drawLine(
                    color = dividerColor,
                    start = center + Offset(
                        cos(radians),
                        sin(radians)
                    ) * (size.minDimension * .19f),
                    end = center + Offset(cos(radians), sin(radians)) * (size.minDimension * .48f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
        DPadArrow(Icons.Default.KeyboardArrowUp, Alignment.TopCenter, Modifier.padding(top = 4.dp))
        DPadArrow(
            Icons.Default.KeyboardArrowDown,
            Alignment.BottomCenter,
            Modifier.padding(bottom = 4.dp)
        )
        DPadArrow(
            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            Alignment.CenterStart,
            Modifier.padding(start = 4.dp)
        )
        DPadArrow(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            Alignment.CenterEnd,
            Modifier.padding(end = 4.dp)
        )
        DPadArrow(
            Icons.Default.KeyboardArrowUp,
            Alignment.TopStart,
            Modifier
                .padding(8.dp)
                .rotate(-45f)
        )
        DPadArrow(
            Icons.Default.KeyboardArrowUp,
            Alignment.TopEnd,
            Modifier
                .padding(8.dp)
                .rotate(45f)
        )
        DPadArrow(
            Icons.Default.KeyboardArrowDown,
            Alignment.BottomStart,
            Modifier
                .padding(8.dp)
                .rotate(45f)
        )
        DPadArrow(
            Icons.Default.KeyboardArrowDown,
            Alignment.BottomEnd,
            Modifier
                .padding(8.dp)
                .rotate(-45f)
        )
        Surface(
            modifier = Modifier.size(28.dp),
            shape = CircleShape,
            color = Color.Black.copy(alpha = .16f),
        ) {}
    }
}

@Composable
private fun BoxScope.DPadArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    alignment: Alignment,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier
            .align(alignment)
            .then(modifier)
            .size(22.dp),
        tint = Color.White.copy(alpha = .82f),
    )
}

@Composable
private fun controlBorder(
    editMode: Boolean,
    selected: Boolean,
    darkControl: Boolean
): BorderStroke = BorderStroke(
    if (editMode && selected) 2.dp else 1.dp,
    if (editMode && selected) {
        MaterialTheme.colorScheme.primary
    } else {
        (if (darkControl) Color.White else Color.Black).copy(alpha = .16f)
    },
)

private data class KeyboardFrame(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

private fun keyboardFrame(profile: VirtualControllerProfile): KeyboardFrame? {
    if (profile.id != "keyboard") return null
    val keys = profile.controls.filter { it.keyCode != null }
    if (keys.isEmpty()) return null
    val paddingX = .008f
    val paddingY = .012f
    val left = max(0f, keys.minOf { it.x } - paddingX)
    val top = max(0f, keys.minOf { it.y } - paddingY)
    val right = min(1f, keys.maxOf { it.x + it.width } + paddingX)
    val bottom = min(1f, keys.maxOf { it.y + it.height } + paddingY)
    return KeyboardFrame(left, top, right - left, bottom - top)
}

internal fun dPadActionsForPosition(x: Float, y: Float): Set<GameAction> {
    val deltaX = x - .5f
    val deltaY = y - .5f
    val ellipseDistance = (deltaX / .5f) * (deltaX / .5f) + (deltaY / .5f) * (deltaY / .5f)
    if (ellipseDistance > 1f) return emptySet()
    if (hypot(deltaX, deltaY) < .16f) return emptySet()
    val degrees = Math.toDegrees(atan2(deltaY.toDouble(), deltaX.toDouble()))
    return when (floor((degrees + 22.5 + 360.0) % 360.0 / 45.0).toInt()) {
        0 -> setOf(GameAction.RIGHT)
        1 -> setOf(GameAction.DOWN, GameAction.RIGHT)
        2 -> setOf(GameAction.DOWN)
        3 -> setOf(GameAction.DOWN, GameAction.LEFT)
        4 -> setOf(GameAction.LEFT)
        5 -> setOf(GameAction.UP, GameAction.LEFT)
        6 -> setOf(GameAction.UP)
        else -> setOf(GameAction.UP, GameAction.RIGHT)
    }
}

private fun dPadSectorForActions(actions: Set<GameAction>): Int? = when (actions) {
    setOf(GameAction.RIGHT) -> 0
    setOf(GameAction.DOWN, GameAction.RIGHT) -> 1
    setOf(GameAction.DOWN) -> 2
    setOf(GameAction.DOWN, GameAction.LEFT) -> 3
    setOf(GameAction.LEFT) -> 4
    setOf(GameAction.UP, GameAction.LEFT) -> 5
    setOf(GameAction.UP) -> 6
    setOf(GameAction.UP, GameAction.RIGHT) -> 7
    else -> null
}

@Composable
internal fun controlDisplayLabel(control: VirtualControl): String {
    if (control.type == VirtualControlType.D_PAD) return stringResource(R.string.controller_dpad)
    val keyCode = control.keyCode ?: return control.label ?: gameActionLabel(control.action)
    return when (keyCode) {
        KeyEvent.KEYCODE_TAB -> stringResource(R.string.key_tab)
        KeyEvent.KEYCODE_CAPS_LOCK -> stringResource(R.string.key_caps)
        KeyEvent.KEYCODE_ENTER -> stringResource(R.string.key_enter)
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> stringResource(R.string.key_shift)
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> stringResource(R.string.key_ctrl)
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> stringResource(R.string.key_alt)
        KeyEvent.KEYCODE_SPACE -> stringResource(R.string.key_space)
        KeyEvent.KEYCODE_ESCAPE -> stringResource(R.string.key_escape)
        KeyEvent.KEYCODE_MENU -> stringResource(R.string.key_menu)
        KeyEvent.KEYCODE_DEL -> "⌫"
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> (keyCode - KeyEvent.KEYCODE_0).toString()
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + keyCode - KeyEvent.KEYCODE_A).toString()
        KeyEvent.KEYCODE_GRAVE -> "`"
        KeyEvent.KEYCODE_MINUS -> "-"
        KeyEvent.KEYCODE_EQUALS -> "="
        KeyEvent.KEYCODE_LEFT_BRACKET -> "["
        KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
        KeyEvent.KEYCODE_BACKSLASH -> "\\"
        KeyEvent.KEYCODE_SEMICOLON -> ";"
        KeyEvent.KEYCODE_APOSTROPHE -> "'"
        KeyEvent.KEYCODE_COMMA -> ","
        KeyEvent.KEYCODE_PERIOD -> "."
        KeyEvent.KEYCODE_SLASH -> "/"
        else -> control.label ?: keyCode.toString()
    }
}

@Composable
internal fun gameActionLabel(action: GameAction): String = stringResource(
    when (action) {
        GameAction.UP -> R.string.action_up
        GameAction.DOWN -> R.string.action_down
        GameAction.LEFT -> R.string.action_left
        GameAction.RIGHT -> R.string.action_right
        GameAction.OK -> R.string.action_ok
        GameAction.CANCEL -> R.string.action_cancel
        GameAction.SHIFT -> R.string.action_shift
        GameAction.MENU -> R.string.action_menu
        GameAction.PAGE_UP -> R.string.action_page_up
        GameAction.PAGE_DOWN -> R.string.action_page_down
        GameAction.ESCAPE -> R.string.action_escape
        GameAction.CONTROL -> R.string.action_control
        GameAction.TAB -> R.string.action_tab
        GameAction.DEBUG -> R.string.action_debug
        GameAction.POINTER_DOWN, GameAction.POINTER_MOVE, GameAction.POINTER_UP -> R.string.action_pointer
    },
)

internal fun moveVirtualControl(
    profile: VirtualControllerProfile,
    controlId: String,
    deltaX: Float,
    deltaY: Float,
): VirtualControllerProfile = profile.copy(
    controls = profile.controls.map { control ->
        if (control.id != controlId) control else control.copy(
            x = (control.x + deltaX).coerceIn(0f, 1f - control.width),
            y = (control.y + deltaY).coerceIn(0f, 1f - control.height),
        )
    },
).also(VirtualControllerProfileValidator::validate)