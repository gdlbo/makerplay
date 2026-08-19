package io.github.gdlbo.makerplay.runtime.webview.internal.web

import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView

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