package io.github.gdlbo.makerplay.feature.player.runtime.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.gdlbo.makerplay.feature.player.R
import io.github.gdlbo.makerplay.feature.player.controller.model.ControllerMode

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
internal fun RuntimePreparing(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text(
            text = stringResource(R.string.preparing_runtime),
        )
      }
    }
}

@Composable
internal fun PlayerToolbar(
    showControls: Boolean,
    editControls: Boolean,
    cheatsAvailable: Boolean,
    layoutLoaded: Boolean,
    controllerMode: ControllerMode,
    onToggleControls: () -> Unit,
    onToggleEditing: () -> Unit,
    onOpenCheats: () -> Unit,
    onSwitchMode: () -> Unit,
    onRestart: () -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = expanded) { expanded = false }

    val pillAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded || editControls) 1.0f else 0.35f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 200),
        label = "pillAlpha",
    )

    Surface(
        modifier = modifier
            .safeDrawingPadding()
            .padding(top = 8.dp)
            .zIndex(3f)
            .graphicsLayer { alpha = pillAlpha },
        shape = CircleShape,
        color = Color(0xEE14161E),
        border = BorderStroke(
            1.dp,
            if (editControls) Color(0xFF38BDF8).copy(alpha = 0.8f) else Color.White.copy(alpha = 0.14f),
        ),
        shadowElevation = 6.dp,
        contentColor = Color.White,
    ) {
        AnimatedContent(
            targetState = expanded,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "PlayerToolbarExpansion",
        ) { isExpanded ->
            if (!isExpanded) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { expanded = true }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        when {
                            editControls -> Icons.Default.Edit
                            controllerMode == ControllerMode.KEYBOARD -> Icons.Default.Keyboard
                            !showControls -> Icons.Default.VisibilityOff
                            else -> Icons.Default.SportsEsports
                        },
                        contentDescription = stringResource(R.string.player_tools_show),
                        modifier = Modifier.size(18.dp),
                        tint = if (editControls) Color(0xFF38BDF8) else Color.White,
                    )
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.White.copy(alpha = 0.7f),
                    )
                }
            } else {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Exit to Library
                    ToolbarIconButton(
                        onClick = onBack,
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.exit_to_library),
                    )

                    // Restart Game
                    ToolbarIconButton(
                        onClick = onRestart,
                        icon = Icons.Default.RestartAlt,
                        contentDescription = stringResource(R.string.restart_game),
                    )

                    ToolbarDivider()

                    // Controls visibility
                    ToolbarIconButton(
                        onClick = onToggleControls,
                        icon = if (showControls) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = stringResource(
                            if (showControls) R.string.controller_hide_controls else R.string.controller_show_controls,
                        ),
                        active = !showControls,
                        activeColor = Color(0x33F59E0B),
                        activeContentColor = Color(0xFFFBBF24),
                    )

                    // Edit button layout
                    ToolbarIconButton(
                        onClick = onToggleEditing,
                        enabled = layoutLoaded,
                        icon = if (editControls) Icons.Default.Check else Icons.Default.Edit,
                        contentDescription = stringResource(
                            if (editControls) R.string.controller_finish_editing else R.string.controller_edit,
                        ),
                        active = editControls,
                        activeColor = Color(0xFF0284C7),
                        activeContentColor = Color.White,
                    )

                    // Mode switch (Gamepad <-> Keyboard)
                    ToolbarIconButton(
                        onClick = onSwitchMode,
                        enabled = layoutLoaded && !editControls,
                        icon = if (controllerMode == ControllerMode.GAMEPAD) Icons.Default.Keyboard else Icons.Default.SportsEsports,
                        contentDescription = stringResource(R.string.controller_switch_mode),
                        active = controllerMode == ControllerMode.KEYBOARD,
                        activeColor = Color(0xFF4F46E5),
                        activeContentColor = Color.White,
                    )

                    ToolbarDivider()

                    // Cheats (if supported)
                    if (cheatsAvailable) {
                        ToolbarIconButton(
                            onClick = onOpenCheats,
                            icon = Icons.Default.AutoAwesome,
                            contentDescription = stringResource(R.string.cheats),
                            tint = Color(0xFFFFD54F),
                        )
                    }


                    // Collapse
                    ToolbarIconButton(
                        onClick = { expanded = false },
                        icon = Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.player_tools_hide),
                        tint = Color.White.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolbarIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    activeColor: Color = MaterialTheme.colorScheme.primaryContainer,
    activeContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    tint: Color? = null,
) {
    val containerModifier = if (active) {
        modifier
            .clip(CircleShape)
            .background(activeColor)
    } else {
        modifier
    }

    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = containerModifier.size(38.dp),
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(19.dp),
            tint = when {
                !enabled -> LocalContentColor.current.copy(alpha = 0.38f)
                active -> activeContentColor
                tint != null -> tint
                else -> Color.White
            },
        )
    }
}

@Composable
private fun ToolbarDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .width(1.dp)
            .height(18.dp)
            .background(Color.White.copy(alpha = 0.15f)),
    )
}

@Composable
internal fun PlayerBackButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .safeDrawingPadding()
            .padding(top = 8.dp, end = 12.dp)
            .zIndex(3f),
        shape = CircleShape,
        color = Color(0xEE14161E),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
        shadowElevation = 6.dp,
        contentColor = Color.White,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(38.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.navigate_back),
                modifier = Modifier.size(18.dp),
                tint = Color.White,
            )
        }
    }
}