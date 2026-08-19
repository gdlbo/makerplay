package io.github.gdlbo.makerplay.vfs

import java.util.Locale

object MimeTypes {
    fun forPath(path: GamePath): String = when (
        path.value.substringAfterLast('.', "").lowercase(Locale.ROOT)
    ) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js", "mjs" -> "text/javascript"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "txt", "csv" -> "text/plain"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "heic", "heif" -> "image/heif"
        "ico" -> "image/x-icon"
        "svg" -> "image/svg+xml"
        "ogg", "oga" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "opus" -> "audio/ogg"
        "weba" -> "audio/webm"
        "webm" -> "video/webm"
        "mp4", "m4v" -> "video/mp4"
        "ogv" -> "video/ogg"
        "mkv" -> "video/x-matroska"
        "3gp" -> "video/3gpp"
        "vtt" -> "text/vtt"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }
}