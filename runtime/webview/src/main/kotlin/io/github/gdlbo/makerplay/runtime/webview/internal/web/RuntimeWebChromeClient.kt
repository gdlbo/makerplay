package io.github.gdlbo.makerplay.runtime.webview.internal.web

import android.app.AlertDialog
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import io.github.gdlbo.makerplay.runtime.webview.internal.bridge.RuntimeKeyboardBridge

private const val TAG = "MakerPlay"

internal class RuntimeWebChromeClient(
    private val onRuntimeError: (String, Map<String, String>) -> Unit,
    private val onCloseRequested: () -> Unit,
) : WebChromeClient() {
    private val deduplicator = RuntimeConsoleDeduplicator()

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        val level = consoleMessage.messageLevel()
        val entry = runtimeConsoleEntry(
            level = level.name,
            isError = level == ConsoleMessage.MessageLevel.ERROR,
            message = consoleMessage.message(),
            source = consoleMessage.sourceId(),
            line = consoleMessage.lineNumber(),
        )
        if (deduplicator.shouldReport(entry)) {
            onRuntimeError(entry.event, entry.fields)
        }
        return super.onConsoleMessage(consoleMessage)
    }

    override fun onJsPrompt(
        view: WebView?,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult?,
    ): Boolean {
        if (result == null) return false
        android.util.Log.i(
            TAG,
            "runtime.js.prompt message=${message.orEmpty().take(80)} url=${url.orEmpty()}",
        )
        // Completing later while IME attaches deadlocks Chromium's editor request.
        result.cancel()
        val host = view ?: return true
        host.post {
            RuntimeKeyboardBridge.prompt(
                host,
                message.orEmpty().ifBlank { "Input" },
                defaultValue.orEmpty(),
            ) { value ->
                RuntimeKeyboardBridge.deliver(host, "0", value)
            }
        }
        return true
    }

    override fun onJsAlert(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?,
    ): Boolean {
        if (result == null) return false
        val context = view?.context
        if (view == null || context == null) {
            result.confirm()
            return true
        }
        return try {
            AlertDialog.Builder(context)
                .setMessage(message ?: "")
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.confirm() }
                .show()
            true
        } catch (error: Exception) {
            android.util.Log.e(TAG, "runtime.js.alert_failed ${error.message}")
            result.confirm()
            true
        }
    }

    override fun onJsConfirm(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?,
    ): Boolean {
        if (result == null) return false
        val context = view?.context
        if (view == null || context == null) {
            result.cancel()
            return true
        }
        return try {
            AlertDialog.Builder(context)
                .setMessage(message ?: "")
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            true
        } catch (error: Exception) {
            android.util.Log.e(TAG, "runtime.js.confirm_failed ${error.message}")
            result.cancel()
            true
        }
    }

    override fun onCloseWindow(window: WebView) {
        onCloseRequested()
    }
}

internal data class RuntimeConsoleEntry(
    val event: String,
    val fields: Map<String, String>,
)

internal class RuntimeConsoleDeduplicator(
    private val duplicateWindowMillis: Long = 100L,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var lastEntry: RuntimeConsoleEntry? = null
    private var lastReportedAt = Long.MIN_VALUE

    fun shouldReport(entry: RuntimeConsoleEntry): Boolean {
        val now = nowMillis()
        val elapsed = now - lastReportedAt
        if (entry == lastEntry && elapsed in 0..duplicateWindowMillis) return false
        lastEntry = entry
        lastReportedAt = now
        return true
    }
}

internal fun runtimeConsoleEntry(
    level: String,
    isError: Boolean,
    message: String,
    source: String,
    line: Int,
) = RuntimeConsoleEntry(
    event = if (isError) "runtime.javascript.error" else "runtime.javascript.console",
    fields = mapOf(
        "level" to level,
        "message" to message,
        "source" to source,
        "line" to line.toString(),
    ),
)