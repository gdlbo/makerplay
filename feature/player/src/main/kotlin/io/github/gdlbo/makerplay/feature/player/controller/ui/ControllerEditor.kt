package io.github.gdlbo.makerplay.feature.player.controller.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gdlbo.makerplay.feature.player.R
import io.github.gdlbo.makerplay.input.GameAction
import io.github.gdlbo.makerplay.input.VirtualControl
import io.github.gdlbo.makerplay.input.VirtualControlShape
import io.github.gdlbo.makerplay.input.VirtualControlType
import io.github.gdlbo.makerplay.input.VirtualControllerProfile
import io.github.gdlbo.makerplay.input.VirtualControllerProfileValidator
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val EditorSurfaceColor = Color(0xF0222428)
private val EditorItemColor = Color.White.copy(alpha = .08f)
private val EditorBorderColor = Color.White.copy(alpha = .14f)
private val EditorMutedContent = Color.White.copy(alpha = .68f)

@Composable
internal fun ControllerEditorPanel(
    profile: VirtualControllerProfile,
    selectedId: String?,
    onProfileChanged: (VirtualControllerProfile) -> Unit,
    onSelected: (String?) -> Unit,
    onResetProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedIndex =
        profile.controls.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0
    val selected = profile.controls.getOrNull(selectedIndex) ?: return
    var expanded by rememberSaveable(profile.id) { mutableStateOf(true) }
    var showResetDialog by rememberSaveable(profile.id) { mutableStateOf(false) }
    val controlListState = rememberLazyListState()

    LaunchedEffect(selected.id, profile.controls.size) {
        controlListState.animateScrollToItem(selectedIndex)
    }

    val addControl = {
        val id = generateCustomId(profile)
        val control = VirtualControl(
            id = id,
            type = VirtualControlType.BUTTON,
            action = GameAction.OK,
            x = .445f,
            y = .42f,
            width = .08f,
            height = .12f,
            opacity = .82f,
            shape = VirtualControlShape.CIRCLE,
            color = 0xFF25272B.toInt(),
        )
        onProfileChanged(
            profile.copy(controls = profile.controls + control)
                .also(VirtualControllerProfileValidator::validate),
        )
        onSelected(id)
    }
    val deleteControl = {
        val remaining = profile.controls.filterNot { it.id == selected.id }
        val nextIndex = selectedIndex.coerceAtMost(remaining.lastIndex)
        onProfileChanged(
            profile.copy(controls = remaining)
                .also(VirtualControllerProfileValidator::validate),
        )
        onSelected(remaining.getOrNull(nextIndex)?.id)
    }

    if (!expanded) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(8.dp),
            color = EditorSurfaceColor,
            contentColor = Color.White,
            border = BorderStroke(1.dp, EditorBorderColor),
            shadowElevation = 4.dp,
        ) {
            IconButton(onClick = { expanded = true }, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.Tune, stringResource(R.string.controller_editor_expand))
            }
        }
    } else {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(8.dp),
            color = EditorSurfaceColor,
            contentColor = Color.White,
            border = BorderStroke(1.dp, EditorBorderColor),
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                EditorHeader(
                    selected = selected,
                    selectedIndex = selectedIndex,
                    controlCount = profile.controls.size,
                    canAdd = profile.controls.size < VirtualControllerProfileValidator.MAX_CONTROLS,
                    canDelete = profile.controls.size > 1,
                    onAdd = addControl,
                    onDelete = deleteControl,
                    onCollapse = { expanded = false },
                    onReset = { showResetDialog = true },
                )
                HorizontalDivider(color = EditorBorderColor)
                ControlPicker(
                    controls = profile.controls,
                    selectedId = selected.id,
                    state = controlListState,
                    onSelected = onSelected,
                )
                if (selected.type == VirtualControlType.D_PAD) {
                    DPadSummary()
                } else {
                    ButtonOptions(profile, selected, onProfileChanged)
                }
                ControlSliders(profile, selected, onProfileChanged)
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            icon = { Icon(Icons.Default.Restore, contentDescription = null) },
            title = { Text(stringResource(R.string.controller_reset_title)) },
            text = { Text(stringResource(R.string.controller_reset_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        onResetProfile()
                    },
                ) {
                    Text(stringResource(R.string.controller_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.controller_reset_cancel))
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
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.controller_editor_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = controlDisplayLabel(selected),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.controller_button_position,
                        selectedIndex + 1,
                        controlCount
                    ),
                    color = EditorMutedContent,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        IconButton(onClick = onReset, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Restore, stringResource(R.string.controller_reset_layout))
        }
        IconButton(enabled = canDelete, onClick = onDelete, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.DeleteOutline, stringResource(R.string.controller_delete))
        }
        IconButton(enabled = canAdd, onClick = onAdd, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Add, stringResource(R.string.controller_add_button))
        }
        IconButton(onClick = onCollapse, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.ExpandLess, stringResource(R.string.controller_editor_collapse))
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.controller_button_list),
            color = EditorMutedContent,
            style = MaterialTheme.typography.labelSmall,
        )
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(controls, key = { _, control -> control.id }) { _, control ->
                val selected = control.id == selectedId
                Surface(
                    onClick = { onSelected(control.id) },
                    modifier = Modifier
                        .height(38.dp)
                        .widthIn(min = 44.dp, max = 96.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .32f) else EditorItemColor,
                    contentColor = Color.White,
                    border = BorderStroke(
                        1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else EditorBorderColor,
                    ),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = controlDisplayLabel(control),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DPadSummary() {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = EditorItemColor,
        contentColor = Color.White,
        border = BorderStroke(1.dp, EditorBorderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Gamepad, contentDescription = null, modifier = Modifier.size(20.dp))
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
) {
    BoxWithConstraints {
        if (maxWidth >= 460.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ActionSelector(profile, selected, onProfileChanged, Modifier.weight(1f))
                ShapeSelector(profile, selected, onProfileChanged)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionSelector(profile, selected, onProfileChanged, Modifier.fillMaxWidth())
                ShapeSelector(profile, selected, onProfileChanged)
            }
        }
    }
}

@Composable
private fun ActionSelector(
    profile: VirtualControllerProfile,
    selected: VirtualControl,
    onProfileChanged: (VirtualControllerProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(
            enabled = selected.keyCode == null,
            onClick = {
                onProfileChanged(
                    profile.update(
                        selected.copy(
                            action = selected.action.previousEditable(),
                            label = null
                        )
                    )
                )
            },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(Icons.Default.ChevronLeft, stringResource(R.string.controller_previous_action))
        }
        Surface(
            modifier = Modifier
                .weight(1f)
                .height(40.dp),
            shape = RoundedCornerShape(6.dp),
            color = EditorItemColor,
            contentColor = Color.White,
            border = BorderStroke(1.dp, EditorBorderColor),
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (selected.keyCode != null) controlDisplayLabel(selected)
                    else gameActionLabel(selected.action),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(
            enabled = selected.keyCode == null,
            onClick = {
                onProfileChanged(
                    profile.update(
                        selected.copy(
                            action = selected.action.nextEditable(),
                            label = null
                        )
                    )
                )
            },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(Icons.Default.ChevronRight, stringResource(R.string.controller_next_action))
        }
    }
}

@Composable
private fun ShapeSelector(
    profile: VirtualControllerProfile,
    selected: VirtualControl,
    onProfileChanged: (VirtualControllerProfile) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ShapeButton(
            selected = selected.shape == VirtualControlShape.CIRCLE,
            onClick = {
                onProfileChanged(profile.update(selected.copy(shape = VirtualControlShape.CIRCLE)))
            },
        ) {
            Icon(
                Icons.Default.RadioButtonUnchecked,
                stringResource(R.string.controller_shape_round)
            )
        }
        ShapeButton(
            selected = selected.shape == VirtualControlShape.ROUNDED_RECTANGLE,
            onClick = {
                onProfileChanged(profile.update(selected.copy(shape = VirtualControlShape.ROUNDED_RECTANGLE)))
            },
        ) {
            Icon(Icons.Default.CropSquare, stringResource(R.string.controller_shape_rectangle))
        }
    }
}

@Composable
private fun ShapeButton(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        shape = RoundedCornerShape(6.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .32f) else EditorItemColor,
        contentColor = if (selected) MaterialTheme.colorScheme.primary else Color.White,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else EditorBorderColor
        ),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun ControlSliders(
    profile: VirtualControllerProfile,
    selected: VirtualControl,
    onProfileChanged: (VirtualControllerProfile) -> Unit,
) {
    val aspectRatio = selected.height / selected.width
    val maximumDimension = if (selected.type == VirtualControlType.D_PAD) .5f else .35f
    val minimumWidth = max(.04f, .02f / aspectRatio)
    val maximumWidth = min(maximumDimension, maximumDimension / aspectRatio)

    BoxWithConstraints {
        if (maxWidth >= 420.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SizeSlider(
                    profile,
                    selected,
                    minimumWidth,
                    maximumWidth,
                    aspectRatio,
                    onProfileChanged,
                    Modifier.weight(1f),
                )
                OpacitySlider(profile, selected, onProfileChanged, Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SizeSlider(
                    profile,
                    selected,
                    minimumWidth,
                    maximumWidth,
                    aspectRatio,
                    onProfileChanged
                )
                OpacitySlider(profile, selected, onProfileChanged)
            }
        }
    }
}

@Composable
private fun SizeSlider(
    profile: VirtualControllerProfile,
    selected: VirtualControl,
    minimumWidth: Float,
    maximumWidth: Float,
    aspectRatio: Float,
    onProfileChanged: (VirtualControllerProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    ControlSlider(
        labelRes = R.string.controller_size,
        valueLabel = "${(selected.width * 100).roundToInt()}%",
        value = selected.width.coerceIn(minimumWidth, maximumWidth),
        valueRange = minimumWidth..maximumWidth,
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
        modifier = modifier,
    )
}

@Composable
private fun OpacitySlider(
    profile: VirtualControllerProfile,
    selected: VirtualControl,
    onProfileChanged: (VirtualControllerProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    ControlSlider(
        labelRes = R.string.controller_opacity,
        valueLabel = "${(selected.opacity * 100).roundToInt()}%",
        value = selected.opacity,
        valueRange = .2f..1f,
        onValueChange = { value -> onProfileChanged(profile.update(selected.copy(opacity = value))) },
        modifier = modifier,
    )
}

@Composable
private fun ControlSlider(
    labelRes: Int,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(labelRes),
                color = EditorMutedContent,
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                valueLabel,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = EditorBorderColor,
            ),
        )
    }
}

private fun generateCustomId(profile: VirtualControllerProfile): String =
    generateSequence(1) { it + 1 }
        .map { "custom-$it" }
        .first { candidate -> profile.controls.none { it.id == candidate } }

private fun VirtualControllerProfile.update(control: VirtualControl): VirtualControllerProfile =
    copy(
        controls = controls.map {
            if (it.id == control.id) {
                control.copy(
                    x = control.x.coerceIn(0f, 1f - control.width),
                    y = control.y.coerceIn(0f, 1f - control.height),
                )
            } else {
                it
            }
        },
    ).also(VirtualControllerProfileValidator::validate)

private val EditableActions = GameAction.entries.filterNot {
    it == GameAction.POINTER_DOWN || it == GameAction.POINTER_MOVE || it == GameAction.POINTER_UP
}

private fun GameAction.nextEditable(): GameAction =
    EditableActions[(EditableActions.indexOf(this) + 1) % EditableActions.size]

private fun GameAction.previousEditable(): GameAction = EditableActions[
    (EditableActions.indexOf(this) - 1 + EditableActions.size) % EditableActions.size
]