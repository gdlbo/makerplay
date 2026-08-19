package io.github.gdlbo.makerplay.feature.player.runtime.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.gdlbo.makerplay.feature.player.R

private val RuntimeFailureColorScheme = darkColorScheme(
    background = Color(0xFF101010),
    onBackground = Color(0xFFE8E2E4),
    surface = Color(0xFF101010),
    onSurface = Color(0xFFE8E2E4),
    surfaceContainer = Color(0xFF211F20),
    onSurfaceVariant = Color(0xFFCBC4C6),
    primary = Color(0xFFE8E2E4),
    onPrimary = Color(0xFF201A1C),
    secondaryContainer = Color(0xFF373234),
    onSecondaryContainer = Color(0xFFF0E6E9),
    errorContainer = Color(0xFF5C2023),
    onErrorContainer = Color(0xFFFFDAD9),
)

internal data class RuntimeFailureUi(
    val title: String,
    val reason: String,
    val technicalDetails: String,
    val sessionId: String? = null,
)

internal fun buildRuntimeFailureReport(
    failure: RuntimeFailureUi,
    logs: String,
    technicalDetailsLabel: String,
    logsLabel: String,
    logsUnavailable: String,
): String = buildString {
    appendLine(failure.title)
    appendLine(failure.reason)
    appendLine()
    appendLine(technicalDetailsLabel)
    appendLine(failure.technicalDetails)
    appendLine()
    appendLine(logsLabel)
    append(logs.ifBlank { logsUnavailable })
}

@Composable
internal fun RuntimeFailureScreen(
    failure: RuntimeFailureUi,
    actionsEnabled: Boolean,
    logsCopied: Boolean,
    onRestart: () -> Unit,
    onCopyLogs: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    MaterialTheme(
        colorScheme = RuntimeFailureColorScheme,
        typography = typography,
        shapes = shapes,
    ) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Surface(
                    modifier = Modifier.size(64.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = failure.title,
                    modifier = Modifier.widthIn(max = 560.dp),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = failure.reason,
                    modifier = Modifier.widthIn(max = 620.dp),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(22.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 620.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            stringResource(R.string.technical_details),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(failure.technicalDetails, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(22.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onRestart,
                        enabled = actionsEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text(
                            stringResource(R.string.restart_game),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    FilledTonalButton(
                        onClick = onCopyLogs,
                        enabled = actionsEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            if (logsCopied) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                            contentDescription = null,
                        )
                        Text(
                            stringResource(if (logsCopied) R.string.logs_copied else R.string.copy_logs),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    if (!actionsEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                stringResource(R.string.preparing_crash_report),
                                modifier = Modifier.padding(start = 10.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    TextButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Text(
                            stringResource(R.string.exit_to_library),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}