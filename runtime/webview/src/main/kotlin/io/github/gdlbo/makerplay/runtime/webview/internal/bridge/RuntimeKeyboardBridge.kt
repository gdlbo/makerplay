package io.github.gdlbo.makerplay.runtime.webview.internal.bridge

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import org.json.JSONObject

internal class RuntimeKeyboardBridge(
    private val webView: WebView,
) {
    @JavascriptInterface
    fun request(title: String, defaultValue: String, variableId: String) {
        android.util.Log.i(
            TAG,
            "runtime.keyboard.request title=${title.take(80)} variableId=$variableId",
        )
        webView.post {
            prompt(webView, title, defaultValue) { value ->
                deliver(webView, variableId, value)
            }
        }
    }

    companion object {
        const val OBJECT_NAME = "makerplayKeyboard"
        private const val TAG = "MakerPlay"

        @SuppressLint("AddJavascriptInterface")
        fun install(webView: WebView) {
            webView.addJavascriptInterface(RuntimeKeyboardBridge(webView), OBJECT_NAME)
        }

        fun uninstall(webView: WebView) {
            webView.removeJavascriptInterface(OBJECT_NAME)
        }

        fun prompt(
            webView: WebView,
            title: String,
            defaultValue: String,
            onResult: (String?) -> Unit,
        ) {
            val context = webView.context ?: run {
                onResult(null)
                return
            }
            var settled = false
            fun settle(value: String?) {
                if (settled) return
                settled = true
                onResult(value)
            }
            try {
                val density = context.resources.displayMetrics.density
                val editText = EditText(context).apply {
                    setText(defaultValue)
                    setSelection(text.length)
                    isSingleLine = true
                    inputType = InputType.TYPE_CLASS_TEXT
                    imeOptions = EditorInfo.IME_ACTION_DONE
                }
                val container = FrameLayout(context).apply {
                    val pad = (20 * density).toInt()
                    setPadding(pad, (10 * density).toInt(), pad, (10 * density).toInt())
                    addView(editText)
                }
                val dialog = AlertDialog.Builder(context)
                    .setTitle(title.ifBlank { "Input" })
                    .setView(container)
                    .setPositiveButton(android.R.string.ok) { _, _ -> settle(editText.text.toString()) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> settle(null) }
                    .setOnCancelListener { settle(null) }
                    .create()
                dialog.setOnShowListener {
                    editText.requestFocus()
                    dialog.window?.setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE,
                    )
                }
                dialog.show()
            } catch (error: Exception) {
                android.util.Log.e(
                    TAG,
                    "runtime.keyboard.failed ${error.javaClass.simpleName}: ${error.message}",
                )
                settle(null)
            }
        }

        fun deliver(webView: WebView, variableId: String, value: String?) {
            val id = variableId.toIntOrNull() ?: 0
            val jsonValue = if (value == null) "null" else JSONObject.quote(value)
            webView.evaluateJavascript(
                """
                (function(){
                  var value = $jsonValue;
                  var field = document.getElementById("_111_input");
                  if (field && value !== null) field.value = value;
                  if (value !== null && $id && window.${'$'}gameVariables &&
                      typeof window.${'$'}gameVariables.setValue === "function") {
                    window.${'$'}gameVariables.setValue($id, value);
                  }
                })();
                """.trimIndent(),
                null,
            )
        }
    }
}
