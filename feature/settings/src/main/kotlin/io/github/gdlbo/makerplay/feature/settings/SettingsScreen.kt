package io.github.gdlbo.makerplay.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gdlbo.makerplay.runtime.api.RuntimeEngineMode
import io.github.gdlbo.makerplay.runtime.api.RuntimeOrientation
import io.github.gdlbo.makerplay.runtime.api.RuntimeScaleMode
import io.github.gdlbo.makerplay.runtime.api.RuntimeSettings
import io.github.gdlbo.makerplay.runtime.api.SUPPORTED_FPS_LIMITS

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    defaultGameFolder: String,
    onDefaultGameFolderChange: (String) -> Unit,
    onChooseDefaultGameFolder: () -> Unit,
    defaultInstallDirect: Boolean,
    onDefaultInstallDirectChange: (Boolean) -> Unit,
    runtimeSettings: RuntimeSettings,
    onRuntimeSettingsChange: (RuntimeSettings) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsSection(stringResource(R.string.appearance_section))
                SettingsCard {
                    ChoiceSetting(
                        title = stringResource(R.string.app_theme),
                        values = ThemeMode.entries,
                        selected = themeMode,
                        label = { mode ->
                            stringResource(
                                when (mode) {
                                    ThemeMode.SYSTEM -> R.string.theme_system
                                    ThemeMode.LIGHT -> R.string.theme_light
                                    ThemeMode.DARK -> R.string.theme_dark
                                },
                            )
                        },
                        onSelected = onThemeModeChange,
                    )
                }

                SettingsSection(stringResource(R.string.library_section))
                SettingsCard {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onChooseDefaultGameFolder),
                        leadingContent = {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                        },
                        headlineContent = {
                            Text(stringResource(R.string.default_game_folder))
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    defaultGameFolder.ifBlank {
                                        stringResource(R.string.default_game_folder_example)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.MiddleEllipsis,
                                )
                                Text(
                                    stringResource(R.string.default_game_folder_description),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (defaultGameFolder.isNotEmpty()) {
                                    IconButton(
                                        onClick = { onDefaultGameFolderChange("") },
                                    ) {
                                        Icon(
                                            Icons.Default.Clear,
                                            contentDescription = stringResource(R.string.clear_default_game_folder),
                                        )
                                    }
                                }
                                SettingsChevron()
                            }
                        },
                    )
                    ChoiceSetting(
                        title = stringResource(R.string.default_import_mode),
                        values = listOf(false, true),
                        selected = defaultInstallDirect,
                        label = { mode ->
                            stringResource(
                                if (mode) R.string.default_direct_mode else R.string.default_copy_mode,
                            )
                        },
                        onSelected = onDefaultInstallDirectChange,
                    )
                }

                RuntimeSettingsOptions(runtimeSettings, onRuntimeSettingsChange)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameSettingsScreen(
    gameTitle: String,
    isWolfGame: Boolean = false,
    useCommonSettings: Boolean,
    commonSettings: RuntimeSettings,
    customSettings: RuntimeSettings,
    onUseCommonSettingsChange: (Boolean) -> Unit,
    onCustomSettingsChange: (RuntimeSettings) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.game_settings_title, gameTitle)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsCard {
                    ToggleSetting(
                        title = stringResource(R.string.use_common_settings),
                        description = stringResource(R.string.use_common_settings_description),
                        checked = useCommonSettings,
                        onCheckedChange = onUseCommonSettingsChange,
                    )
                }
                if (!useCommonSettings) {
                    RuntimeSettingsOptions(customSettings, onCustomSettingsChange, isWolfGame = isWolfGame)
                } else {
                    Text(
                        stringResource(
                            R.string.common_settings_summary,
                            commonSettings.fpsLimit?.let { stringResource(R.string.fps_value, it) }
                                ?: stringResource(R.string.fps_auto),
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.RuntimeSettingsOptions(
    runtimeSettings: RuntimeSettings,
    onRuntimeSettingsChange: (RuntimeSettings) -> Unit,
    isWolfGame: Boolean = false,
) {
    // WOLF games run on the native backend: Chromium WebView options do not
    // apply and are hidden entirely.

    SettingsSection(stringResource(R.string.display_section))
    SettingsCard {
        ChoiceSetting(
            title = stringResource(R.string.orientation),
            values = RuntimeOrientation.entries,
            selected = runtimeSettings.orientation,
            label = { orientation ->
                stringResource(
                    when (orientation) {
                        RuntimeOrientation.AUTO -> R.string.orientation_auto
                        RuntimeOrientation.PORTRAIT -> R.string.orientation_portrait
                        RuntimeOrientation.LANDSCAPE -> R.string.orientation_landscape
                    },
                )
            },
            onSelected = { onRuntimeSettingsChange(runtimeSettings.copy(orientation = it)) },
        )
        ChoiceSetting(
            title = stringResource(R.string.scale_mode),
            values = RuntimeScaleMode.entries,
            selected = runtimeSettings.scaleMode,
            label = { mode ->
                stringResource(
                    when (mode) {
                        RuntimeScaleMode.FIT -> R.string.scale_fit
                        RuntimeScaleMode.INTEGER -> R.string.scale_integer
                        RuntimeScaleMode.STRETCH -> R.string.scale_stretch
                    },
                )
            },
            onSelected = { onRuntimeSettingsChange(runtimeSettings.copy(scaleMode = it)) },
        )
        ToggleSetting(
            title = stringResource(R.string.pixel_smoothing),
            description = stringResource(R.string.pixel_smoothing_description),
            checked = runtimeSettings.pixelSmoothing,
            onCheckedChange = { onRuntimeSettingsChange(runtimeSettings.copy(pixelSmoothing = it)) },
        )
        ToggleSetting(
            title = stringResource(R.string.immersive_mode),
            description = stringResource(R.string.immersive_mode_description),
            checked = runtimeSettings.immersiveMode,
            onCheckedChange = { onRuntimeSettingsChange(runtimeSettings.copy(immersiveMode = it)) },
        )
    }

    SettingsSection(stringResource(R.string.playback_section))
    SettingsCard {
        ToggleSetting(
            title = stringResource(R.string.pause_on_background),
            description = stringResource(R.string.pause_on_background_description),
            checked = runtimeSettings.pauseOnBackground,
            onCheckedChange = { onRuntimeSettingsChange(runtimeSettings.copy(pauseOnBackground = it)) },
        )
        ToggleSetting(
            title = stringResource(R.string.game_vibration),
            description = stringResource(R.string.game_vibration_description),
            checked = runtimeSettings.vibrationEnabled,
            onCheckedChange = { onRuntimeSettingsChange(runtimeSettings.copy(vibrationEnabled = it)) },
        )
    }

    if (!isWolfGame) {
        SettingsSection(stringResource(R.string.compatibility_section))
        SettingsCard {
            ChoiceSetting(
                title = stringResource(R.string.engine_mode),
                values = RuntimeEngineMode.entries,
                selected = runtimeSettings.engineMode,
                label = { mode ->
                    stringResource(
                        when (mode) {
                            RuntimeEngineMode.AUTO -> R.string.engine_auto
                            RuntimeEngineMode.MV -> R.string.engine_mv
                            RuntimeEngineMode.MZ -> R.string.engine_mz
                        },
                    )
                },
                onSelected = { onRuntimeSettingsChange(runtimeSettings.copy(engineMode = it)) },
            )
            ToggleSetting(
                title = stringResource(R.string.webgl_enabled),
                description = stringResource(R.string.webgl_description),
                checked = runtimeSettings.webGlEnabled,
                onCheckedChange = { onRuntimeSettingsChange(runtimeSettings.copy(webGlEnabled = it)) },
            )
            ToggleSetting(
                title = stringResource(R.string.legacy_compatibility),
                description = stringResource(R.string.legacy_compatibility_description),
                checked = runtimeSettings.legacyCompatibility,
                onCheckedChange = { onRuntimeSettingsChange(runtimeSettings.copy(legacyCompatibility = it)) },
            )
            ToggleSetting(
                title = stringResource(R.string.ignore_missing_files),
                description = stringResource(R.string.ignore_missing_files_description),
                checked = runtimeSettings.ignoreMissingFiles,
                onCheckedChange = { onRuntimeSettingsChange(runtimeSettings.copy(ignoreMissingFiles = it)) },
            )
        }
        Text(
            stringResource(R.string.compatibility_restart_description),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SettingsSection(stringResource(R.string.performance_section))
    SettingsCard {
        ChoiceSetting(
            title = stringResource(R.string.fps_limit),
            values = listOf<Int?>(null) + SUPPORTED_FPS_LIMITS,
            selected = runtimeSettings.fpsLimit,
            label = { value ->
                value?.let { stringResource(R.string.fps_value, it) }
                    ?: stringResource(R.string.fps_auto)
            },
            onSelected = { onRuntimeSettingsChange(runtimeSettings.copy(fpsLimit = it)) },
        )
        ToggleSetting(
            title = stringResource(R.string.record_logs),
            description = stringResource(R.string.record_logs_description),
            checked = runtimeSettings.recordLogs,
            onCheckedChange = { onRuntimeSettingsChange(runtimeSettings.copy(recordLogs = it)) },
        )
        ToggleSetting(
            title = stringResource(R.string.show_fps_counter),
            description = stringResource(R.string.show_fps_counter_description),
            checked = runtimeSettings.showFpsCounter,
            onCheckedChange = { onRuntimeSettingsChange(runtimeSettings.copy(showFpsCounter = it)) },
        )
    }

    SettingsSection(stringResource(R.string.runtime_modules_section))
    Text(
        stringResource(R.string.runtime_modules_description),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SettingsCard {
        if (!isWolfGame) {
            ToggleSetting(
                title = stringResource(R.string.module_steam),
                description = stringResource(R.string.module_steam_description),
                checked = runtimeSettings.modules.steamCompatibility,
                onCheckedChange = {
                    onRuntimeSettingsChange(
                        runtimeSettings.copy(modules = runtimeSettings.modules.copy(steamCompatibility = it)),
                    )
                },
            )
            ToggleSetting(
                title = stringResource(R.string.limit_background_load),
                description = stringResource(R.string.limit_background_load_description),
                checked = runtimeSettings.modules.limitWorkerCount,
                onCheckedChange = {
                    onRuntimeSettingsChange(
                        runtimeSettings.copy(modules = runtimeSettings.modules.copy(limitWorkerCount = it)),
                    )
                },
            )
            ToggleSetting(
                title = stringResource(R.string.module_performance),
                description = stringResource(R.string.module_performance_description),
                checked = runtimeSettings.modules.performanceOptimization,
                onCheckedChange = {
                    onRuntimeSettingsChange(
                        runtimeSettings.copy(
                            modules = runtimeSettings.modules.copy(performanceOptimization = it),
                        ),
                    )
                },
            )
        }
        ToggleSetting(
            title = stringResource(R.string.module_cheats),
            description = stringResource(R.string.module_cheats_description),
            checked = runtimeSettings.modules.cheatBridge,
            onCheckedChange = {
                onRuntimeSettingsChange(
                    runtimeSettings.copy(
                        modules = runtimeSettings.modules.copy(
                            cheatBridge = it
                        )
                    )
                )
            },
        )
        ToggleSetting(
            title = stringResource(R.string.module_diagnostics),
            description = stringResource(R.string.module_diagnostics_description),
            checked = runtimeSettings.modules.diagnosticsBridge,
            onCheckedChange = {
                onRuntimeSettingsChange(
                    runtimeSettings.copy(modules = runtimeSettings.modules.copy(diagnosticsBridge = it)),
                )
            },
        )
    }
}

@Composable
private fun SettingsCard(
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        title,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun <T> ChoiceSetting(
    title: String,
    values: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true },
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Text(label(selected), color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            SettingsChevron()
        },
    )
    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text(title) },
            text = {
                Column {
                    values.forEach { value ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected == value,
                                    role = Role.RadioButton,
                                    onClick = {
                                        onSelected(value)
                                        expanded = false
                                    },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == value, onClick = null)
                            Text(
                                label(value),
                                modifier = Modifier.padding(start = 12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun ToggleSetting(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Box(
                modifier = Modifier.width(52.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Switch(checked = checked, onCheckedChange = null)
            }
        },
    )
}

@Composable
private fun SettingsChevron() {
    Box(
        modifier = Modifier.width(52.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}