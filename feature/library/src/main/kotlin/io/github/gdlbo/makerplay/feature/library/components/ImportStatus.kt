package io.github.gdlbo.makerplay.feature.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.gdlbo.makerplay.feature.importer.ImportPhase
import io.github.gdlbo.makerplay.feature.importer.ImportUiState
import io.github.gdlbo.makerplay.feature.library.R

@Composable
internal fun ImportStatus(state: ImportUiState, onCancel: () -> Unit) {
    when (state) {
        ImportUiState.Idle -> Unit
        is ImportUiState.Running -> Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val progress = state.progress
                val fraction = when (progress.phase) {
                    ImportPhase.COPYING if progress.totalBytes > 0 -> {
                        (progress.copiedBytes.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
                    }

                    ImportPhase.COPYING if progress.totalFiles > 0 -> {
                        (progress.copiedFiles.toFloat() / progress.totalFiles).coerceIn(0f, 1f)
                    }

                    else -> null
                }
                Text(
                    when (progress.phase) {
                        ImportPhase.SCANNING -> stringResource(
                            R.string.scanning_game_files,
                            progress.copiedFiles,
                        )

                        ImportPhase.COPYING -> stringResource(
                            R.string.importing_files,
                            ((fraction ?: 0f) * 100).toInt(),
                            progress.copiedFiles,
                            progress.totalFiles,
                        )

                        ImportPhase.FINALIZING -> stringResource(R.string.finalizing_game_import)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (fraction == null) LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(50)),
                )
                else LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(50)),
                )
                HorizontalDivider()
                TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.cancel_import))
                }
            }
        }

        is ImportUiState.Succeeded -> Text(
            stringResource(R.string.import_completed),
            color = MaterialTheme.colorScheme.primary,
        )

        is ImportUiState.Failed -> Text(state.userMessage, color = MaterialTheme.colorScheme.error)
    }
}