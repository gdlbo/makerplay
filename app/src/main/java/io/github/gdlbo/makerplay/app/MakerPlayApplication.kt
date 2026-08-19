package io.github.gdlbo.makerplay.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import io.github.gdlbo.makerplay.feature.importer.GameImportWorker
import io.github.gdlbo.makerplay.runtime.webview.WebViewRuntimeStartup

class MakerPlayApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(applicationContext) }

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
    }
}