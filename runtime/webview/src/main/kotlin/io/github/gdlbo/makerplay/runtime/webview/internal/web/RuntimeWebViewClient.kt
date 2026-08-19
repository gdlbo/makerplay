package io.github.gdlbo.makerplay.runtime.webview.internal.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import io.github.gdlbo.makerplay.runtime.webview.GameOriginResponder
import io.github.gdlbo.makerplay.runtime.webview.internal.assets.RuntimeScripts
import java.io.ByteArrayInputStream

@SuppressLint("MissingOnRenderProcessGone")
internal class RuntimeWebViewClient(
    private val startUrl: String,
    private val responder: GameOriginResponder?,
    private val assetLoader: WebViewAssetLoader,
    private val runtimeScripts: RuntimeScripts,
    private val onRuntimeError: (String, Map<String, String>) -> Unit,
    private val onPageStarted: () -> Unit,
    private val onPageReady: (WebView) -> Unit,
    private val onRendererGone: (WebView, Boolean) -> Unit,
) : WebViewClient() {
    private val startOrigin = Uri.parse(startUrl)

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        onPageStarted()
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (!Uri.parse(url).sameOrigin(startOrigin)) return
        if (runtimeScripts.layout.isNotEmpty()) {
            view.evaluateJavascript(runtimeScripts.layout, null)
        }
        if (runtimeScripts.frameRate.isNotEmpty()) {
            view.evaluateJavascript(runtimeScripts.frameRate, null)
        }
        if (runtimeScripts.legacyCompatibility.isNotEmpty()) {
            view.evaluateJavascript(runtimeScripts.legacyCompatibility, null)
        }
        onPageReady(view)
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean = !request.url.sameOrigin(startOrigin)

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
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
                null,
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
}