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
    private val prefetchLookup: ((String) -> ByteArray?)? = null,
    private val overlayMetadata: ((String) -> OverlayAsset)? = null,
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
        val overlay = if (method == "HEAD" && overlayMetadata != null) {
            overlayMetadata.invoke(path)
        } else overlayAsset?.invoke(path)
        if (overlay is OverlayAsset.Deleted) return missing(path, method, requestedRange)
        return when (overlay) {
            is OverlayAsset.Present -> foundOverlay(method, path, requestedRange, overlay.bytes, overlay.bytes.size.toLong(), overlay.lastModifiedMillis)
            is OverlayAsset.Metadata -> foundOverlay(method, path, requestedRange, null, overlay.length, overlay.lastModifiedMillis)
            else -> prefetchedFullFile(method, path, requestedRange)
                ?: when (val opened = fileSystem.open(path, requestedRange)) {
                    VfsOpenResult.Missing -> missing(path, method, requestedRange)
                    is VfsOpenResult.RequiresCodec,
                    is VfsOpenResult.InvalidAsset,
                        -> error(500, "Invalid Asset")

                    is VfsOpenResult.RangeNotSatisfiable -> rangeError(opened.completeLength)
                    is VfsOpenResult.Found -> found(method, path, requestedRange, opened)
                }
        }
    }

    /** Fast path for bytes already staged during boot prefetch. */
    private fun prefetchedFullFile(
        method: String,
        path: String,
        requestedRange: ByteRange?,
    ): OriginResponse? {
        if (requestedRange != null) return null
        if (path.endsWith(".js", ignoreCase = true)) return null
        val asset = fileSystem.resolve(path) ?: return null
        val bytes = prefetchLookup?.invoke(path) ?: return null
        android.util.Log.i(
            "MakerPlay",
            "native.io op=origin-cache-hit path=$path bytes=${bytes.size}",
        )
        val payload = if (path.equals("data/System.json", ignoreCase = true)) {
            rewriteSystemJsonClearEncryption(ByteArrayInputStream(bytes))?.let { rewritten ->
                rewritten.stream.use { it.readBytes() }
            } ?: bytes
        } else {
            bytes
        }
        val headers = linkedMapOf(
            "Accept-Ranges" to "bytes",
            "Content-Length" to payload.size.toString(),
            "ETag" to asset.entityTag,
        )
        return OriginResponse(
            200,
            "OK",
            asset.mimeType,
            headers,
            if (method == "HEAD") EMPTY_BODY else ByteArrayInputStream(payload),
        )
    }

    private fun foundOverlay(
        method: String,
        path: String,
        requestedRange: ByteRange?,
        bytes: ByteArray?,
        completeLength: Long,
        lastModifiedMillis: Long,
    ): OriginResponse {
        val start = requestedRange?.startInclusive ?: 0L
        if (requestedRange != null && start >= completeLength) return rangeError(completeLength)
        val requestedEnd = requestedRange?.endInclusive
        val end = when {
            completeLength == 0L -> -1L
            requestedEnd == null -> completeLength - 1
            else -> minOf(requestedEnd, completeLength - 1)
        }
        val contentLength = if (completeLength == 0L) 0L else end - start + 1
        val headers = linkedMapOf(
            "Accept-Ranges" to "bytes",
            "Content-Length" to contentLength.toString(),
            "ETag" to "W/\"overlay-${lastModifiedMillis.toString(16)}-${
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
            if (method == "HEAD") {
                EMPTY_BODY
            } else {
                ByteArrayInputStream(requireNotNull(bytes), start.toInt(), contentLength.toInt())
            },
        )
    }

    private fun found(
        method: String,
        path: String,
        requestedRange: ByteRange?,
        opened: VfsOpenResult.Found,
    ): OriginResponse {
        val rewrittenStream: InputStream?
        val rewrittenLength: Long?
        when {
            requestedRange == null && path.equals("data/System.json", ignoreCase = true) -> {
                val systemRewrite = rewriteSystemJsonClearEncryption(opened.stream)
                rewrittenStream = systemRewrite?.stream
                rewrittenLength = systemRewrite?.length
            }
            requestedRange == null -> {
                val jsRewrite = rewriteBrowserIncompatibleJavaScript(path, opened.stream)
                rewrittenStream = jsRewrite?.stream
                rewrittenLength = jsRewrite?.length
            }
            else -> {
                rewrittenStream = null
                rewrittenLength = null
            }
        }
        val responseStream = rewrittenStream ?: opened.stream
        val responseLength = rewrittenLength ?: opened.contentLength
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

    /**
     * When native VFS codec owns decryption, clear RPG Maker JS Decrypter flags so assets
     * are requested as logical .png/.ogg and decoded natively instead of dual-decrypting.
     */
    private fun rewriteSystemJsonClearEncryption(source: InputStream): RewrittenBody? {
        if (!io.github.gdlbo.makerplay.runtime.webview.nativebridge.RpgmNative.isAvailable()) {
            return null
        }
        val original = source.use { it.readBytes() }
        val text = original.toString(Charsets.UTF_8)
        if (!text.contains("hasEncryptedImages") && !text.contains("hasEncryptedAudio")) {
            return RewrittenBody(ByteArrayInputStream(original), original.size.toLong())
        }
        val rewritten = text
            .replace(Regex("\"hasEncryptedImages\"\\s*:\\s*true"), "\"hasEncryptedImages\":false")
            .replace(Regex("\"hasEncryptedAudio\"\\s*:\\s*true"), "\"hasEncryptedAudio\":false")
        if (rewritten == text) {
            return RewrittenBody(ByteArrayInputStream(original), original.size.toLong())
        }
        android.util.Log.i("MakerPlay", "native.io op=system-json-clear-encryption")
        val bytes = rewritten.toByteArray(Charsets.UTF_8)
        return RewrittenBody(ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    private data class RewrittenBody(val stream: InputStream, val length: Long)

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
        // Empty 200 bodies make RPG Maker's JS Decrypter treat the response as a
        // successful encrypted asset (status < 400) and throw "Header is wrong".
        if (isEncryptedRpgMakerAsset(path)) return error(404, "Not Found")
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
        // DataManager JSON.parse's empty XHR bodies ("Unexpected end of JSON input").
        val jsonPlaceholder = missingJsonPlaceholder(path)?.toByteArray(Charsets.UTF_8)
        if (jsonPlaceholder != null) {
            return OriginResponse(
                statusCode = 200,
                reasonPhrase = "OK",
                mimeType = mimeType,
                headers = mapOf("Content-Length" to jsonPlaceholder.size.toString()),
                body = if (method == "HEAD") EMPTY_BODY else ByteArrayInputStream(jsonPlaceholder),
            )
        }
        return OriginResponse(
            statusCode = 200,
            reasonPhrase = "OK",
            mimeType = mimeType,
            headers = mapOf("Content-Length" to "0"),
            body = EMPTY_BODY,
        )
    }

    private fun missingJsonPlaceholder(path: String): String? {
        if (!path.endsWith(".json", ignoreCase = true)) return null
        val name = path.substringAfterLast('/').lowercase(Locale.ROOT)
        return when {
            name == "system.json" || MAP_OBJECT_JSON.matches(name) -> "{}"
            else -> "[]"
        }
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

    private fun isEncryptedRpgMakerAsset(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        return ENCRYPTED_ASSET_SUFFIXES.any(lower::endsWith)
    }

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
        val ENCRYPTED_ASSET_SUFFIXES = listOf(
            ".rpgmvp",
            ".rpgmvo",
            ".rpgmvm",
            ".png_",
            ".ogg_",
            ".m4a_",
        )
        val MAP_OBJECT_JSON = Regex("map\\d+\\.json")
        val EMPTY_BODY = ByteArrayInputStream(ByteArray(0))
    }
}
