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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gdlbo.makerplay.feature.player.R
import io.github.gdlbo.makerplay.input.GameAction
import io.github.gdlbo.makerplay.input.InputStateReducer
import io.github.gdlbo.makerplay.input.LogicalInputSnapshot
import io.github.gdlbo.makerplay.input.VirtualControl
import io.github.gdlbo.makerplay.input.VirtualControlShape
import io.github.gdlbo.makerplay.input.VirtualControlType
import io.github.gdlbo.makerplay.input.VirtualControllerProfile
import io.github.gdlbo.makerplay.input.VirtualControllerProfileValidator
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun VirtualControllerOverlay(
    profile: VirtualControllerProfile,
    editMode: Boolean,
    selectedControlId: String? = null,
    onControlMoved: (String, Float, Float) -> Unit,
    onControlSelected: (String) -> Unit = {},
    onSnapshotChanged: (LogicalInputSnapshot) -> Unit,
    pointerPageOffsetX: Float = 0f,
    pointerPageOffsetY: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val reducer = remember(profile.id) { InputStateReducer() }
    val latestOnControlMoved = rememberUpdatedState(onControlMoved)
    val latestPointerPageOffsetX = rememberUpdatedState(pointerPageOffsetX)
    val latestPointerPageOffsetY = rememberUpdatedState(pointerPageOffsetY)
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
            .pointerInput(profile.controls, canvasSize) {
                awaitEachGesture {
                    // Only forward taps the controls did not consume. Using
                    // requireUnconsumed=false here steals the gesture arena from
                    // D-pad/buttons and breaks virtual keyboard input.
                    val down = awaitFirstDown(requireUnconsumed = true)
                    if (canvasSize.width == 0 || canvasSize.height == 0 || profile.controls.any {
                            down.position.isInside(it, canvasSize)
                        }
                    ) {
                        return@awaitEachGesture
                    }
                    val source = "virtual:game-touch"
                    // Normalized 0..1 overlay coords. input-bridge.js maps these
                    // through the CSS viewport so density/DPR cannot drift.
                    fun normX(localX: Float): Float {
                        val width = canvasSize.width.coerceAtLeast(1)
                        return ((localX + latestPointerPageOffsetX.value) / width)
                            .coerceIn(0f, 1f)
                    }
                    fun normY(localY: Float): Float {
                        val height = canvasSize.height.coerceAtLeast(1)
                        return ((localY + latestPointerPageOffsetY.value) / height)
                            .coerceIn(0f, 1f)
                    }
                    reducer.pointerDown(
                        source,
                        down.id.value,
                        normX(down.position.x),
                        normY(down.position.y),
                    )
                    onSnapshotChanged(reducer.snapshot())
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            reducer.pointerMove(
                                source,
                                down.id.value,
                                normX(change.position.x),
                                normY(change.position.y),
                            )
                            onSnapshotChanged(reducer.snapshot())
                        }
                    } finally {
                        reducer.pointerUp(source, down.id.value)
                        onSnapshotChanged(reducer.snapshot())
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
                color = Color(0xFF14161A).copy(alpha = .78f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .10f)),
            ) {}
        }
        profile.controls.forEach { control ->
            key(control.id) {
                val placement = controlPlacement(
                    control = control,
                    canvasWidth = maxWidth,
                    canvasHeight = maxHeight,
                )
                val interaction = Modifier
                    .offset(x = placement.x, y = placement.y)
                    .size(placement.width, placement.height)
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
                        modifier = interaction,
                    )
                } else {
                    VirtualButton(
                        control = control,
                        editMode = editMode,
                        selected = selectedControlId == control.id,
                        reducer = reducer,
                        onSnapshotChanged = onSnapshotChanged,
                        modifier = interaction,
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
    var pressed by remember(control.id) { mutableStateOf(false) }
    val circular = control.shape == VirtualControlShape.CIRCLE
    val inputModifier = if (editMode) {
        Modifier
    } else {
        Modifier.pointerInput(control.id) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                pressed = true
                val keyCode = control.keyCode
                if (keyCode != null) {
                    reducer.pressKeyCode(source, keyCode)
                } else {
                    reducer.press(source, control.action)
                }
                onSnapshotChanged(reducer.snapshot())
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                    }
                } finally {
                    pressed = false
                    if (keyCode != null) {
                        reducer.releaseKeyCode(source, keyCode)
                    } else {
                        reducer.release(source, control.action)
                    }
                    onSnapshotChanged(reducer.snapshot())
                }
            }
        }
    }

    val fillAlpha = when {
        pressed -> (control.opacity + .16f).coerceIn(.35f, 1f)
        else -> control.opacity.coerceIn(.2f, 1f)
    }
    Surface(
        modifier = modifier.then(inputModifier),
        shape = if (circular) CircleShape else RoundedCornerShape(8.dp),
        color = controlColor.copy(alpha = fillAlpha),
        contentColor = if (darkControl) Color.White else Color.Black,
        border = controlBorder(editMode, selected, darkControl, pressed),
        tonalElevation = if (pressed) 0.dp else 1.dp,
        shadowElevation = 0.dp,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            // Circles are already square-fitted; extra horizontal padding makes
            // right-side face buttons look uneven on tall portrait canvases.
            modifier = if (circular) Modifier else Modifier.padding(horizontal = 4.dp),
        ) {
            Text(
                text = controlDisplayLabel(control),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (circular) 13.sp else 12.sp,
                    letterSpacing = 0.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
                    down.consume()
                    updateActions(
                        dPadActionsForPosition(
                            down.position.x / size.width,
                            down.position.y / size.height,
                        ),
                    )
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        updateActions(
                            dPadActionsForPosition(
                                change.position.x / size.width,
                                change.position.y / size.height,
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
        border = controlBorder(editMode, selected, darkControl, pressed = activeActions.isNotEmpty()),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
    ) {
        DPadFace(activeActions = activeActions)
    }
}

@Composable
private fun DPadFace(activeActions: Set<GameAction>) {
    val idleArm = Color.White.copy(alpha = .10f)
    val activeArm = Color.White.copy(alpha = .30f)
    val ring = Color.White.copy(alpha = .18f)
    val disc = Color.Black.copy(alpha = .14f)

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val diameter = size.minDimension
            val strokeWidth = 1.dp.toPx()
            val radius = diameter * .5f - strokeWidth
            drawCircle(color = disc, radius = radius)
            drawCircle(color = ring, radius = radius, style = Stroke(width = strokeWidth))

            val armThickness = diameter * .33f
            val armReach = diameter * .78f
            val corner = CornerRadius(armThickness * .28f, armThickness * .28f)
            val cx = center.x
            val cy = center.y

            fun drawArm(left: Float, top: Float, width: Float, height: Float, active: Boolean) {
                drawRoundRect(
                    color = if (active) activeArm else idleArm,
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    cornerRadius = corner,
                )
            }

            drawArm(
                left = cx - armThickness / 2f,
                top = cy - armReach / 2f,
                width = armThickness,
                height = armReach / 2f - armThickness * .12f,
                active = GameAction.UP in activeActions,
            )
            drawArm(
                left = cx - armThickness / 2f,
                top = cy + armThickness * .12f,
                width = armThickness,
                height = armReach / 2f - armThickness * .12f,
                active = GameAction.DOWN in activeActions,
            )
            drawArm(
                left = cx - armReach / 2f,
                top = cy - armThickness / 2f,
                width = armReach / 2f - armThickness * .12f,
                height = armThickness,
                active = GameAction.LEFT in activeActions,
            )
            drawArm(
                left = cx + armThickness * .12f,
                top = cy - armThickness / 2f,
                width = armReach / 2f - armThickness * .12f,
                height = armThickness,
                active = GameAction.RIGHT in activeActions,
            )

            val hub = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = cx - armThickness * .42f,
                        top = cy - armThickness * .42f,
                        right = cx + armThickness * .42f,
                        bottom = cy + armThickness * .42f,
                        cornerRadius = CornerRadius(armThickness * .18f, armThickness * .18f),
                    ),
                )
            }
            drawPath(hub, color = Color.Black.copy(alpha = .22f))
            drawPath(hub, color = ring, style = Stroke(width = strokeWidth))
        }
        DPadArrow(Icons.Default.KeyboardArrowUp, Alignment.TopCenter, Modifier.padding(top = 10.dp))
        DPadArrow(
            Icons.Default.KeyboardArrowDown,
            Alignment.BottomCenter,
            Modifier.padding(bottom = 10.dp),
        )
        DPadArrow(
            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            Alignment.CenterStart,
            Modifier.padding(start = 10.dp),
        )
        DPadArrow(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            Alignment.CenterEnd,
            Modifier.padding(end = 10.dp),
        )
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
            .size(20.dp),
        tint = Color.White.copy(alpha = .86f),
    )
}

