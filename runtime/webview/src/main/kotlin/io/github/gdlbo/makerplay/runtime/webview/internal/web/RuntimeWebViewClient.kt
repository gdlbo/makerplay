package io.github.gdlbo.makerplay.runtime.webview.internal.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.gdlbo.makerplay.runtime.webview.GameOriginResponder
import io.github.gdlbo.makerplay.runtime.webview.internal.assets.RuntimeNetworkFallback
import io.github.gdlbo.makerplay.runtime.webview.internal.assets.RuntimeScripts
import java.io.ByteArrayInputStream

@SuppressLint("MissingOnRenderProcessGone")
internal class RuntimeWebViewClient(
    private val startUrl: String,
    private val responder: GameOriginResponder?,
    private val assetLoader: WebViewAssetLoader,
    private val runtimeScripts: RuntimeScripts,
    private val networkFallbacks: List<RuntimeNetworkFallback>,
    private val onRuntimeError: (String, Map<String, String>) -> Unit,
    private val onPageStarted: () -> Unit,
    private val onPageReady: (WebView) -> Unit,
    private val onRendererGone: (WebView, Boolean) -> Unit,
) : WebViewClient() {
    private val startOrigin = Uri.parse(startUrl)
    private var loadGeneration = 0L

    private var notifiedReady = false

    private fun notifyPageReady(view: WebView, source: String) {
        if (notifiedReady) return
        notifiedReady = true
        Log.i(TAG, "runtime.page_ready source=$source url=${view.url}")
        onPageReady(view)
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        val generation = ++loadGeneration
        notifiedReady = false
        Log.i(TAG, "runtime.page_started url=$url")
        onPageStarted()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.VISUAL_STATE_CALLBACK)) {
            WebViewCompat.postVisualStateCallback(
                view,
                generation,
                object : WebViewCompat.VisualStateCallback {
                    override fun onComplete(requestId: Long) {
                        if (requestId != loadGeneration) return
                        if (!Uri.parse(view.url ?: url).sameOrigin(startOrigin)) return
                        notifyPageReady(view, "visual_state")
                    }
                },
            )
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (!Uri.parse(url).sameOrigin(startOrigin)) {
            Log.i(TAG, "runtime.page_finished skipped origin url=$url start=$startOrigin")
            return
        }
        if (runtimeScripts.layout.isNotEmpty()) {
            view.evaluateJavascript(runtimeScripts.layout, null)
        }
        if (runtimeScripts.frameRate.isNotEmpty()) {
            view.evaluateJavascript(runtimeScripts.frameRate, null)
        }
        if (runtimeScripts.legacyCompatibility.isNotEmpty()) {
            view.evaluateJavascript(runtimeScripts.legacyCompatibility, null)
        }
        notifyPageReady(view, "page_finished")
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean = !request.url.sameOrigin(startOrigin)

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        bundledNetworkResponse(request)?.let { return it }
        if (responder == null) return assetLoader.shouldInterceptRequest(request.url)
        return try {
            val response = responder.respond(
                request.method,
                request.url.toString(),
                request.requestHeaders,
            )
            if (response.statusCode >= 400) {
                onRuntimeError(
                    "runtime.resource.http_error",
                    request.fields() + mapOf(
                        "statusCode" to response.statusCode.toString(),
                        "reason" to response.reasonPhrase,
                    ),
                )
            }
            WebResourceResponse(
                response.mimeType,
                charsetFor(response.mimeType),
                response.statusCode,
                response.reasonPhrase,
                response.headers,
                response.body,
            )
        } catch (error: Exception) {
            onRuntimeError(
                "runtime.resource.open_failed",
                request.fields() + ("failureClass" to error.javaClass.name),
            )
            WebResourceResponse(
                "text/plain",
                "UTF-8",
                500,
                "Internal Server Error",
                emptyMap(),
                ByteArrayInputStream(byteArrayOf()),
            )
        }
    }

    private fun bundledNetworkResponse(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (!url.query.isNullOrEmpty() || request.method.uppercase() !in setOf("GET", "HEAD")) return null
        val fallback = networkFallbacks.firstOrNull {
            it.scheme.equals(url.scheme, ignoreCase = true) &&
                it.host.equals(url.host, ignoreCase = true) &&
                it.path == url.path
        } ?: return null
        val headers = mapOf(
            "Cache-Control" to "public, max-age=31536000",
            "Content-Length" to fallback.body.size.toString(),
        )
        return WebResourceResponse(
            fallback.mimeType,
            "UTF-8",
            200,
            "OK",
            headers,
            ByteArrayInputStream(if (request.method.equals("HEAD", ignoreCase = true)) byteArrayOf() else fallback.body),
        )
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        onRuntimeError(
            "runtime.resource.unavailable",
            request.fields() + mapOf(
                "errorCode" to error.errorCode.toString(),
                "description" to error.description.toString(),
            ),
        )
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (responder != null) return
        onRuntimeError(
            "runtime.resource.http_error",
            request.fields() + mapOf(
                "statusCode" to errorResponse.statusCode.toString(),
                "reason" to errorResponse.reasonPhrase.orEmpty(),
            ),
        )
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onRendererGone(view, detail.didCrash())
        return true
    }

    private companion object {
        const val TAG = "MakerPlay"

        fun charsetFor(mimeType: String): String? {
            val mime = mimeType.substringBefore(';').trim().lowercase()
            return if (
                mime.startsWith("text/") ||
                    mime == "application/json" ||
                    mime == "application/javascript" ||
                    mime == "application/xml" ||
                    mime == "application/xhtml+xml"
            ) {
                "UTF-8"
            } else {
                null
            }
        }
    }
}