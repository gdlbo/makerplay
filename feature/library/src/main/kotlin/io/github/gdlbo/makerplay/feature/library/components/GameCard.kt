package io.github.gdlbo.makerplay.feature.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gdlbo.makerplay.feature.library.R
import io.github.gdlbo.makerplay.model.GameSummary
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GameCard(
    game: GameSummary,
    artwork: File?,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit,
    onSettings: () -> Unit,
    onDelete: () -> Unit,
    onClearWebData: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmClearWebData by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    if (confirmDelete) {
        ModalBottomSheet(
            onDismissRequest = { confirmDelete = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.delete_game_title, game.title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.delete_game_message),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.delete))
                }
                TextButton(
                    onClick = { confirmDelete = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        }
    }
    if (confirmClearWebData) {
        ModalBottomSheet(onDismissRequest = { confirmClearWebData = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.clear_web_data_title, game.title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.clear_web_data_message),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        confirmClearWebData = false
                        onClearWebData()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.clear_web_data))
                }
                TextButton(
                    onClick = { confirmClearWebData = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        }
    }
    Card(
        onClick = onPlay,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column {
            Box {
                GameArtwork(
                    file = artwork,
                    title = game.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                    shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                    contentScale = ContentScale.Fit,
                )
                GameActionsMenu(
                    expanded = menuExpanded,
                    onExpandedChange = { menuExpanded = it },
                    onSettings = onSettings,
                    onClearWebData = { confirmClearWebData = true },
                    onDelete = { confirmDelete = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    game.title,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val engineDisplay = remember(game.engine, game.engineVersion) {
                        val version = game.engineVersion.orEmpty().trim()
                        val name = game.engine.name
                        when {
                            version.isBlank() -> name
                            version.equals(name, ignoreCase = true) -> version
                            version.startsWith(name, ignoreCase = true) -> version
                            version.contains(name, ignoreCase = true) -> version
                            else -> "$name $version"
                        }
                    }
                    MetadataLabel(text = engineDisplay)
                    if (game.plugins.isNotEmpty()) {
                        MetadataLabel(
                            text = game.plugins.size.toString(),
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Extension,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                        )
                    }
                }
                Button(
                    onClick = onPlay,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Text(
                        stringResource(R.string.play),
                        modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun GameActionsMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSettings: () -> Unit,
    onClearWebData: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalIconButton(
        onClick = { onExpandedChange(true) },
        modifier = modifier,
    ) {
        Icon(
            Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.game_actions),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp,
            shadowElevation = 0.dp,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.game_settings)) },
                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                onClick = {
                    onExpandedChange(false)
                    onSettings()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.clear_web_data)) },
                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                onClick = {
                    onExpandedChange(false)
                    onClearWebData()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.delete_game),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    onExpandedChange(false)
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun MetadataLabel(
    text: String,
    leadingIcon: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(
        LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.invoke()
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}