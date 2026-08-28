package io.github.gdlbo.makerplay.runtime.webview

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean

class CommonJsRuntimeConfiguration internal constructor(
    internal val allowedOrigin: String,
    internal val token: String,
    internal val protocol: NodeFileProtocol,
)

internal object RuntimeCommonJsBridge {
    const val OBJECT_NAME = "makerplayNodeNative"
    const val ASYNC_OBJECT_NAME = "makerplayNodeAsyncNative"

    class Attachment internal constructor(
        internal val configuration: CommonJsRuntimeConfiguration,
        internal val queue: RuntimeSerialQueue,
        internal val attached: AtomicBoolean = AtomicBoolean(true),
    )

    private val executor: ThreadPoolExecutor = RuntimeAsyncExecutor.create("makerplay-node-fs")

    @SuppressLint("RequiresFeature", "AddJavascriptInterface")
    fun install(
        webView: WebView,
        configuration: CommonJsRuntimeConfiguration,
        runtimeSource: String,
    ): Attachment {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            "This WebView does not support document-start CommonJS injection."
        }
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            "This WebView does not support asynchronous Node messaging."
        }
        val attachment = Attachment(configuration, RuntimeSerialQueue(executor))
        webView.addJavascriptInterface(
            SynchronousNodeBridge(
                token = configuration.token,
                protocol = configuration.protocol,
            ),
            OBJECT_NAME,
        )
        installAsync(webView, configuration, attachment)
        check(TOKEN_MARKER in runtimeSource) { "CommonJS token marker is missing" }
        val token = JsonPrimitive(configuration.token).toString()
        val source = runtimeSource.replace(TOKEN_MARKER, token)
        check(TOKEN_MARKER !in source) { "CommonJS token marker was not replaced" }
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            source,
            setOf(configuration.allowedOrigin),
        )
        return attachment
    }

    fun uninstall(webView: WebView, attachment: Attachment) {
        attachment.attached.set(false)
        attachment.queue.close()
        webView.removeJavascriptInterface(OBJECT_NAME)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.removeWebMessageListener(webView, ASYNC_OBJECT_NAME)
        }
    }

    @SuppressLint("RequiresFeature")
    private fun installAsync(
        webView: WebView,
        configuration: CommonJsRuntimeConfiguration,
        attachment: Attachment,
    ) {
        val expectedOrigin = Uri.parse(configuration.allowedOrigin)
        WebViewCompat.addWebMessageListener(
            webView,
            ASYNC_OBJECT_NAME,
            setOf(configuration.allowedOrigin),
        ) { view, message, sourceOrigin, isMainFrame, replyProxy ->
            if (
                !isMainFrame ||
                !sourceOrigin.sameOrigin(expectedOrigin) ||
                message.type != WebMessageCompat.TYPE_STRING
            ) return@addWebMessageListener
            val request = message.data ?: return@addWebMessageListener
            if (!attachment.attached.get()) return@addWebMessageListener
            val accepted = attachment.queue.submit(
                weight = request.length,
                task = {
                    val replied = configuration.protocol.handleReadAsyncIfPossible(request) { response ->
                        if (attachment.attached.get()) {
                            view.post {
                                if (attachment.attached.get()) replyProxy.postMessage(response)
                            }
                        }
                    }
                    if (!replied) {
                        val response = configuration.protocol.handle(request)
                        if (attachment.attached.get()) {
                            view.post {
                                if (attachment.attached.get()) replyProxy.postMessage(response)
                            }
                        }
                    }
                },
                rejected = {
                    if (attachment.attached.get()) {
                        view.post {
                            if (attachment.attached.get()) {
                                replyProxy.postMessage(configuration.protocol.busy(request))
                            }
                        }
                    }
                },
            )
            if (!accepted && attachment.attached.get()) {
                replyProxy.postMessage(configuration.protocol.busy(request))
                return@addWebMessageListener
            }
        }
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

    private const val TOKEN_MARKER = "__MAKERPLAY_NODE_TOKEN__"
}

internal class SynchronousNodeBridge(
    token: String,
    private val protocol: NodeFileProtocol,
) {
    private val expectedToken = token.toByteArray(Charsets.UTF_8)

    @JavascriptInterface
    fun transact(token: String, request: String): String {
        if (!MessageDigest.isEqual(expectedToken, token.toByteArray(Charsets.UTF_8))) {
            return "{\"v\":1,\"id\":\"invalid\",\"ok\":false,\"error\":\"forbidden\"}"
        }
        return protocol.handle(request)
    }
}