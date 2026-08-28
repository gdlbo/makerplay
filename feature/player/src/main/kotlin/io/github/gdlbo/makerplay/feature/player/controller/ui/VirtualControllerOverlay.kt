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
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
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
                    // through the CSS viewport / GameCanvas client rect.
                    fun normalized(localX: Float, localY: Float): Pair<Float, Float> =
                        normalizeOverlayPointer(
                            localX = localX,
                            localY = localY,
                            overlayWidthPx = canvasSize.width,
                            overlayHeightPx = canvasSize.height,
                            pageOffsetXPx = latestPointerPageOffsetX.value,
                            pageOffsetYPx = latestPointerPageOffsetY.value,
                        )
                    val (downX, downY) = normalized(down.position.x, down.position.y)
                    reducer.pointerDown(source, down.id.value, downX, downY)
                    onSnapshotChanged(reducer.snapshot())
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val (moveX, moveY) = normalized(change.position.x, change.position.y)
                            reducer.pointerMove(source, down.id.value, moveX, moveY)
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
                shape = RoundedCornerShape(16.dp),
                color = Color(0xF2101218),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .14f)),
                shadowElevation = 10.dp,
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
                        placement = placement,
                        canvasWidth = maxWidth,
                        canvasHeight = maxHeight,
                        editModifier = interaction,
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
    val isKey = control.keyCode != null
    val circular = control.shape == VirtualControlShape.CIRCLE
    val keyShape = when {
        circular -> CircleShape
        isKey -> RoundedCornerShape(6.dp)
        else -> RoundedCornerShape(10.dp)
    }
    val isModifierKey = isKey && (control.keyCode in listOf(59, 60, 113, 114, 57, 58, 61, 115, 67, 111, 82))
    val isEnterKey = isKey && control.keyCode == 66

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
        pressed -> 1.0f
        else -> control.opacity.coerceIn(.2f, 1f)
    }
    val buttonColor = when {
        pressed -> Color(0xFF0284C7)
        isEnterKey -> Color(0xFF1E3A5F).copy(alpha = fillAlpha)
        isModifierKey -> Color(0xFF1B1D26).copy(alpha = fillAlpha)
        isKey -> Color(0xFF282B36).copy(alpha = fillAlpha)
        else -> controlColor.copy(alpha = fillAlpha)
    }

    Surface(
        modifier = modifier.then(inputModifier),
        shape = keyShape,
        color = buttonColor,
        contentColor = if (pressed) Color.White else if (darkControl || isKey) Color.White else Color.Black,
        border = controlBorder(editMode, selected, darkControl, pressed),
        tonalElevation = if (pressed) 0.dp else 1.dp,
        shadowElevation = if (isKey && !pressed) 2.dp else 0.dp,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            // Circles are already square-fitted; extra horizontal padding makes
            // right-side face buttons look uneven on tall portrait canvases.
            modifier = if (circular) Modifier else Modifier.padding(horizontal = if (isKey) 2.dp else 4.dp),
        ) {
            Text(
                text = controlDisplayLabel(control),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (isKey) FontWeight.Medium else FontWeight.SemiBold,
                    fontSize = if (circular) 13.sp else if (isKey) 11.sp else 12.sp,
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
    placement: ControlPlacement,
    canvasWidth: Dp,
    canvasHeight: Dp,
    editModifier: Modifier,
) {
    val source = "virtual:${control.id}"
    val controlColor = Color(control.color)
    val darkControl = controlColor.luminance() < .45f
    var activeActions by remember(control.id) { mutableStateOf(emptySet<GameAction>()) }

    if (editMode) {
        val editAlpha = control.opacity.coerceIn(.2f, 1f)
        Surface(
            modifier = editModifier,
            shape = CircleShape,
            color = Color(0xFF12141C).copy(alpha = editAlpha),
            contentColor = Color.White.copy(alpha = editAlpha.coerceAtLeast(0.55f)),
            border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                     else BorderStroke(1.dp, Color.White.copy(alpha = 0.12f * editAlpha)),
            tonalElevation = 2.dp,
            shadowElevation = 6.dp,
        ) {
            DPadFace(activeActions = emptySet(), opacity = editAlpha)
        }
        return
    }

    // Play mode: Expanded invisible touch zone so diagonal touches (e.g. left-down) never miss
    // and touches never pass through to cause accidental walking on the game canvas!
    val dPadSize = placement.width
    val extraMargin = (dPadSize * 0.50f).coerceIn(40.dp, 90.dp)
    val touchLeft = if (placement.x <= extraMargin * 1.5f) 0.dp else placement.x - extraMargin
    val touchTop = maxOf(0.dp, placement.y - extraMargin)
    val touchRight = minOf(canvasWidth, placement.x + dPadSize + extraMargin)
    val touchBottom = if (canvasHeight - (placement.y + dPadSize) <= extraMargin * 1.5f) canvasHeight else placement.y + dPadSize + extraMargin
    val touchWidth = touchRight - touchLeft
    val touchHeight = touchBottom - touchTop

    fun actionsForTouchInBox(
        boxX: Float,
        boxY: Float,
        dPadLeftInBoxPx: Float,
        dPadTopInBoxPx: Float,
        dPadSizePx: Float,
    ): Set<GameAction> {
        val centerX = dPadLeftInBoxPx + dPadSizePx / 2f
        val centerY = dPadTopInBoxPx + dPadSizePx / 2f
        val deltaX = boxX - centerX
        val deltaY = boxY - centerY
        val radiusPx = dPadSizePx / 2f
        val dist = kotlin.math.hypot(deltaX, deltaY)
        if (dist < radiusPx * 0.16f) return emptySet()

        val degrees = Math.toDegrees(atan2(deltaY.toDouble(), deltaX.toDouble()))
        val octant = kotlin.math.floor((degrees + 22.5 + 360.0) % 360.0 / 45.0).toInt()
        return when (octant) {
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

    Box(
        modifier = Modifier
            .offset(x = touchLeft, y = touchTop)
            .size(width = touchWidth, height = touchHeight)
            .pointerInput(control.id) {
                awaitEachGesture {
                    var currentActions = emptySet<GameAction>()

                    fun updateActions(nextActions: Set<GameAction>) {
                        if (nextActions == currentActions) return
                        reducer.clearSource(source)
                        nextActions.forEach { action ->
                            reducer.press(source, action)
                            when (action) {
                                GameAction.UP -> reducer.pressKeyCode(source, 19)
                                GameAction.DOWN -> reducer.pressKeyCode(source, 20)
                                GameAction.LEFT -> reducer.pressKeyCode(source, 21)
                                GameAction.RIGHT -> reducer.pressKeyCode(source, 22)
                                else -> Unit
                            }
                        }
                        currentActions = nextActions
                        activeActions = nextActions
                        onSnapshotChanged(reducer.snapshot())
                    }

                    try {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val dPadLeftPx = (placement.x - touchLeft).toPx()
                        val dPadTopPx = (placement.y - touchTop).toPx()
                        val dPadSizePx = dPadSize.toPx()
                        updateActions(
                            actionsForTouchInBox(
                                down.position.x,
                                down.position.y,
                                dPadLeftPx,
                                dPadTopPx,
                                dPadSizePx,
                            )
                        )
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            updateActions(
                                actionsForTouchInBox(
                                    change.position.x,
                                    change.position.y,
                                    dPadLeftPx,
                                    dPadTopPx,
                                    dPadSizePx,
                                )
                            )
                        }
                    } finally {
                        reducer.clearSource(source)
                        activeActions = emptySet()
                        onSnapshotChanged(reducer.snapshot())
                    }
                }
            },
    ) {
        val playAlpha = control.opacity.coerceIn(.2f, 1f)
        Surface(
            modifier = Modifier
                .offset(x = placement.x - touchLeft, y = placement.y - touchTop)
                .size(dPadSize),
            shape = CircleShape,
            color = Color(0xFF12141C).copy(alpha = playAlpha),
            contentColor = Color.White.copy(alpha = playAlpha.coerceAtLeast(0.55f)),
            border = BorderStroke(
                1.dp,
                if (activeActions.isNotEmpty()) Color(0xFF38BDF8).copy(alpha = 0.6f * playAlpha)
                else Color.White.copy(alpha = 0.12f * playAlpha),
            ),
            tonalElevation = 2.dp,
            shadowElevation = 6.dp,
        ) {
            DPadFace(activeActions = activeActions, opacity = playAlpha)
        }
    }
}

@Composable
private fun DPadFace(
    activeActions: Set<GameAction>,
    opacity: Float = 1f,
) {
    val faceAlpha = opacity.coerceIn(.2f, 1f)
    val idleButtonColor = Color.White.copy(alpha = 0.09f * faceAlpha)
    val idleBorderColor = Color.White.copy(alpha = 0.18f * faceAlpha)
    val activeButtonColor = Color(0xFF0284C7).copy(alpha = 0.92f * faceAlpha)
    val activeGlowColor = Color(0xFF38BDF8).copy(alpha = faceAlpha)

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val d = size.minDimension
            val cx = center.x
            val cy = center.y

            // Diagonal corner reference dots (45, 135, 225, 315 deg)
            val diagDist = d * 0.35f
            val dotRadius = 2.dp.toPx()
            for (angle in listOf(45.0, 135.0, 225.0, 315.0)) {
                val rad = Math.toRadians(angle)
                val dotX = cx + (diagDist * kotlin.math.cos(rad)).toFloat()
                val dotY = cy + (diagDist * kotlin.math.sin(rad)).toFloat()
                drawCircle(
                    color = Color.White.copy(alpha = 0.22f * faceAlpha),
                    radius = dotRadius,
                    center = Offset(dotX, dotY),
                )
            }

            // Button geometry: 4 distinct, sculpted directional keys that NEVER overlap!
            val hubRadius = d * 0.15f
            val btnWidth = d * 0.27f
            val btnReach = d * 0.45f
            val btnLength = btnReach - hubRadius
            val corner = CornerRadius(btnWidth * 0.28f, btnWidth * 0.28f)

            fun drawButton(left: Float, top: Float, width: Float, height: Float, active: Boolean) {
                val fillColor = if (active) activeButtonColor else idleButtonColor
                val strokeColor = if (active) activeGlowColor else idleBorderColor
                val strokeW = if (active) 2.dp.toPx() else 1.dp.toPx()

                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = strokeColor,
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    cornerRadius = corner,
                    style = Stroke(width = strokeW),
                )
            }

            // UP button
            val isUp = GameAction.UP in activeActions
            drawButton(
                left = cx - btnWidth / 2f,
                top = cy - btnReach,
                width = btnWidth,
                height = btnLength,
                active = isUp,
            )
            // DOWN button
            val isDown = GameAction.DOWN in activeActions
            drawButton(
                left = cx - btnWidth / 2f,
                top = cy + hubRadius,
                width = btnWidth,
                height = btnLength,
                active = isDown,
            )
            // LEFT button
            val isLeft = GameAction.LEFT in activeActions
            drawButton(
                left = cx - btnReach,
                top = cy - btnWidth / 2f,
                width = btnLength,
                height = btnWidth,
                active = isLeft,
            )
            // RIGHT button
            val isRight = GameAction.RIGHT in activeActions
            drawButton(
                left = cx + hubRadius,
                top = cy - btnWidth / 2f,
                width = btnLength,
                height = btnWidth,
                active = isRight,
            )

            // Draw crisp chevrons on each button
            val btnCenterDist = (hubRadius + btnReach) / 2f
            val chevronArm = 6.5.dp.toPx()

            fun drawChevron(midX: Float, midY: Float, angleDeg: Float, active: Boolean) {
                val strokeW = (if (active) 3.dp else 2.2.dp).toPx()
                val color = if (active) Color.White else Color.White.copy(alpha = 0.85f)
                rotate(angleDeg, pivot = Offset(midX, midY)) {
                    val path = Path().apply {
                        moveTo(midX - chevronArm, midY + chevronArm * 0.45f)
                        lineTo(midX, midY - chevronArm * 0.45f)
                        lineTo(midX + chevronArm, midY + chevronArm * 0.45f)
                    }
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(
                            width = strokeW,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
            }

            drawChevron(cx, cy - btnCenterDist, 0f, isUp)
            drawChevron(cx + btnCenterDist, cy, 90f, isRight)
            drawChevron(cx, cy + btnCenterDist, 180f, isDown)
            drawChevron(cx - btnCenterDist, cy, 270f, isLeft)

            // Recessed center hub
            drawCircle(
                color = Color(0x9008090E),
                radius = hubRadius * 0.85f,
                center = Offset(cx, cy),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.15f),
                radius = hubRadius * 0.85f,
                center = Offset(cx, cy),
                style = Stroke(width = 1.dp.toPx()),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.35f),
                radius = 2.dp.toPx(),
                center = Offset(cx, cy),
            )
        }
    }
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

/**
 * Maps an overlay-local touch into a 0..1 fraction of the shared runtime surface.
 * [pageOffsetXPx]/[pageOffsetYPx] correct Popup/window origin drift versus the WebView.
 */
internal fun normalizeOverlayPointer(
    localX: Float,
    localY: Float,
    overlayWidthPx: Int,
    overlayHeightPx: Int,
    pageOffsetXPx: Float = 0f,
    pageOffsetYPx: Float = 0f,
): Pair<Float, Float> {
    val width = overlayWidthPx.coerceAtLeast(1).toFloat()
    val height = overlayHeightPx.coerceAtLeast(1).toFloat()
    val x = ((localX + pageOffsetXPx) / width).coerceIn(0f, 1f)
    val y = ((localY + pageOffsetYPx) / height).coerceIn(0f, 1f)
    return x to y
}

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
