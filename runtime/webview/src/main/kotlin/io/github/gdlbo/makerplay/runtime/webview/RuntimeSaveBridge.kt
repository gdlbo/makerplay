package io.github.gdlbo.makerplay.runtime.webview

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.gdlbo.makerplay.runtime.webview.internal.save.MvSaveBridgeScript
import io.github.gdlbo.makerplay.runtime.webview.internal.save.MzSaveBridgeScript
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean

sealed interface RuntimeSaveBridgeConfiguration

data class RuntimeSaveBridgeSession(
    val allowedOrigin: String,
    val protocol: SaveBridgeProtocol,
) : RuntimeSaveBridgeConfiguration

data class MvRuntimeSaveBridgeSession(
    val allowedOrigin: String,
    val protocol: SaveBridgeProtocol,
    val token: String,
) : RuntimeSaveBridgeConfiguration

internal object RuntimeSaveBridge {
    internal class Attachment internal constructor(
        internal val session: RuntimeSaveBridgeConfiguration,
        internal val queue: RuntimeSerialQueue?,
        internal val attached: AtomicBoolean = AtomicBoolean(true),
    )

    private val executor: ThreadPoolExecutor = RuntimeAsyncExecutor.create("makerplay-save")

    fun install(
        webView: WebView,
        session: RuntimeSaveBridgeConfiguration,
        mvScriptTemplate: String,
        mzScriptTemplate: String,
    ): Attachment {
        val attachment = Attachment(
            session = session,
            queue = if (session is RuntimeSaveBridgeSession) RuntimeSerialQueue(executor) else null,
        )
        when (session) {
            is RuntimeSaveBridgeSession -> installMz(webView, session, attachment, mzScriptTemplate)
            is MvRuntimeSaveBridgeSession -> installMv(webView, session, mvScriptTemplate)
        }
        return attachment
    }

    fun uninstall(webView: WebView, attachment: Attachment) {
        attachment.attached.set(false)
        attachment.queue?.close()
        when (attachment.session) {
            is RuntimeSaveBridgeSession -> {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    WebViewCompat.removeWebMessageListener(webView, MzSaveBridgeScript.OBJECT_NAME)
                }
            }

            is MvRuntimeSaveBridgeSession -> webView.removeJavascriptInterface(MvSaveBridgeScript.OBJECT_NAME)
        }
    }

    @SuppressLint("RequiresFeature")
    private fun installMz(
        webView: WebView,
        session: RuntimeSaveBridgeSession,
        attachment: Attachment,
        scriptTemplate: String,
    ) {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            "This WebView does not support secure save messaging."
        }
        check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            "This WebView does not support document-start save injection."
        }
        val origin = Uri.parse(session.allowedOrigin)
        val rules = setOf(session.allowedOrigin)
        WebViewCompat.addWebMessageListener(
            webView,
            MzSaveBridgeScript.OBJECT_NAME,
            rules,
        ) { view, message, sourceOrigin, isMainFrame, replyProxy ->
            if (
                !isMainFrame ||
                !sourceOrigin.sameOrigin(origin) ||
                message.type != WebMessageCompat.TYPE_STRING
            ) return@addWebMessageListener
            val request = message.data ?: return@addWebMessageListener
            if (!attachment.attached.get()) return@addWebMessageListener
            val queue = checkNotNull(attachment.queue)
            val accepted = queue.submit(
                weight = request.length,
                task = {
                    val response = session.protocol.handle(request)
                    if (attachment.attached.get()) {
                        view.post {
                            if (attachment.attached.get()) replyProxy.postMessage(response)
                        }
                    }
                },
                rejected = {
                    if (attachment.attached.get()) {
                        view.post {
                            if (attachment.attached.get()) {
                                replyProxy.postMessage(session.protocol.busy(request))
                            }
                        }
                    }
                },
            )
            if (!accepted && attachment.attached.get()) {
                replyProxy.postMessage(session.protocol.busy(request))
                return@addWebMessageListener
            }
        }
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            MzSaveBridgeScript.source(scriptTemplate),
            rules
        )
    }

    @SuppressLint("RequiresFeature")
    private fun installMv(
        webView: WebView,
        session: MvRuntimeSaveBridgeSession,
        scriptTemplate: String,
    ) {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            "This WebView does not support document-start save injection."
        }
        val rules = setOf(session.allowedOrigin)
        webView.addJavascriptInterface(
            MvSynchronousSaveBridge(session.token, session.protocol),
            MvSaveBridgeScript.OBJECT_NAME,
        )
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            MvSaveBridgeScript.source(scriptTemplate, session.token),
            rules,
        )
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

internal class MvSynchronousSaveBridge(
    token: String,
    private val protocol: SaveBridgeProtocol,
) {
    private val expectedToken = token.toByteArray(Charsets.UTF_8)

    @JavascriptInterface
    fun transact(token: String, request: String): String {
        val suppliedToken = token.toByteArray(Charsets.UTF_8)
        if (!java.security.MessageDigest.isEqual(expectedToken, suppliedToken)) {
            return protocol.busy("")
        }
        return protocol.handle(request)
    }
}