package io.github.gdlbo.makerplay.feature.player.controller.ui

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.gdlbo.makerplay.feature.player.R
import io.github.gdlbo.makerplay.input.GameAction
import io.github.gdlbo.makerplay.input.VirtualControl
import io.github.gdlbo.makerplay.input.VirtualControlShape
import io.github.gdlbo.makerplay.input.VirtualControlType
import io.github.gdlbo.makerplay.input.VirtualControllerProfile
import io.github.gdlbo.makerplay.input.VirtualControllerProfileValidator
import kotlinx.coroutines.awaitCancellation
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun ControllerEditorPanel(
    profile: VirtualControllerProfile,
    selectedId: String?,
    onProfileChanged: (VirtualControllerProfile) -> Unit,
    onSelected: (String?) -> Unit,
    onResetProfile: () -> Unit,
    onSaveAsDefault: (() -> Unit)? = null,
    onKeyCapture: (((Int) -> Unit)?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val selectedIndex =
        profile.controls.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0
    val selected = profile.controls.getOrNull(selectedIndex) ?: return
    var expanded by rememberSaveable(profile.id) { mutableStateOf(true) }
    var showResetDialog by rememberSaveable(profile.id) { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable(profile.id) { mutableStateOf(false) }
    val controlListState = rememberLazyListState()
    val propertyScrollState = rememberScrollState()
    var dragX by rememberSaveable { mutableFloatStateOf(0f) }
    var dragY by rememberSaveable { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val panelOffset = Modifier.offset { IntOffset(dragX.roundToInt(), dragY.roundToInt()) }
    val moveBy = { amount: Offset ->
        dragX += amount.x
        dragY += amount.y
    }

    LaunchedEffect(selected.id, profile.controls.size) {
        controlListState.animateScrollToItem(selectedIndex)
        propertyScrollState.scrollTo(0)
    }

    val addControl = {
        val id = generateCustomId(profile)
        val control = VirtualControl(
            id = id, type = VirtualControlType.BUTTON, action = GameAction.OK,
            x = .445f, y = .42f, width = .08f, height = .12f, opacity = .82f,
            shape = VirtualControlShape.CIRCLE, color = 0xFF25272B.toInt(),
        )
        onProfileChanged(
            profile.copy(controls = profile.controls + control)
                .also(VirtualControllerProfileValidator::validate)
        )
        onSelected(id)
    }
    val deleteControl = {
        val remaining = profile.controls.filterNot { it.id == selected.id }
        val nextIndex = selectedIndex.coerceAtMost(remaining.lastIndex)
        onProfileChanged(
            profile.copy(controls = remaining).also(VirtualControllerProfileValidator::validate)
        )
        onSelected(remaining.getOrNull(nextIndex)?.id)
    }

    Box(modifier.then(panelOffset)) {
        AnimatedVisibility(
            visible = !expanded,
            enter = fadeIn(tween(160)) + scaleIn(tween(180), initialScale = 0.86f),
            exit = fadeOut(tween(120)) + scaleOut(tween(140), targetScale = 0.86f),
        ) {
            Surface(
                modifier = Modifier.dragToMove(
                    onStart = { dragging = true },
                    onEnd = { dragging = false },
                    onDrag = moveBy,
                ),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 3.dp,
                shadowElevation = if (dragging) 8.dp else 3.dp,
            ) {
                IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Tune, stringResource(R.string.controller_editor_expand))
                }
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(180)),
        ) {
            Surface(
                modifier = Modifier.animateContentSize(tween(180)),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 1.dp,
                shadowElevation = 0.dp,
            ) {
                Column {
                    EditorHeader(
                        selected = selected,
                        selectedIndex = selectedIndex,
                        controlCount = profile.controls.size,
                        canAdd = profile.controls.size < VirtualControllerProfileValidator.MAX_CONTROLS,
                        canDelete = profile.controls.size > 1,
                        onAdd = addControl,
                        onDelete = { showDeleteDialog = true },
                        onCollapse = { expanded = false },
                        onReset = { showResetDialog = true },
                        onSaveAsDefault = onSaveAsDefault,
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDrag = moveBy,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(propertyScrollState)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ControlPicker(profile.controls, selected.id, controlListState, onSelected)
                        if (selected.type == VirtualControlType.D_PAD) DPadSummary()
                        else ButtonOptions(profile, selected, onProfileChanged, onKeyCapture)
                        ControlSliders(profile, selected, onProfileChanged)
                        PositionSliders(profile, selected, onProfileChanged)
                    }
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            icon = { Icon(Icons.Default.RestartAlt, null) },
            title = { Text(stringResource(R.string.controller_reset_title)) },
            text = { Text(stringResource(R.string.controller_reset_message)) },
            confirmButton = {
                Button(onClick = {
                    showResetDialog = false; onResetProfile()
                }) { Text(stringResource(R.string.controller_reset_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(
                        stringResource(R.string.controller_reset_cancel)
                    )
                }
            },
        )
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.DeleteOutline, null) },
            title = { Text(stringResource(R.string.controller_delete_title)) },
            text = { Text(stringResource(R.string.controller_delete_message)) },
            confirmButton = {
                Button(onClick = {
                    showDeleteDialog = false; deleteControl()
                }) { Text(stringResource(R.string.controller_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(
                        stringResource(R.string.controller_reset_cancel)
                    )
                }
            },
        )
    }
}

@Composable
private fun EditorHeader(
    selected: VirtualControl,
    selectedIndex: Int,
    controlCount: Int,
    canAdd: Boolean,
    canDelete: Boolean,
    onAdd: () -> Unit,
    onDelete: () -> Unit,
    onCollapse: () -> Unit,
    onReset: () -> Unit,
    onSaveAsDefault: (() -> Unit)? = null,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDrag: (Offset) -> Unit,
) {
    var overflowOpen by rememberSaveable(selected.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .dragToMove(onDragStart, onDragEnd, onDrag),
        ) {
            Text(
                stringResource(R.string.controller_editor_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${controlDisplayLabel(selected)} · " + stringResource(
                    R.string.controller_button_position, selectedIndex + 1, controlCount,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(enabled = canAdd, onClick = onAdd, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Add, stringResource(R.string.controller_add_button))
        }
        Box {
            IconButton(onClick = { overflowOpen = true }, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.MoreVert,
                    stringResource(R.string.controller_editor_more_actions)
                )
            }
            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.controller_reset_layout)) },
                    leadingIcon = { Icon(Icons.Default.RestartAlt, null) },
                    onClick = { overflowOpen = false; onReset() },
                )
                if (onSaveAsDefault != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.controller_save_as_default)) },
                        leadingIcon = { Icon(Icons.Default.BookmarkAdd, null) },
                        onClick = { overflowOpen = false; onSaveAsDefault() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.controller_delete)) },
                    leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, enabled = canDelete,
                    onClick = { overflowOpen = false; onDelete() },
                )
            }
        }
        IconButton(onClick = onCollapse, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                stringResource(R.string.controller_editor_collapse)
            )
        }
    }
}

