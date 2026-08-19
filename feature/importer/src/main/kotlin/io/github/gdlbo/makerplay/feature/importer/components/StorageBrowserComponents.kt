package io.github.gdlbo.makerplay.feature.importer.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gdlbo.makerplay.feature.importer.GameInstallMode
import io.github.gdlbo.makerplay.feature.importer.R
import java.io.File

@Composable
internal fun InstallModeSelector(
    installMode: GameInstallMode,
    onModeChange: (GameInstallMode) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        GameInstallMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = installMode == mode,
                onClick = { onModeChange(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, GameInstallMode.entries.size),
            ) {
                Text(stringResource(if (mode == GameInstallMode.DIRECT) R.string.direct_mode else R.string.copy_mode))
            }
        }
    }
}

@Composable
internal fun StoragePermissionContent(
    onRequestAccess: () -> Unit,
    onUseSystemPicker: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(88.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Icon(
                Icons.Default.Security,
                contentDescription = null,
                modifier = Modifier.padding(22.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            stringResource(R.string.storage_access_title),
            modifier = Modifier.padding(top = 24.dp),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(R.string.storage_access_description),
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRequestAccess, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.open_storage_settings))
        }
        onUseSystemPicker?.let { openPicker ->
            FilledTonalButton(
                onClick = openPicker,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.use_system_picker))
            }
        }
    }
}

@Composable
internal fun DirectoryRow(directory: File, isRoot: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isRoot) Icons.Default.Storage else Icons.Default.Folder,
                contentDescription = null
            )
            Text(
                directory.name.ifBlank { directory.path },
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}