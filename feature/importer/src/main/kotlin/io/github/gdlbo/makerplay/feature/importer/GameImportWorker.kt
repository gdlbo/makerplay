package io.github.gdlbo.makerplay.feature.importer

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import java.io.File

class GameImportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val leases = SafPermissionLeaseStore(applicationContext)
        return try {
            performImport()
        } finally {
            runCatching { leases.complete(id) }
        }
    }

    private suspend fun performImport(): Result {
        val sourceKind = inputData.getString(KEY_SOURCE_KIND)
            ?.let { runCatching { ImportSourceKind.valueOf(it) }.getOrNull() }
            ?: return Result.failure(errorData(applicationContext.getString(R.string.import_source_invalid)))
        val sourceLocation = inputData.getString(KEY_SOURCE_LOCATION)
            ?: return Result.failure(errorData(applicationContext.getString(R.string.selected_folder_unavailable)))
        val importId = inputData.getString(KEY_IMPORT_ID)
            ?: return Result.failure(errorData(applicationContext.getString(R.string.import_request_invalid)))
        val installMode = inputData.getString(KEY_INSTALL_MODE)
            ?.let { runCatching { GameInstallMode.valueOf(it) }.getOrNull() }
            ?: GameInstallMode.COPY
        val store = PrivateGameStore(File(applicationContext.filesDir, GAMES_DIRECTORY))
        return try {
            setForeground(createForegroundInfo(installMode))
            val source = when (sourceKind) {
                ImportSourceKind.SAF_TREE -> SafImportSource(
                    applicationContext,
                    Uri.parse(sourceLocation)
                )

                ImportSourceKind.FILE_DIRECTORY -> {
                    if (!Environment.isExternalStorageManager()) {
                        throw ImportFailure(applicationContext.getString(R.string.storage_access_revoked))
                    }
                    FileImportSource(
                        root = File(sourceLocation),
                        allowedRoots = StorageRoots.available(applicationContext),
                    )
                }
            }
            val progressReporter: suspend (ImportProgress) -> Unit = { progress ->
                setProgress(
                    workDataOf(
                        KEY_COPIED_BYTES to progress.copiedBytes,
                        KEY_TOTAL_BYTES to progress.totalBytes,
                        KEY_COPIED_FILES to progress.copiedFiles,
                        KEY_TOTAL_FILES to progress.totalFiles,
                        KEY_PHASE to progress.phase.name,
                    ),
                )
            }
            val engine = GameImportEngine()
            val result = when (installMode) {
                GameInstallMode.COPY -> engine.import(source, store, importId, progressReporter)
                GameInstallMode.DIRECT -> {
                    if (sourceKind != ImportSourceKind.FILE_DIRECTORY) {
                        throw ImportFailure(applicationContext.getString(R.string.direct_mode_requires_file_access))
                    }
                    engine.link(source, File(sourceLocation), store, importId, progressReporter)
                }
            }
            Result.success(workDataOf(KEY_GAME_ID to result.game.id))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ImportFailure) {
            Result.failure(errorData(failure.userMessage))
        } catch (_: Throwable) {
            Result.failure(errorData(applicationContext.getString(R.string.import_failed)))
        }
    }

    private fun createForegroundInfo(installMode: GameInstallMode): ForegroundInfo {
        val cancelIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)
        val action = Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            applicationContext.getString(R.string.cancel_import_notification),
            cancelIntent,
        ).build()
        val notification = Notification.Builder(applicationContext, IMPORT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(
                applicationContext.getString(
                    if (installMode == GameInstallMode.DIRECT) {
                        R.string.link_notification_title
                    } else {
                        R.string.import_notification_title
                    },
                ),
            )
            .setContentText(
                applicationContext.getString(
                    if (installMode == GameInstallMode.DIRECT) {
                        R.string.link_notification_text
                    } else {
                        R.string.import_notification_text
                    },
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(action)
            .build()
        return ForegroundInfo(
            IMPORT_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun errorData(message: String): Data = workDataOf(KEY_ERROR_MESSAGE to message)

    companion object {
        const val TAG = "game-import"
        const val UNIQUE_WORK_NAME = "game-import-active"
        const val GAMES_DIRECTORY = "games"
        const val IMPORT_CHANNEL_ID = "game_imports"
        const val KEY_SOURCE_KIND = "sourceKind"
        const val KEY_SOURCE_LOCATION = "sourceLocation"
        const val KEY_IMPORT_ID = "importId"
        const val KEY_INSTALL_MODE = "installMode"
        const val KEY_GAME_ID = "gameId"
        const val KEY_ERROR_MESSAGE = "errorMessage"
        const val KEY_COPIED_BYTES = "copiedBytes"
        const val KEY_TOTAL_BYTES = "totalBytes"
        const val KEY_COPIED_FILES = "copiedFiles"
        const val KEY_TOTAL_FILES = "totalFiles"
        const val KEY_PHASE = "phase"
        private const val IMPORT_NOTIFICATION_ID = 4101
    }
}