@Composable
private fun ControlPicker(
    controls: List<VirtualControl>,
    selectedId: String,
    state: LazyListState,
    onSelected: (String?) -> Unit,
) {
    LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        itemsIndexed(controls, key = { _, control -> control.id }) { _, control ->
            val selected = control.id == selectedId
            Surface(
                onClick = { onSelected(control.id) },
                modifier = Modifier
                    .height(40.dp)
                    .widthIn(min = 48.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                border = if (selected) BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary
                ) else null,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        controlDisplayLabel(control), style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun DPadSummary() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.Gamepad, null, modifier = Modifier.size(20.dp))
            Text(
                stringResource(R.string.controller_dpad),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun ButtonOptions(
    profile: VirtualControllerProfile,
    selected: VirtualControl,
    onProfileChanged: (VirtualControllerProfile) -> Unit,
    onKeyCapture: (((Int) -> Unit)?) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionSelector(profile, selected, onProfileChanged, Modifier.weight(1f))
        KeyBindButton(profile, selected, onProfileChanged, onKeyCapture, Modifier.weight(1f))
        ShapeSelector(profile, selected, onProfileChanged)
    }
}

@Composable
private fun KeyBindButton(
    profile: VirtualControllerProfile,
    selected: VirtualControl,
    onProfileChanged: (VirtualControllerProfile) -> Unit,
    onKeyCapture: (((Int) -> Unit)?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by rememberSaveable(selected.id) { mutableStateOf(false) }
    val bind = { keyCode: Int ->
        showPicker = false
        onProfileChanged(profile.update(controlBoundToKey(selected, keyCode)))
    }
    LaunchedEffect(showPicker, selected.id) {
        if (!showPicker) {
            onKeyCapture(null)
            return@LaunchedEffect
        }
        try {
            onKeyCapture(bind)
            awaitCancellation()
        } finally {
            onKeyCapture(null)
        }
    }
    val keyLabel = if (selected.keyCode != null) {
        controlDisplayLabel(selected)
    } else {
        stringResource(R.string.controller_key_unset)
    }
    OutlinedButton(
        onClick = { showPicker = true },
        modifier = modifier.height(40.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
    ) {
        Text(
            keyLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (showPicker) {
        KeyPickerDialog(
            selected = selected,
            onDismiss = { showPicker = false },
            onKeySelected = bind,
            onClear = {
                showPicker = false
                onProfileChanged(profile.update(selected.copy(keyCode = null, label = null)))
            },
        )
    }
}

@Composable
private fun KeyPickerDialog(
    selected: VirtualControl,
    onDismiss: () -> Unit,
    onKeySelected: (Int) -> Unit,
    onClear: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.controller_choose_key),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                KeyRow("QWERTYUIOP", selected, onKeySelected)
                KeyRow("ASDFGHJKL", selected, onKeySelected)
                KeyRow("ZXCVBNM", selected, onKeySelected)
                KeyRow("0123456789", selected, onKeySelected)
                KeyRow("⇧ ⌃ ⌥ ␣ Enter", selected, onKeySelected)
                KeyRow("Tab Esc Menu ⌫", selected, onKeySelected)
                KeyRow("↑ ↓ ← →", selected, onKeySelected)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.controller_reset_cancel)) }
        },
        dismissButton = {
            TextButton(onClick = onClear) { Text(stringResource(R.string.controller_key_unset)) }
        },
    )
}

