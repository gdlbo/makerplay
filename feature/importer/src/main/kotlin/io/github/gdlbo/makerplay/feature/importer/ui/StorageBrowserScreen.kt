package io.github.gdlbo.makerplay.feature.importer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gdlbo.makerplay.feature.importer.GameInstallMode
import io.github.gdlbo.makerplay.feature.importer.R
import io.github.gdlbo.makerplay.feature.importer.components.DirectoryRow
import io.github.gdlbo.makerplay.feature.importer.components.InstallModeSelector
import io.github.gdlbo.makerplay.feature.importer.components.StoragePermissionContent
import io.github.gdlbo.makerplay.feature.importer.storage.isInside
import io.github.gdlbo.makerplay.feature.importer.storage.listDirectories
import io.github.gdlbo.makerplay.feature.importer.storage.hasNwRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageBrowserScreen(
    hasFullStorageAccess: Boolean,
    roots: List<File>,
    initialDirectoryPath: String,
    onRequestFullStorageAccess: () -> Unit,
    onUseSystemPicker: () -> Unit,
    onInstallDirectory: (File, GameInstallMode) -> Unit,
    onSelectDirectory: ((File) -> Unit)? = null,
    initialInstallMode: GameInstallMode = GameInstallMode.COPY,
    onBack: () -> Unit,
) {
    var currentPath by rememberSaveable(initialDirectoryPath, hasFullStorageAccess, roots) {
        mutableStateOf(
            initialBrowserDirectory(
                path = initialDirectoryPath,
                roots = roots,
                hasFullStorageAccess = hasFullStorageAccess,
            )?.path,
        )
    }
    var installMode by rememberSaveable(initialInstallMode) { mutableStateOf(initialInstallMode) }
    val current = currentPath?.let(::File)
    var directories by remember { mutableStateOf<List<File>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var gameDirectories by remember { mutableStateOf<Set<String>>(emptySet()) }
    val directoryReadError = stringResource(R.string.directory_read_error)

    LaunchedEffect(hasFullStorageAccess, currentPath, roots) {
        gameDirectories = emptySet()
        if (!hasFullStorageAccess) {
            directories = emptyList()
            gameDirectories = emptySet()
            error = null
            return@LaunchedEffect
        }
        val result = current?.let { withContext(Dispatchers.IO) { listDirectories(it, roots) } }
        directories = result?.getOrElse { emptyList() }.orEmpty()
        gameDirectories = withContext(Dispatchers.IO) {
            (if (current == null) roots else directories)
                .filter(::hasNwRuntime)
                .mapTo(mutableSetOf()) { it.path }
        }
        error = result?.exceptionOrNull()?.let { directoryReadError }
    }

    fun navigateBack() {
        if (current == null) {
            onBack()
            return
        }
        val parent = current.parentFile
        currentPath = parent?.takeIf { candidate ->
            roots.any { root -> current.isInside(root) && candidate.isInside(root) }
        }?.path
    }

    BackHandler(onBack = ::navigateBack)

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        current?.name?.ifBlank { current.path } ?: stringResource(
                            if (onSelectDirectory == null) {
                                R.string.choose_game_folder
                            } else {
                                R.string.choose_default_folder
                            },
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = ::navigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(
                                R.string.navigate_back
                            )
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hasFullStorageAccess) {
                StoragePermissionContent(
                    onRequestAccess = onRequestFullStorageAccess,
                    onUseSystemPicker = onUseSystemPicker.takeIf { onSelectDirectory == null },
                )
            } else {
                current?.let { directory ->
                    Text(
                        directory.path,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (onSelectDirectory == null) {
                        InstallModeSelector(
                            installMode = installMode,
                            onModeChange = { installMode = it },
                        )
                    }
                    Button(
                        onClick = {
                            onSelectDirectory?.invoke(directory)
                                ?: onInstallDirectory(directory, installMode)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Text(
                            stringResource(
                                if (onSelectDirectory != null) {
                                    R.string.use_this_folder
                                } else if (installMode == GameInstallMode.DIRECT) {
                                    R.string.open_this_folder_directly
                                } else {
                                    R.string.import_this_folder
                                },
                            ),
                            Modifier.padding(start = 8.dp),
                        )
                    }
                } ?: Text(
                    stringResource(R.string.storage_locations),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val visibleDirectories = if (current == null) roots else directories
                if (visibleDirectories.isEmpty()) {
                    EmptyDirectoryState(isRoot = current == null)
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visibleDirectories, key = File::getPath) { directory ->
                        DirectoryRow(
                            directory = directory,
                            isRoot = current == null,
                            isGameFolder = directory.path in gameDirectories,
                            onClick = { currentPath = directory.path },
                        )
                    }
                }
                if (onSelectDirectory == null && installMode == GameInstallMode.COPY) {
                    FilledTonalButton(
                        onClick = onUseSystemPicker,
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(bottom = 8.dp),
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Text(
                            stringResource(R.string.use_system_picker),
                            Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

internal fun initialBrowserDirectory(
    path: String,
    roots: List<File>,
    hasFullStorageAccess: Boolean,
): File? {
    if (!hasFullStorageAccess || path.isBlank()) return null
    val directory = runCatching { File(path.trim()).canonicalFile }.getOrNull() ?: return null
    return directory.takeIf { candidate ->
        candidate.isDirectory && candidate.canRead() && roots.any(candidate::isInside)
    }
}

@Composable
private fun EmptyDirectoryState(isRoot: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (isRoot) Icons.Default.FolderOff else Icons.Default.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(if (isRoot) R.string.no_storage_locations else R.string.empty_folder_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(if (isRoot) R.string.no_storage_locations_description else R.string.empty_folder_description),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
