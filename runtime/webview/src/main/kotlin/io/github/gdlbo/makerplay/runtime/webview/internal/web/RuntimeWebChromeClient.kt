package io.github.gdlbo.makerplay.runtime.webview.internal.web

import android.app.AlertDialog
import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout

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
        if (view == null || result == null) return false
        val context = view.context ?: return false
        val density = context.resources.displayMetrics.density
        val editText = EditText(context).apply {
            setText(defaultValue ?: "")
            setSelection(text.length)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val container = FrameLayout(context).apply {
            val horizontalPadding = (20 * density).toInt()
            val verticalPadding = (10 * density).toInt()
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            addView(editText)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(message ?: "Input")
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                result.confirm(editText.text.toString())
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                result.cancel()
            }
            .setOnCancelListener {
                result.cancel()
            }
            .create()

        dialog.show()
        editText.requestFocus()
        editText.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(editText, 0)
        }
        return true
    }

    override fun onJsAlert(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?,
    ): Boolean {
        if (view == null || result == null) return false
        val context = view.context ?: return false
        AlertDialog.Builder(context)
            .setMessage(message ?: "")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                result.confirm()
            }
            .setOnCancelListener {
                result.confirm()
            }
            .show()
        return true
    }

    override fun onJsConfirm(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?,
    ): Boolean {
        if (view == null || result == null) return false
        val context = view.context ?: return false
        AlertDialog.Builder(context)
            .setMessage(message ?: "")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                result.confirm()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                result.cancel()
            }
            .setOnCancelListener {
                result.cancel()
            }
            .show()
        return true
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