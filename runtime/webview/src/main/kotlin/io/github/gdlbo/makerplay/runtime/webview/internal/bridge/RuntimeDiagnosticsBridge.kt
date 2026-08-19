package io.github.gdlbo.makerplay.runtime.webview.internal.bridge

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.gdlbo.makerplay.runtime.webview.internal.assets.renderRuntimeScript
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal enum class WebGlContextEvent { LOST, RESTORED }

internal object RuntimeDiagnosticsBridge {
    private const val OBJECT_NAME = "makerplayRuntimeDiagnostics"
    private const val MAX_MESSAGE_CHARS = 128

    @SuppressLint("RequiresFeature")
    fun install(
        webView: WebView,
        allowedOrigin: String,
        scriptTemplate: String,
        onEvent: (WebGlContextEvent) -> Unit,
    ): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) return false
        val expectedOrigin = Uri.parse(allowedOrigin)
        val rules = setOf(allowedOrigin)
        WebViewCompat.addWebMessageListener(
            webView,
            OBJECT_NAME,
            rules
        ) { _, message, sourceOrigin, isMainFrame, _ ->
            if (
                !isMainFrame ||
                !sourceOrigin.sameOrigin(expectedOrigin) ||
                message.type != WebMessageCompat.TYPE_STRING
            ) return@addWebMessageListener
            parse(message.data.orEmpty())?.let(onEvent)
        }
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            renderRuntimeScript(scriptTemplate, "__MAKERPLAY_OBJECT_NAME__" to OBJECT_NAME),
            rules,
        )
        return true
    }

    fun uninstall(webView: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.removeWebMessageListener(webView, OBJECT_NAME)
        }
    }

    internal fun parse(message: String): WebGlContextEvent? {
        if (message.length > MAX_MESSAGE_CHARS) return null
        return runCatching {
            val value = Json.parseToJsonElement(message).jsonObject
            if (value["v"]?.jsonPrimitive?.intOrNull != 1) return null
            when (value["type"]?.jsonPrimitive?.content) {
                "lost" -> WebGlContextEvent.LOST
                "restored" -> WebGlContextEvent.RESTORED
                else -> null
            }
        }.getOrNull()
    }

    private fun Uri.sameOrigin(expected: Uri): Boolean =
        scheme.equals(expected.scheme, ignoreCase = true) &&
                host.equals(expected.host, ignoreCase = true) &&
                effectivePort() == expected.effectivePort()

    private fun Uri.effectivePort(): Int = when {
        port != -1 -> port
        scheme.equals("https", ignoreCase = true) -> 443
        else -> -1
    }
}