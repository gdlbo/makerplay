package io.github.gdlbo.makerplay.vfs

import java.util.Locale

@JvmInline
value class GamePath private constructor(val value: String) {
    val folded: String get() = value.lowercase(Locale.ROOT)

    companion object {
        fun parse(raw: String): GamePath {
            require(raw.length <= MAX_PATH_LENGTH) { "Game path is too long" }
            require(raw.none { it.code == 0 || it.code < 0x20 }) { "Game path contains control characters" }
            require(!DRIVE_PATH.matches(raw)) { "Game path must be relative" }
            val segments = raw.replace('\\', '/').trimStart('/').split('/')
            require(segments.none { it == ".." }) { "Game path must not escape its root" }
            val normalized = segments.filterNot { it.isEmpty() || it == "." }.joinToString("/")
            require(normalized.isNotBlank()) { "Game path must not be blank" }
            require(normalized.length <= MAX_PATH_LENGTH) { "Game path is too long" }
            return GamePath(normalized)
        }

        private const val MAX_PATH_LENGTH = 1024
        private val DRIVE_PATH = Regex("^[a-zA-Z]:.*")
    }
}