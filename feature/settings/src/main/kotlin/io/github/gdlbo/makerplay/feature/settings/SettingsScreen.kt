package io.github.gdlbo.makerplay.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.gdlbo.makerplay.runtime.api.RuntimeEngineMode
import io.github.gdlbo.makerplay.runtime.api.RuntimeOrientation
import io.github.gdlbo.makerplay.runtime.api.RuntimeScaleMode
import io.github.gdlbo.makerplay.runtime.api.RuntimeSettings
import io.github.gdlbo.makerplay.runtime.api.SUPPORTED_FPS_LIMITS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    defaultGameFolder: String,
    onDefaultGameFolderChange: (String) -> Unit,
    onChooseDefaultGameFolder: () -> Unit,
    runtimeSettings: RuntimeSettings,
    onRuntimeSettingsChange: (RuntimeSettings) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Scaffold(
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.library_section),
                modifier = Modifier.padding(start = 12.dp, top = 12.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            TextField(
                value = defaultGameFolder,
                onValueChange = onDefaultGameFolderChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.default_game_folder)) },
                placeholder = { Text(stringResource(R.string.default_game_folder_example)) },
                leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                trailingIcon = {
                    if (defaultGameFolder.isNotEmpty()) {
                        IconButton(onClick = { onDefaultGameFolderChange("") }) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = stringResource(R.string.clear_default_game_folder),
                            )
                        }
                    }
                },
                supportingText = { Text(stringResource(R.string.default_game_folder_description)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            FilledTonalButton(
                onClick = onChooseDefaultGameFolder,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Text(
                    stringResource(R.string.choose_default_game_folder),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            RuntimeSettingsOptions(runtimeSettings, onRuntimeSettingsChange)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameSettingsScreen(
    gameTitle: String,
    useCommonSettings: Boolean,
    commonSettings: RuntimeSettings,
    customSettings: RuntimeSettings,
    onUseCommonSettingsChange: (Boolean) -> Unit,
    onCustomSettingsChange: (RuntimeSettings) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ToggleSetting(
                title = stringResource(R.string.use_common_settings),
                description = stringResource(R.string.use_common_settings_description),
                checked = useCommonSettings,
                onCheckedChange = onUseCommonSettingsChange,
            )
            if (!useCommonSettings) {
                RuntimeSettingsOptions(customSettings, onCustomSettingsChange)
            } else {
                Text(
                    stringResource(
                        R.string.common_settings_summary,
                        commonSettings.fpsLimit?.let { stringResource(R.string.fps_value, it) }
                            ?: stringResource(R.string.fps_auto),
                    ),
                    modifier = Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.RuntimeSettingsOptions(
    runtimeSettings: RuntimeSettings,
    onRuntimeSettingsChange: (RuntimeSettings) -> Unit,
) {
    SettingsSection(stringResource(R.string.display_section))
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
    SettingsSection(stringResource(R.string.playback_section))
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
    SettingsSection(stringResource(R.string.compatibility_section))
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
    Text(
        stringResource(R.string.compatibility_restart_description),
        modifier = Modifier.padding(horizontal = 12.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SettingsSection(stringResource(R.string.performance_section))
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
        title = stringResource(R.string.show_fps_counter),
        description = stringResource(R.string.show_fps_counter_description),
        checked = runtimeSettings.showFpsCounter,
        onCheckedChange = { onRuntimeSettingsChange(runtimeSettings.copy(showFpsCounter = it)) },
    )
    SettingsSection(stringResource(R.string.runtime_modules_section))
    Text(
        stringResource(R.string.runtime_modules_description),
        modifier = Modifier.padding(horizontal = 12.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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

@Composable
private fun SettingsSection(title: String) {
    Text(
        title,
        modifier = Modifier.padding(start = 12.dp, top = 4.dp),
        style = MaterialTheme.typography.titleSmall,
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.titleMedium
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            values.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = selected == value,
                    onClick = { onSelected(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, values.size),
                ) {
                    Text(label(value))
                }
            }
        }
    }
}

@Composable
private fun ToggleSetting(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}