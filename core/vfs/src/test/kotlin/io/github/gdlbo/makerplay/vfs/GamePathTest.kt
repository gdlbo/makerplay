package io.github.gdlbo.makerplay.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Locale

class GamePathTest {
    @Test
    fun normalizesWindowsSeparatorsWithoutChangingRelativeOwnership() {
        assertEquals(
            "img/characters/Actor1.png",
            GamePath.parse("img\\characters\\Actor1.png").value
        )
    }

    @Test
    fun rejectsParentTraversal() {
        assertThrows(IllegalArgumentException::class.java) {
            GamePath.parse("img/../../outside.png")
        }
    }

    @Test
    fun normalizesDotAndDuplicateSeparators() {
        assertEquals("img/Icon.png", GamePath.parse("/img//./Icon.png").value)
    }

    @Test
    fun rejectsDriveAndControlPaths() {
        assertThrows(IllegalArgumentException::class.java) { GamePath.parse("C:\\game\\index.html") }
        assertThrows(IllegalArgumentException::class.java) { GamePath.parse("C:index.html") }
        assertThrows(IllegalArgumentException::class.java) { GamePath.parse("img/evil\u0000.png") }
    }

    @Test
    fun caseFoldDoesNotDependOnDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("img/icon.png", GamePath.parse("IMG/ICON.PNG").folded)
            assertEquals("image/gif", MimeTypes.forPath(GamePath.parse("img/ICON.GIF")))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun recognizesCommonBrowserMediaTypes() {
        val expected = mapOf(
            "sound.aac" to "audio/aac",
            "sound.flac" to "audio/flac",
            "sound.opus" to "audio/ogg",
            "sound.weba" to "audio/webm",
            "movie.ogv" to "video/ogg",
            "movie.mkv" to "video/x-matroska",
            "movie.3gp" to "video/3gpp",
            "captions.vtt" to "text/vtt",
            "image.bmp" to "image/bmp",
            "image.avif" to "image/avif",
            "image.heic" to "image/heif",
            "favicon.ico" to "image/x-icon",
        )

        expected.forEach { (path, mimeType) ->
            assertEquals(path, mimeType, MimeTypes.forPath(GamePath.parse(path)))
        }
    }
}