@Composable
private fun controlBorder(
    editMode: Boolean,
    selected: Boolean,
    darkControl: Boolean,
    pressed: Boolean,
): BorderStroke {
    val base = (if (darkControl) Color.White else Color.Black)
    return BorderStroke(
        width = when {
            editMode && selected -> 2.dp
            pressed -> 1.5.dp
            else -> 1.dp
        },
        color = when {
            editMode && selected -> MaterialTheme.colorScheme.primary
            pressed -> base.copy(alpha = .34f)
            else -> base.copy(alpha = .14f)
        },
    )
}

private data class KeyboardFrame(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

private data class ControlPlacement(
    val x: Dp,
    val y: Dp,
    val width: Dp,
    val height: Dp,
)

private fun controlPlacement(
    control: VirtualControl,
    canvasWidth: Dp,
    canvasHeight: Dp,
): ControlPlacement {
    val rawWidth = canvasWidth * control.width
    val rawHeight = canvasHeight * control.height
    val rawX = canvasWidth * control.x
    val rawY = canvasHeight * control.y
    if (!control.usesCircularBounds()) {
        return ControlPlacement(rawX, rawY, rawWidth, rawHeight)
    }
    val side = minOf(rawWidth, rawHeight)
    return ControlPlacement(
        x = rawX + (rawWidth - side) / 2f,
        y = rawY + (rawHeight - side) / 2f,
        width = side,
        height = side,
    )
}

private fun VirtualControl.usesCircularBounds(): Boolean =
    type == VirtualControlType.D_PAD || shape == VirtualControlShape.CIRCLE

private fun Offset.isInside(control: VirtualControl, canvasSize: IntSize): Boolean {
    val left = control.x * canvasSize.width
    val top = control.y * canvasSize.height
    val rawWidth = control.width * canvasSize.width
    val rawHeight = control.height * canvasSize.height
    if (!control.usesCircularBounds()) {
        return x >= left &&
            x <= left + rawWidth &&
            y >= top &&
            y <= top + rawHeight
    }
    val side = min(rawWidth, rawHeight)
    val centerX = left + rawWidth / 2f
    val centerY = top + rawHeight / 2f
    val dx = x - centerX
    val dy = y - centerY
    return dx * dx + dy * dy <= (side / 2f) * (side / 2f)
}

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

internal fun circularControlSidePx(
    control: VirtualControl,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
): Float {
    require(control.usesCircularBounds())
    return min(control.width * canvasWidthPx, control.height * canvasHeightPx)
}

internal fun controlsFitViewport(
    profile: VirtualControllerProfile,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
): Boolean = profile.controls.all { control ->
    val rawWidth = control.width * canvasWidthPx
    val rawHeight = control.height * canvasHeightPx
    val rawX = control.x * canvasWidthPx
    val rawY = control.y * canvasHeightPx
    if (control.usesCircularBounds()) {
        val side = min(rawWidth, rawHeight)
        val x = rawX + (rawWidth - side) / 2f
        val y = rawY + (rawHeight - side) / 2f
        x >= 0f && y >= 0f && x + side <= canvasWidthPx && y + side <= canvasHeightPx
    } else {
        rawX >= 0f && rawY >= 0f &&
            rawX + rawWidth <= canvasWidthPx && rawY + rawHeight <= canvasHeightPx
    }
}
