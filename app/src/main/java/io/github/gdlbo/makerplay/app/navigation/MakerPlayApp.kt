package io.github.gdlbo.makerplay.app.navigation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import io.github.gdlbo.makerplay.model.GameEngine
import io.github.gdlbo.makerplay.app.AppGraph
import io.github.gdlbo.makerplay.app.openFolderExternally
import io.github.gdlbo.makerplay.app.ui.MakerPlayTheme
import io.github.gdlbo.makerplay.feature.importer.StorageRoots
import io.github.gdlbo.makerplay.feature.importer.GameInstallMode
import io.github.gdlbo.makerplay.feature.importer.ui.StorageBrowserScreen
import io.github.gdlbo.makerplay.feature.library.LibraryScreen
import io.github.gdlbo.makerplay.feature.library.R
import io.github.gdlbo.makerplay.feature.player.controller.ui.DefaultControllerLayoutScreen
import io.github.gdlbo.makerplay.feature.player.runtime.RuntimeHostScreen
import io.github.gdlbo.makerplay.feature.settings.GameSettingsScreen
import io.github.gdlbo.makerplay.feature.settings.R.string
import io.github.gdlbo.makerplay.feature.settings.SettingsScreen
import io.github.gdlbo.makerplay.runtime.api.LaunchRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun MakerPlayApp(graph: AppGraph) {
    val navController = rememberNavController()
    val games by graph.catalog.games.collectAsStateWithLifecycle()
    val importState by graph.importCoordinator.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val openFolderFailedMessage = stringResource(R.string.open_folder_failed)
    val savedAsAppDefaultMessage = stringResource(
        string.saved_as_app_default,
    )
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasFullStorageAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var storageRefresh by remember { mutableIntStateOf(0) }
    var defaultGameFolder by remember { mutableStateOf(graph.defaultGameFolder) }
    var defaultInstallMode by remember { mutableStateOf(graph.defaultInstallMode) }
    var runtimeSettings by remember { mutableStateOf(graph.runtimeSettings) }
    var themeMode by remember { mutableStateOf(graph.themeMode) }
    var defaultControllerRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasFullStorageAccess = Environment.isExternalStorageManager()
                storageRefresh++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val storageRoots = remember(hasFullStorageAccess, storageRefresh) {
        if (hasFullStorageAccess) StorageRoots.available(context) else emptyList()
    }
    val notificationPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {},
    )

    fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun requestFullStorageAccess() {
        val appIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            "package:${context.packageName}".toUri(),
        )
        runCatching { context.startActivity(appIntent) }
            .onFailure {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
    }

    val directoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            if (uri != null) {
                requestNotificationPermissionIfNeeded()
                graph.importCoordinator.enqueueSaf(uri)
                navController.popBackStack()
            }
        },
    )
    MakerPlayTheme(themeMode = themeMode) {
        Surface(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = "library",
                enterTransition = {
                    fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 5 }
                },
                exitTransition = {
                    fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { -it / 8 }
                },
                popEnterTransition = {
                    fadeIn(tween(220)) + slideInHorizontally(tween(220)) { -it / 8 }
                },
                popExitTransition = {
                    fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { it / 5 }
                },
            ) {
                composable("library") { entry ->
                    DisposableEffect(entry) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                scope.launch(Dispatchers.IO) { graph.catalog.refresh() }
                            }
                        }
                        entry.lifecycle.addObserver(observer)
                        onDispose { entry.lifecycle.removeObserver(observer) }
                    }
                    LibraryScreen(
                        games = games,
                        importState = importState,
                        onImport = { navController.navigate("storage-import") },
                        onCancelImport = graph.importCoordinator::cancel,
                        onPlay = { game -> navController.navigate("runtime/${game.id}") },
                        onGameSettings = { game -> navController.navigate("game-settings/${game.id}") },
                        onDelete = { game ->
                            scope.launch(Dispatchers.IO) { graph.deleteGame(game.id) }
                        },
                        onClearWebData = { game -> graph.clearGameWebData(game.id) },
                        canExport = { game -> graph.canExportGame(game.id) },
                        canShowFolder = { game -> graph.browseableGameFolder(game.id) != null },
                        onShowInFileManager = { game ->
                            graph.browseableGameFolder(game.id)?.let { folder ->
                                navController.navigate(
                                    "browse-folder/${encodeNavPath(folder.path)}",
                                )
                            }
                        },
                        onOpenInExternalFileManager = { game ->
                            val folder = graph.browseableGameFolder(game.id)
                            android.util.Log.i(
                                "MakerPlay",
                                "native.folder.open request gameId=${game.id} folder=${folder?.path}",
                            )
                            if (folder == null || !openFolderExternally(context, folder)) {
                                Toast.makeText(
                                    context,
                                    openFolderFailedMessage,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onExport = { game ->
                            scope.launch(Dispatchers.IO) {
                                val archive = graph.exportCopiedGame(game)
                                val appContext = context.applicationContext
                                launch(Dispatchers.Main) {
                                    if (archive == null) {
                                        Toast.makeText(
                                            context,
                                            appContext.getString(
                                                R.string.export_game_failed,
                                            ),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        return@launch
                                    }
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.export",
                                        archive,
                                    )
                                    val share = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/zip"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(
                                            Intent.EXTRA_SUBJECT,
                                            appContext.getString(
                                                R.string.export_game_share_title,
                                                game.title,
                                            ),
                                        )
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(
                                            share,
                                            appContext.getString(
                                                R.string.export_game_share_title,
                                                game.title,
                                            ),
                                        ),
                                    )
                                }
                            }
                        },
                        onReorderGames = graph.catalog::reorderGames,
                        onRunSmokeTest = { navController.navigate("runtime-smoke") },
                        onSettings = { navController.navigate("settings") },
                        artworkFile = graph::artworkFile,
                        showRuntimeSmokeTest = false,
                    )
                }
                composable("storage-import") {
                    StorageBrowserScreen(
                        hasFullStorageAccess = hasFullStorageAccess,
                        roots = storageRoots,
                        initialDirectoryPath = defaultGameFolder,
                        initialInstallMode = defaultInstallMode,
                        onRequestFullStorageAccess = ::requestFullStorageAccess,
                        onUseSystemPicker = { directoryPicker.launch(null) },
                        onInstallDirectory = { directory, installMode ->
                            requestNotificationPermissionIfNeeded()
                            graph.importCoordinator.enqueueFile(directory, installMode)
                            navController.popBackStack()
                        },
                        onBack = navController::popBackStack,
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            themeMode = mode
                            graph.themeMode = mode
                        },
                        defaultGameFolder = defaultGameFolder,
                        defaultInstallDirect = defaultInstallMode == GameInstallMode.DIRECT,
                        onDefaultInstallDirectChange = { direct ->
                            defaultInstallMode = if (direct) GameInstallMode.DIRECT else GameInstallMode.COPY
                            graph.defaultInstallMode = defaultInstallMode
                        },
                        onDefaultGameFolderChange = { path ->
                            defaultGameFolder = path
                            graph.defaultGameFolder = path
                        },
                        onChooseDefaultGameFolder = { navController.navigate("default-folder") },
                        runtimeSettings = runtimeSettings,
                        onRuntimeSettingsChange = { settings ->
                            runtimeSettings = settings
                            graph.runtimeSettings = settings
                        },
                        hasCustomDefaultControllerLayout = remember(defaultControllerRevision) {
                            graph.hasCustomDefaultControllerLayout()
                        },
                        onEditDefaultControllerLayout = {
                            navController.navigate("default-controller")
                        },
                        onResetDefaultControllerLayout = {
                            graph.clearDefaultControllerLayout()
                            defaultControllerRevision++
                        },
                        onBack = navController::popBackStack,
                    )
                }
                composable("default-controller") {
                    DefaultControllerLayoutScreen(
                        layoutFile = graph.defaultControllerLayoutFile(),
                        onBack = {
                            defaultControllerRevision++
                            navController.popBackStack()
                        },
                    )
                }
                composable("default-folder") {
                    StorageBrowserScreen(
                        hasFullStorageAccess = hasFullStorageAccess,
                        roots = storageRoots,
                        initialDirectoryPath = defaultGameFolder,
                        initialInstallMode = defaultInstallMode,
                        onRequestFullStorageAccess = ::requestFullStorageAccess,
                        onUseSystemPicker = {},
                        onInstallDirectory = { _, _ -> },
                        onSelectDirectory = { directory ->
                            defaultGameFolder = directory.path
                            graph.defaultGameFolder = directory.path
                            navController.popBackStack()
                        },
                        onBack = navController::popBackStack,
                    )
                }
                composable("game-settings/{gameId}") { entry ->
                    val gameId = requireNotNull(entry.arguments?.getString("gameId"))
                    val game = games.firstOrNull { it.id == gameId }
                    var useCommonSettings by remember(gameId) {
                        mutableStateOf(graph.gameRuntimeSettings(gameId) == null)
                    }
                    var customSettings by remember(gameId) {
                        mutableStateOf(graph.savedGameRuntimeSettings(gameId) ?: runtimeSettings)
                    }
                    val folderPath = graph.addedFromLocation(gameId)
                        ?: graph.browseableGameFolder(gameId)?.path
                    GameSettingsScreen(
                        gameTitle = game?.title ?: gameId,
                        isWolfGame = game?.engine == GameEngine.WOLF,
                        useCommonSettings = useCommonSettings,
                        commonSettings = runtimeSettings,
                        customSettings = customSettings,
                        gameFolderPath = folderPath,
                        onUseCommonSettingsChange = { useCommon ->
                            useCommonSettings = useCommon
                            graph.setGameRuntimeSettings(
                                gameId,
                                if (useCommon) null else customSettings
                            )
                        },
                        onCustomSettingsChange = { settings ->
                            customSettings = settings
                            graph.setGameRuntimeSettings(gameId, settings)
                        },
                        onSaveAsAppDefault = { settings ->
                            runtimeSettings = settings
                            graph.runtimeSettings = settings
                            Toast.makeText(
                                context,
                                savedAsAppDefaultMessage,
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        onShowInFileManager = {
                            graph.browseableGameFolder(gameId)?.let { folder ->
                                navController.navigate(
                                    "browse-folder/${encodeNavPath(folder.path)}",
                                )
                            }
                        },
                        onOpenInExternalFileManager = {
                            val folder = graph.browseableGameFolder(gameId)
                            android.util.Log.i(
                                "MakerPlay",
                                "native.folder.open request gameId=$gameId folder=${folder?.path}",
                            )
                            if (folder == null || !openFolderExternally(context, folder)) {
                                Toast.makeText(
                                    context,
                                    openFolderFailedMessage,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onBack = navController::popBackStack,
                    )
                }
                composable(
                    route = "browse-folder/{path}",
                    arguments = listOf(navArgument("path") { type = NavType.StringType }),
                ) { entry ->
                    val folderPath = decodeNavPath(
                        requireNotNull(entry.arguments?.getString("path")),
                    )
                    val folder = File(folderPath)
                    StorageBrowserScreen(
                        hasFullStorageAccess = hasFullStorageAccess,
                        roots = storageRoots,
                        extraRoots = listOfNotNull(folder.takeIf { it.isDirectory }),
                        initialDirectoryPath = folderPath,
                        initialInstallMode = defaultInstallMode,
                        browseOnly = true,
                        onRequestFullStorageAccess = ::requestFullStorageAccess,
                        onUseSystemPicker = {},
                        onInstallDirectory = { _, _ -> },
                        onOpenExternally = { directory ->
                            if (!openFolderExternally(context, directory)) {
                                Toast.makeText(
                                    context,
                                    openFolderFailedMessage,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onBack = navController::popBackStack,
                    )
                }
                composable("runtime-smoke") {
                    LaunchedEffect(runtimeSettings.heapTrimFraction) {
                        (context.applicationContext as io.github.gdlbo.makerplay.app.MakerPlayApplication)
                            .applyGameMemoryOptions(runtimeSettings)
                    }
                    RuntimeHostScreen(
                        backend = graph.runtimeBackend("m0-smoke"),
                        request = LaunchRequest(
                            "m0-smoke",
                            smokeTest = true,
                            settings = runtimeSettings
                        ),
                        onBack = navController::popBackStack,
                    )
                }
                composable("runtime/{gameId}") { entry ->
                    val gameId = requireNotNull(entry.arguments?.getString("gameId"))
                    val effectiveSettings = graph.gameRuntimeSettings(gameId) ?: runtimeSettings
                    LaunchedEffect(gameId, effectiveSettings.heapTrimFraction) {
                        (context.applicationContext as io.github.gdlbo.makerplay.app.MakerPlayApplication)
                            .applyGameMemoryOptions(effectiveSettings)
                    }
                    RuntimeHostScreen(
                        backend = graph.runtimeBackend(gameId),
                        request = LaunchRequest(
                            gameId,
                            settings = effectiveSettings,
                        ),
                        layoutFile = graph.controllerLayoutFile(gameId),
                        defaultLayoutFile = graph.defaultControllerLayoutFile(),
                        logFile = graph.runtimeLogFile(gameId),
                        loggingEnabled = effectiveSettings.recordLogs,
                        onToggleLogging = {
                            val updated = effectiveSettings.copy(recordLogs = !effectiveSettings.recordLogs)
                            if (graph.gameRuntimeSettings(gameId) == null) {
                                runtimeSettings = updated
                                graph.runtimeSettings = updated
                            } else {
                                graph.setGameRuntimeSettings(gameId, updated)
                            }
                        },
                        onBack = navController::popBackStack,
                    )
                }
            }
        }
    }
}

internal fun encodeNavPath(path: String): String =
    URLEncoder.encode(path, StandardCharsets.UTF_8.toString())

internal fun decodeNavPath(path: String): String =
    URLDecoder.decode(path, StandardCharsets.UTF_8.toString())