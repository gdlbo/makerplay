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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = expanded) { expanded = false }

    Surface(
        modifier = modifier
            .safeDrawingPadding()
            .padding(12.dp)
            .zIndex(3f),
        shape = MaterialTheme.shapes.large,
        color = Color.Black.copy(alpha = .68f),
        contentColor = Color.White,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    if (expanded) Icons.Default.Close else Icons.Default.MoreVert,
                    stringResource(if (expanded) R.string.player_tools_hide else R.string.player_tools_show),
                )
            }
            if (expanded) {
                IconButton(onClick = onToggleControls, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (showControls) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        stringResource(if (showControls) R.string.controller_hide_controls else R.string.controller_show_controls),
                    )
                }
                IconButton(
                    onClick = onToggleEditing,
                    enabled = layoutLoaded,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        if (editControls) Icons.Default.Done else Icons.Default.Edit,
                        stringResource(if (editControls) R.string.controller_finish_editing else R.string.controller_edit),
                    )
                }
                if (cheatsAvailable) {
                    IconButton(
                        onClick = onOpenCheats,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Default.AutoAwesome, stringResource(R.string.cheats))
                    }
                }
                IconButton(
                    onClick = onSwitchMode,
                    enabled = layoutLoaded && !editControls,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        if (controllerMode == ControllerMode.GAMEPAD) Icons.Default.Keyboard else Icons.Default.SportsEsports,
                        stringResource(R.string.controller_switch_mode),
                    )
                }
            }
        }
    }
}

@Composable
internal fun PlayerBackButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onBack,
        modifier = modifier
            .safeDrawingPadding()
            .padding(12.dp)
            .zIndex(3f),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            stringResource(R.string.navigate_back),
            tint = Color.White
        )
    }
}