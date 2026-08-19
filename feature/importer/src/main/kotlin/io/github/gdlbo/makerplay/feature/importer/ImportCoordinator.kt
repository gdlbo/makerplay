package io.github.gdlbo.makerplay.feature.importer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.Observer
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

class ImportCoordinator(
    context: Context,
    private val catalog: GameCatalogRepository,
) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val leases = SafPermissionLeaseStore(appContext)
    private val mutableState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val state: StateFlow<ImportUiState> = mutableState.asStateFlow()
    private val observer = Observer<List<WorkInfo>>(::onWorkInfoChanged)
    private var currentWorkId: UUID? = null

    init {
        workManager.getWorkInfosForUniqueWorkLiveData(GameImportWorker.UNIQUE_WORK_NAME)
            .observeForever(observer)
    }

    fun enqueueSaf(sourceUri: Uri) {
        enqueue(
            sourceKind = ImportSourceKind.SAF_TREE,
            sourceLocation = sourceUri.toString(),
            persistedUri = sourceUri,
            installMode = GameInstallMode.COPY,
        )
    }

    fun enqueueFile(directory: File, installMode: GameInstallMode = GameInstallMode.COPY) {
        runCatching { directory.canonicalPath }
            .onSuccess { path ->
                enqueue(
                    sourceKind = ImportSourceKind.FILE_DIRECTORY,
                    sourceLocation = path,
                    persistedUri = null,
                    installMode = installMode,
                )
            }
            .onFailure { reportFailure(appContext.getString(R.string.selected_folder_unavailable)) }
    }

    private fun enqueue(
        sourceKind: ImportSourceKind,
        sourceLocation: String,
        persistedUri: Uri?,
        installMode: GameInstallMode,
    ) {
        val importId = UUID.randomUUID().toString()
        val request = OneTimeWorkRequestBuilder<GameImportWorker>()
            .setInputData(
                workDataOf(
                    GameImportWorker.KEY_SOURCE_KIND to sourceKind.name,
                    GameImportWorker.KEY_SOURCE_LOCATION to sourceLocation,
                    GameImportWorker.KEY_IMPORT_ID to importId,
                    GameImportWorker.KEY_INSTALL_MODE to installMode.name,
                ),
            )
            .addTag(GameImportWorker.TAG)
            .build()
        currentWorkId = request.id
        try {
            if (persistedUri != null) {
                leases.acquire(request.id, persistedUri)
            }
            workManager.enqueueUniqueWork(
                GameImportWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        } catch (_: Throwable) {
            currentWorkId = null
            if (persistedUri != null) leases.complete(request.id)
            reportFailure(appContext.getString(R.string.import_enqueue_failed))
        }
    }

    fun cancel() {
        workManager.cancelUniqueWork(GameImportWorker.UNIQUE_WORK_NAME)
    }

    fun reportFailure(message: String) {
        mutableState.value = ImportUiState.Failed(message)
    }

    private fun onWorkInfoChanged(workInfos: List<WorkInfo>) {
        releaseUnusedPermissions(workInfos)
        val info = currentWorkId?.let { id -> workInfos.find { it.id == id } }
            ?: workInfos.firstOrNull { !it.state.isFinished }
            ?: run {
                mutableState.value = ImportUiState.Idle
                return
            }
        mutableState.value = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING -> {
                ImportUiState.Running(
                    ImportProgress(
                        phase = info.progress.getString(GameImportWorker.KEY_PHASE)
                            ?.let { runCatching { ImportPhase.valueOf(it) }.getOrNull() }
                            ?: ImportPhase.SCANNING,
                        copiedBytes = info.progress.getLong(GameImportWorker.KEY_COPIED_BYTES, 0L),
                        totalBytes = info.progress.getLong(GameImportWorker.KEY_TOTAL_BYTES, 0L),
                        copiedFiles = info.progress.getLong(GameImportWorker.KEY_COPIED_FILES, 0L),
                        totalFiles = info.progress.getLong(GameImportWorker.KEY_TOTAL_FILES, 0L),
                    ),
                )
            }

            WorkInfo.State.SUCCEEDED -> {
                catalog.refresh()
                currentWorkId = null
                ImportUiState.Succeeded(
                    info.outputData.getString(GameImportWorker.KEY_GAME_ID).orEmpty()
                )
            }

            WorkInfo.State.FAILED -> {
                currentWorkId = null
                ImportUiState.Failed(
                    info.outputData.getString(GameImportWorker.KEY_ERROR_MESSAGE)
                        ?: appContext.getString(R.string.import_failed),
                )
            }

            WorkInfo.State.CANCELLED -> {
                currentWorkId = null
                ImportUiState.Idle
            }
        }
    }

    private fun releaseUnusedPermissions(workInfos: List<WorkInfo>) {
        val activeIds = workInfos.asSequence()
            .filterNot { it.state.isFinished }
            .map { it.id.toString() }
            .toMutableSet()
            .apply {
                currentWorkId
                    ?.takeIf { current -> workInfos.none { it.id == current } }
                    ?.let { add(it.toString()) }
            }
        leases.reconcile(activeIds)
    }
}