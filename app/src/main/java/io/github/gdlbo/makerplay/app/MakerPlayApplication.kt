package io.github.gdlbo.makerplay.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import io.github.gdlbo.makerplay.feature.importer.GameImportWorker
import io.github.gdlbo.makerplay.runtime.api.MemorySampleReason
import io.github.gdlbo.makerplay.runtime.api.RuntimeMemoryWatchdog
import io.github.gdlbo.makerplay.runtime.api.RuntimeMemoryWatchdogOptions
import io.github.gdlbo.makerplay.runtime.api.RuntimeSettings
import io.github.gdlbo.makerplay.runtime.webview.WebViewRuntimeStartup

class MakerPlayApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(applicationContext) }

    // Threshold-based sampler/evictor. Defaults stay disabled for unit tests; the app enables it
    // explicitly and forwards lifecycle trim events into it.
    private val memoryWatchdog: RuntimeMemoryWatchdog by lazy {
        RuntimeMemoryWatchdog.create(
            logger = graph.logger,
            options = RuntimeMemoryWatchdogOptions(
                enabled = true,
                nativeTrimBytes = RuntimeMemoryWatchdogOptions.DEFAULT_NATIVE_TRIM_BYTES,
            ),
        )
    }

    override fun onCreate() {
        super.onCreate()
        val appGraph = graph
        WebViewRuntimeStartup.start(
            context = this,
            onReady = { appGraph.logger.info("runtime.webview_startup_ready") },
            onError = { appGraph.logger.error("runtime.webview_startup_failed", it) },
        )
        appGraph.logger.info("application.start")
        val previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                appGraph.logger.error("application.crash", error)
            } finally {
                previousCrashHandler?.uncaughtException(thread, error)
            }
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                GameImportWorker.IMPORT_CHANNEL_ID,
                getString(R.string.import_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        memoryWatchdog.start()
    }

    /** Applies the open game's heap trim fraction without restarting the sampler. */
    fun applyGameMemoryOptions(settings: RuntimeSettings) {
        memoryWatchdog.updateOptions(
            RuntimeMemoryWatchdogOptions(
                enabled = true,
                heapTrimFraction = settings.heapTrimFraction,
                nativeTrimBytes = settings.nativeTrimBytes,
                graphicsTrimBytes = settings.graphicsTrimBytes,
            ),
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        memoryWatchdog.sampleNow(
            MemorySampleReason.TRIM_MEMORY,
            mapOf("level" to level.toString()),
        )
    }
}