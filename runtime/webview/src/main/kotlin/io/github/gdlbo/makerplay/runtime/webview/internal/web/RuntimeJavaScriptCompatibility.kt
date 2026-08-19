package io.github.gdlbo.makerplay.runtime.webview.internal.web

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

internal data class RewrittenJavaScript(
    val stream: InputStream,
    val length: Long,
)

internal fun rewriteBrowserIncompatibleJavaScript(
    path: String,
    source: InputStream,
): RewrittenJavaScript? {
    val isLogger = path.equals("js/libs/logger.js", ignoreCase = true)
    if (!isLogger) return null
    val bytes = source.use(InputStream::readBytes)
    val text = bytes.toString(StandardCharsets.UTF_8)
    val rewritten = text.replace(
        "return new URL(p, import.meta.url).pathname",
        "return new URL(p, document.baseURI).pathname",
    )
    if (rewritten == text) {
        return RewrittenJavaScript(ByteArrayInputStream(bytes), bytes.size.toLong())
    }
    val output = rewritten.toByteArray(StandardCharsets.UTF_8)
    return RewrittenJavaScript(ByteArrayInputStream(output), output.size.toLong())
}