@Composable
private fun KeyRow(symbols: String, selected: VirtualControl, onKeySelected: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        symbols.split(" ").filter(String::isNotEmpty).forEach { symbol ->
            KeyCap(
                symbol = symbol,
                selected = selected,
                onKeySelected = onKeySelected,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun KeyCap(
    symbol: String,
    selected: VirtualControl,
    onKeySelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyCode = keyCodeForSymbol(symbol) ?: return
    val chosen = selected.keyCode == keyCode
    Surface(
        onClick = { onKeySelected(keyCode) },
        modifier = modifier.height(40.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        tonalElevation = if (chosen) 0.dp else 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                symbol,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

private fun keyCodeForSymbol(symbol: String): Int? {
    if (symbol.length == 1) {
        val letter = symbol[0]
        if (letter in 'A'..'Z') return KeyEvent.KEYCODE_A + (letter - 'A')
        if (letter in '0'..'9') return KeyEvent.KEYCODE_0 + (letter - '0')
    }
    return when (symbol) {
        "⇧" -> KeyEvent.KEYCODE_SHIFT_LEFT
        "⌃" -> KeyEvent.KEYCODE_CTRL_LEFT
        "⌥" -> KeyEvent.KEYCODE_ALT_LEFT
        "␣" -> KeyEvent.KEYCODE_SPACE
        "Tab" -> KeyEvent.KEYCODE_TAB
        "Esc" -> KeyEvent.KEYCODE_ESCAPE
        "Menu" -> KeyEvent.KEYCODE_MENU
        "⌫" -> KeyEvent.KEYCODE_DEL
        "↑" -> KeyEvent.KEYCODE_DPAD_UP
        "↓" -> KeyEvent.KEYCODE_DPAD_DOWN
        "←" -> KeyEvent.KEYCODE_DPAD_LEFT
        "→" -> KeyEvent.KEYCODE_DPAD_RIGHT
        "Enter" -> KeyEvent.KEYCODE_ENTER
        else -> null
    }
}

internal fun controlBoundToKey(control: VirtualControl, keyCode: Int): VirtualControl {
    val code = if (keyCode == KeyEvent.KEYCODE_BACK) KeyEvent.KEYCODE_ESCAPE else keyCode
    val action = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> GameAction.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> GameAction.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> GameAction.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> GameAction.RIGHT
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_Z -> GameAction.OK
        KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_X -> GameAction.ESCAPE
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> GameAction.SHIFT
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> GameAction.CONTROL
        KeyEvent.KEYCODE_TAB -> GameAction.TAB
        KeyEvent.KEYCODE_MENU -> GameAction.MENU
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_Q -> GameAction.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_W -> GameAction.PAGE_DOWN
        else -> control.action
    }
    return control.copy(keyCode = code, action = action, label = null)
}

@Composable
private fun ActionSelector(
    profile: VirtualControllerProfile,
    selected: VirtualControl,
    onProfileChanged: (VirtualControllerProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showActionPicker by rememberSaveable(selected.id) { mutableStateOf(false) }
    OutlinedButton(
        onClick = { showActionPicker = true },
        modifier = modifier.height(40.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
    ) { Text(gameActionLabel(selected.action), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    if (showActionPicker) {
        ActionPickerDialog(
            selectedAction = selected.action, onDismiss = { showActionPicker = false },
            onActionSelected = { action ->
                showActionPicker = false
                onProfileChanged(
                    profile.update(
                        selected.copy(
                            action = action,
                            label = null,
                            keyCode = null
                        )
                    )
                )
            },
        )
    }
}

@Composable
private fun ActionPickerDialog(
    selectedAction: GameAction,
    onDismiss: () -> Unit,
    onActionSelected: (GameAction) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.controller_choose_action)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EditableActions.forEach { action ->
                    val selected = action == selectedAction
                    if (selected) Button(
                        onClick = { onActionSelected(action) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(gameActionLabel(action))
                    } else OutlinedButton(
                        onClick = { onActionSelected(action) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(gameActionLabel(action))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.controller_reset_cancel)) } },
    )
}

@Composable
private fun ShapeSelector(
    profile: VirtualControllerProfile,
    selected: VirtualControl,
    onProfileChanged: (VirtualControllerProfile) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ShapeIcon(
            selected = selected.shape == VirtualControlShape.CIRCLE,
            icon = Icons.Default.RadioButtonUnchecked,
            description = stringResource(R.string.controller_shape_round),
            onClick = { onProfileChanged(profile.update(selected.copy(shape = VirtualControlShape.CIRCLE))) },
        )
        ShapeIcon(
            selected = selected.shape == VirtualControlShape.ROUNDED_RECTANGLE,
            icon = Icons.Default.CropSquare,
            description = stringResource(R.string.controller_shape_rectangle),
            onClick = { onProfileChanged(profile.update(selected.copy(shape = VirtualControlShape.ROUNDED_RECTANGLE))) },
        )
    }
}

@Composable
private fun ShapeIcon(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, description, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ControlSliders(
    profile: VirtualControllerProfile,
    selected: VirtualControl,
    onProfileChanged: (VirtualControllerProfile) -> Unit
) {
    val aspectRatio = selected.height / selected.width
    val maximumDimension = if (selected.type == VirtualControlType.D_PAD) .5f else .35f
    val minimumWidth = max(.04f, .02f / aspectRatio)
    val maximumWidth = min(maximumDimension, maximumDimension / aspectRatio)
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        SizeSlider(profile, selected, minimumWidth, maximumWidth, aspectRatio, onProfileChanged)
        OpacitySlider(profile, selected, onProfileChanged)
    }
}

@Composable
private fun PositionSliders(
    profile: VirtualControllerProfile,
    selected: VirtualControl,
    onProfileChanged: (VirtualControllerProfile) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        ControlSlider(
            R.string.controller_position_x,
            "${(selected.x * 100).roundToInt()}%",
            selected.x,
            0f..(1f - selected.width),
            onValueChange = { x -> onProfileChanged(profile.update(selected.copy(x = x))) })
        ControlSlider(
            R.string.controller_position_y,
            "${(selected.y * 100).roundToInt()}%",
            selected.y,
            0f..(1f - selected.height),
            onValueChange = { y -> onProfileChanged(profile.update(selected.copy(y = y))) })
    }
}

@Composable
private fun SizeSlider(
    profile: VirtualControllerProfile,
    selected: VirtualControl,
    minimumWidth: Float,
    maximumWidth: Float,
    aspectRatio: Float,
    onProfileChanged: (VirtualControllerProfile) -> Unit
) {
    ControlSlider(
        R.string.controller_size,
        "${(selected.width * 100).roundToInt()}%",
        selected.width.coerceIn(minimumWidth, maximumWidth),
        minimumWidth..maximumWidth,
        onValueChange = { width ->
            onProfileChanged(
                profile.update(
                    selected.copy(
                        width = width,
                        height = width * aspectRatio
                    )
                )
            )
        },
    )
}

@Composable
private fun OpacitySlider(
    profile: VirtualControllerProfile,
    selected: VirtualControl,
    onProfileChanged: (VirtualControllerProfile) -> Unit
) {
    ControlSlider(
        R.string.controller_opacity,
        "${(selected.opacity * 100).roundToInt()}%",
        selected.opacity,
        .2f..1f,
        onValueChange = { value -> onProfileChanged(profile.update(selected.copy(opacity = value))) })
}

@Composable
private fun ControlSlider(
    labelRes: Int,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(labelRes),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                valueLabel,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
    }
}

private fun Modifier.dragToMove(
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onDrag: (Offset) -> Unit,
): Modifier = pointerInput(Unit) {
    detectDragGestures(
        onDragStart = { onStart() },
        onDragEnd = { onEnd() },
        onDragCancel = { onEnd() },
    ) { change, amount ->
        change.consume()
        onDrag(amount)
    }
}

private fun generateCustomId(profile: VirtualControllerProfile): String =
    generateSequence(1) { it + 1 }
        .map { "custom-$it" }.first { candidate -> profile.controls.none { it.id == candidate } }

private fun VirtualControllerProfile.update(control: VirtualControl): VirtualControllerProfile =
    copy(
        controls = controls.map {
            if (it.id == control.id) control.copy(
                x = control.x.coerceIn(0f, 1f - control.width),
                y = control.y.coerceIn(0f, 1f - control.height),
            ) else it
        },
    ).also(VirtualControllerProfileValidator::validate)

private val EditableActions = GameAction.entries.filterNot {
    it == GameAction.POINTER_DOWN || it == GameAction.POINTER_MOVE || it == GameAction.POINTER_UP
}
