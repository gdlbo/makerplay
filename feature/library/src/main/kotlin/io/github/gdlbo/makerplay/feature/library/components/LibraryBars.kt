package io.github.gdlbo.makerplay.feature.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.gdlbo.makerplay.feature.importer.ImportUiState
import io.github.gdlbo.makerplay.feature.library.R
import io.github.gdlbo.makerplay.model.GameSummary

import androidx.compose.material3.FilledTonalButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryTopBar(
    games: List<GameSummary>,
    onSettings: () -> Unit,
    showImportButton: Boolean = false,
    importState: ImportUiState = ImportUiState.Idle,
    onImport: () -> Unit = {},
    showRuntimeSmokeTest: Boolean = false,
    onRunSmokeTest: () -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(stringResource(R.string.library_title))
                if (games.isNotEmpty()) {
                    Text(
                        pluralStringResource(R.plurals.game_count, games.size, games.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = {
            if (showImportButton && games.isNotEmpty()) {
                if (showRuntimeSmokeTest) {
                    IconButton(onClick = onRunSmokeTest) {
                        Icon(
                            Icons.Default.Science,
                            contentDescription = stringResource(R.string.run_runtime_smoke_test),
                        )
                    }
                }
                FilledTonalButton(
                    onClick = onImport,
                    enabled = importState !is ImportUiState.Running,
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(
                        stringResource(R.string.import_another_game),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
internal fun LibraryBottomBar(
    hasGames: Boolean,
    importState: ImportUiState,
    onImport: () -> Unit,
    onRunSmokeTest: () -> Unit,
    showRuntimeSmokeTest: Boolean,
    visible: Boolean = true,
) {
    if (!hasGames || !visible) return

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onImport,
                enabled = importState !is ImportUiState.Running,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(stringResource(R.string.import_another_game), Modifier.padding(start = 8.dp))
            }
            if (showRuntimeSmokeTest) {
                TextButton(onClick = onRunSmokeTest, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Science, contentDescription = null)
                    Text(
                        stringResource(R.string.run_runtime_smoke_test),
                        Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}