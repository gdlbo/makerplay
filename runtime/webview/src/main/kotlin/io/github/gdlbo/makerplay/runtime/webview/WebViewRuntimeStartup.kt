package io.github.gdlbo.makerplay.runtime.webview

import android.content.Context
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import java.util.concurrent.Executors

object WebViewRuntimeStartup {
    private val backgroundExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "WebViewStartup").apply { isDaemon = true }
        }

    fun start(
        context: Context,
        onReady: () -> Unit = {},
        onError: (Throwable) -> Unit = {},
    ) {
        WebViewCompat.startUpWebView(
            context.applicationContext,
            WebViewStartUpConfig.Builder(backgroundExecutor).build(),
            object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                override fun onResult(result: WebViewStartUpResult) = onReady()

                override fun onError(error: WebViewStartupException) = onError(error)
            },
        )
    }
}