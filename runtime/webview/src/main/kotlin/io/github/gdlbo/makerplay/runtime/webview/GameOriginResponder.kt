package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.runtime.webview.internal.web.rewriteBrowserIncompatibleJavaScript
import io.github.gdlbo.makerplay.vfs.ByteRange
import io.github.gdlbo.makerplay.vfs.GameFileSystem
import io.github.gdlbo.makerplay.vfs.GamePath
import io.github.gdlbo.makerplay.vfs.MimeTypes
import io.github.gdlbo.makerplay.vfs.VfsOpenResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

data class OriginResponse(
    val statusCode: Int,
    val reasonPhrase: String,
    val mimeType: String,
    val headers: Map<String, String>,
    val body: InputStream,
)

class GameOriginResponder(
    private val host: String,
    private val sessionId: String,
    private val fileSystem: GameFileSystem,
    private val ignoreMissingFiles: Boolean = false,
    private val onMissingFileIgnored: (path: String, mimeType: String) -> Unit = { _, _ -> },
    private val overlayAsset: ((String) -> OverlayAsset)? = null,
) {
    init {
        require(HOST.matches(host)) { "Invalid runtime origin host" }
        require(SESSION_ID.matches(sessionId)) { "Invalid runtime session ID" }
    }

    fun respond(
        method: String,
        url: String,
        requestHeaders: Map<String, String>,
    ): OriginResponse {
        if (method != "GET" && method != "HEAD") return error(405, "Method Not Allowed")
        val path = logicalPath(url) ?: return error(403, "Forbidden")
        val requestedRange = when (val parsed = parseRange(requestHeaders.header("Range"))) {
            ParsedRange.None -> null
            ParsedRange.Invalid -> return unsatisfied(path)
            is ParsedRange.Valid -> parsed.range
        }
        val overlay = overlayAsset?.invoke(path)
        if (overlay is OverlayAsset.Deleted) return missing(path, method, requestedRange)
        return when (overlay) {
            is OverlayAsset.Present -> foundOverlay(method, path, requestedRange, overlay)
            else -> when (val opened = fileSystem.open(path, requestedRange)) {
                VfsOpenResult.Missing -> missing(path, method, requestedRange)
                is VfsOpenResult.RequiresCodec,
                is VfsOpenResult.InvalidAsset,
                    -> error(500, "Invalid Asset")

                is VfsOpenResult.RangeNotSatisfiable -> rangeError(opened.completeLength)
                is VfsOpenResult.Found -> found(method, path, requestedRange, opened)
            }
        }
    }

    private fun foundOverlay(
        method: String,
        path: String,
        requestedRange: ByteRange?,
        asset: OverlayAsset.Present,
    ): OriginResponse {
        val completeLength = asset.bytes.size.toLong()
        val start = requestedRange?.startInclusive ?: 0L
        if (requestedRange != null && start >= completeLength) return rangeError(completeLength)
        val requestedEnd = requestedRange?.endInclusive
        val end = when {
            completeLength == 0L -> -1L
            requestedEnd == null -> completeLength - 1
            else -> minOf(requestedEnd, completeLength - 1)
        }
        val bodyBytes = if (completeLength == 0L) {
            byteArrayOf()
        } else {
            asset.bytes.copyOfRange(start.toInt(), (end + 1).toInt())
        }
        val headers = linkedMapOf(
            "Accept-Ranges" to "bytes",
            "Content-Length" to bodyBytes.size.toString(),
            "ETag" to "W/\"overlay-${asset.lastModifiedMillis.toString(16)}-${
                completeLength.toString(
                    16
                )
            }\"",
        )
        if (requestedRange != null) headers["Content-Range"] = "bytes $start-$end/$completeLength"
        return OriginResponse(
            if (requestedRange == null) 200 else 206,
            if (requestedRange == null) "OK" else "Partial Content",
            MimeTypes.forPath(GamePath.parse(path)),
            headers,
            if (method == "HEAD") EMPTY_BODY else ByteArrayInputStream(bodyBytes),
        )
    }

    private fun found(
        method: String,
        path: String,
        requestedRange: ByteRange?,
        opened: VfsOpenResult.Found,
    ): OriginResponse {
        val rewritten = if (requestedRange == null) {
            rewriteBrowserIncompatibleJavaScript(path, opened.stream)
        } else {
            null
        }
        val responseStream = rewritten?.stream ?: opened.stream
        val responseLength = rewritten?.length ?: opened.contentLength
        val headers = linkedMapOf(
            "Accept-Ranges" to "bytes",
            "Content-Length" to responseLength.toString(),
            "ETag" to opened.asset.entityTag,
        )
        opened.contentRange?.let { headers["Content-Range"] = it }
        val body = if (method == "HEAD") {
            responseStream.close()
            EMPTY_BODY
        } else {
            responseStream
        }
        return OriginResponse(
            if (opened.contentRange == null) 200 else 206,
            if (opened.contentRange == null) "OK" else "Partial Content",
            opened.asset.mimeType,
            headers,
            body,
        )
    }

    private fun unsatisfied(path: String): OriginResponse = when (
        val probe = fileSystem.open(path, ByteRange(Long.MAX_VALUE))
    ) {
        VfsOpenResult.Missing -> missing(path)
        is VfsOpenResult.RequiresCodec,
        is VfsOpenResult.InvalidAsset,
            -> error(500, "Invalid Asset")

        is VfsOpenResult.RangeNotSatisfiable -> rangeError(probe.completeLength)
        is VfsOpenResult.Found -> {
            probe.stream.close()
            error(416, "Range Not Satisfiable")
        }
    }

    private fun logicalPath(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (
            !uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals(host, ignoreCase = true) ||
            uri.userInfo != null ||
            (uri.port != -1 && uri.port != 443)
        ) return null
        val prefix = "/session/$sessionId/asset/"
        val rawPath = uri.rawPath ?: return null
        if (!rawPath.startsWith(prefix)) return null
        val encodedPath = rawPath.removePrefix(prefix)
        val decoded = decodePath(encodedPath)?.takeIf { value ->
            value.isNotBlank() && '\\' !in value && value.none { it.code < 0x20 }
        } ?: return null
        return runCatching { GamePath.parse(decoded).value }.getOrNull()
    }

    private fun decodePath(value: String): String? {
        val bytes = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char == '%' -> {
                    if (index + 2 >= value.length) return null
                    val high = value[index + 1].digitToIntOrNull(16) ?: return null
                    val low = value[index + 2].digitToIntOrNull(16) ?: return null
                    bytes.write((high shl 4) or low)
                    index += 3
                }

                char.code in 0x21..0x7e -> {
                    bytes.write(char.code)
                    index++
                }

                else -> return null
            }
        }
        return runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        }.getOrNull()
    }

    private fun rangeError(length: Long) = OriginResponse(
        416,
        "Range Not Satisfiable",
        "application/octet-stream",
        mapOf("Accept-Ranges" to "bytes", "Content-Range" to "bytes */$length"),
        EMPTY_BODY,
    )

    private fun missing(
        path: String,
        method: String = "GET",
        requestedRange: ByteRange? = null,
    ): OriginResponse {
        if (!ignoreMissingFiles) return error(404, "Not Found")
        val mimeType = MimeTypes.forPath(GamePath.parse(path))
        onMissingFileIgnored(path, mimeType)
        if (mimeType.startsWith("font/")) {
            fallbackFontPaths().forEach { fallbackPath ->
                val opened = fileSystem.open(fallbackPath, requestedRange)
                if (opened is VfsOpenResult.Found) {
                    return found(method, fallbackPath, requestedRange, opened)
                }
            }
        }
        return OriginResponse(
            statusCode = 200,
            reasonPhrase = "OK",
            mimeType = mimeType,
            headers = mapOf("Content-Length" to "0"),
            body = EMPTY_BODY,
        )
    }

    private fun fallbackFontPaths(): Sequence<String> {
        val discovered = fileSystem.list("fonts").orEmpty()
            .asSequence()
            .filter(::isFontFile)
            .map { name -> "fonts/$name" }
        return (PREFERRED_FONT_FALLBACKS.asSequence() + discovered).distinct()
    }

    private fun isFontFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in FONT_EXTENSIONS

    private fun error(status: Int, reason: String) = OriginResponse(
        status,
        reason,
        "text/plain",
        emptyMap(),
        EMPTY_BODY,
    )

    private sealed interface ParsedRange {
        data object None : ParsedRange
        data object Invalid : ParsedRange
        data class Valid(val range: ByteRange) : ParsedRange
    }

    private fun parseRange(value: String?): ParsedRange {
        if (value == null) return ParsedRange.None
        val match = RANGE.matchEntire(value.trim()) ?: return ParsedRange.Invalid
        val start = match.groupValues[1].toLongOrNull() ?: return ParsedRange.Invalid
        val end = match.groupValues[2].takeIf(String::isNotEmpty)?.toLongOrNull()
            ?: if (match.groupValues[2].isEmpty()) null else return ParsedRange.Invalid
        if (end != null && end < start) return ParsedRange.Invalid
        return ParsedRange.Valid(ByteRange(start, end))
    }

    private fun Map<String, String>.header(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private companion object {
        val HOST = Regex("[a-z0-9](?:[a-z0-9.-]{0,61}[a-z0-9])?")
        val SESSION_ID = Regex("[a-zA-Z0-9-]{1,64}")
        val RANGE = Regex("bytes=(\\d+)-(\\d*)")
        val FONT_EXTENSIONS = setOf("woff", "woff2", "ttf", "otf")
        val PREFERRED_FONT_FALLBACKS = listOf(
            "fonts/mplus-1m-regular.woff",
            "fonts/mplus-2p-bold-sub.woff",
        )
        val EMPTY_BODY = ByteArrayInputStream(ByteArray(0))
    }
}