package io.github.gdlbo.makerplay.runtime.webview.internal.web

import android.net.Uri
import android.webkit.WebResourceRequest

internal fun Uri.sameOrigin(other: Uri): Boolean =
    scheme.equals(other.scheme, ignoreCase = true) &&
            host.equals(other.host, ignoreCase = true) &&
            effectivePort() == other.effectivePort()

private fun Uri.effectivePort(): Int = when {
    port != -1 -> port
    scheme.equals("https", ignoreCase = true) -> 443
    else -> -1
}

internal fun Uri.origin(): String {
    val portSuffix = if (port == -1 || port == 443) "" else ":$port"
    return "$scheme://$host$portSuffix"
}

internal fun WebResourceRequest.fields(): Map<String, String> = mapOf(
    "url" to url.buildUpon().clearQuery().fragment(null).build().toString(),
    "method" to method,
    "mainFrame" to isForMainFrame.toString(),
)