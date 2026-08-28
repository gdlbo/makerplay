package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.vfs.GameFileIndex
import io.github.gdlbo.makerplay.vfs.GameFileSystem
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GameOriginResponderTest {
    private lateinit var root: File
    private lateinit var responder: GameOriginResponder

    @Before
    fun setUp() {
        root = Files.createTempDirectory("makerplay-origin-test").toFile()
        write("index.html", "<html>game</html>")
        write("audio/theme.ogg", "0123456789")
        responder = GameOriginResponder(HOST, SESSION, GameFileSystem(GameFileIndex.build(root)))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun servesGetHeadRangesAndReloadRequests() {
        val full = request("GET", "index.html")
        assertEquals(200, full.statusCode)
        assertEquals("text/html", full.mimeType)
        assertEquals("<html>game</html>", full.body.bufferedReader().use { it.readText() })

        val partial = request("GET", "audio/theme.ogg", mapOf("Range" to "bytes=2-5"))
        assertEquals(206, partial.statusCode)
        assertEquals("bytes 2-5/10", partial.headers["Content-Range"])
        partial.body.use { assertArrayEquals("2345".toByteArray(), it.readBytes()) }

        val head = request("HEAD", "audio/theme.ogg")
        assertEquals(200, head.statusCode)
        assertEquals("10", head.headers["Content-Length"])
        assertEquals(0, head.body.readBytes().size)

        val reloaded = request(
            "GET",
            "audio/theme.ogg",
            mapOf("If-None-Match" to partial.headers.getValue("ETag"))
        )
        assertEquals(200, reloaded.statusCode)
        reloaded.body.use { assertArrayEquals("0123456789".toByteArray(), it.readBytes()) }
    }

    @Test
    fun rejectsOtherOriginsSessionsMethodsAndMalformedPaths() {
        assertEquals(403, responder.respond("GET", "https://other.test/", emptyMap()).statusCode)
        assertEquals(
            403,
            responder.respond(
                "GET",
                "https://$HOST/session/other/asset/index.html",
                emptyMap()
            ).statusCode,
        )
        assertEquals(405, request("POST", "index.html").statusCode)
        assertEquals(403, request("GET", "%2e%2e/index.html").statusCode)
        assertEquals(403, request("GET", "bad%5cpath").statusCode)
        assertEquals(403, request("GET", "bad%00path").statusCode)
        assertEquals(403, request("GET", "bad%GGpath").statusCode)
    }

    @Test
    fun rejectsSuffixAndMultipleRangesWithTheLogicalLength() {
        listOf("bytes=-5", "bytes=0-1,4-5", "items=0-1").forEach { range ->
            val response = request("GET", "audio/theme.ogg", mapOf("Range" to range))
            assertEquals(416, response.statusCode)
            assertEquals("bytes */10", response.headers["Content-Range"])
        }
        assertTrue(request("GET", "missing.file").statusCode == 404)
    }

    @Test
    fun returnsTypedEmptyFileAndReportsItWhenMissingFilesAreIgnored() {
        val ignored = mutableListOf<Pair<String, String>>()
        responder = GameOriginResponder(
            HOST,
            SESSION,
            GameFileSystem(GameFileIndex.build(root)),
            ignoreMissingFiles = true,
            onMissingFileIgnored = { path, mimeType -> ignored += path to mimeType },
        )

        val response = request("GET", "js/optional-plugin.js")

        assertEquals(200, response.statusCode)
        assertEquals("text/javascript", response.mimeType)
        assertEquals("0", response.headers["Content-Length"])
        assertArrayEquals(byteArrayOf(), response.body.readBytes())
        assertEquals(listOf("js/optional-plugin.js" to "text/javascript"), ignored)
    }

    @Test
    fun missingEncryptedAssetsStayNotFoundEvenWhenMissingFilesAreIgnored() {
        val ignored = mutableListOf<Pair<String, String>>()
        responder = GameOriginResponder(
            HOST,
            SESSION,
            GameFileSystem(GameFileIndex.build(root)),
            ignoreMissingFiles = true,
            onMissingFileIgnored = { path, mimeType -> ignored += path to mimeType },
        )

        val response = request("GET", "img/pictures/Hero.rpgmvp")

        assertEquals(404, response.statusCode)
        assertTrue(ignored.isEmpty())
    }

    @Test
    fun servesGeneratedOverlayAssetsBeforeTheImmutableGameIndex() {
        responder = GameOriginResponder(
            HOST,
            SESSION,
            GameFileSystem(GameFileIndex.build(root)),
            overlayAsset = { path ->
                if (path == "img/generated.png") {
                    OverlayAsset.Present("generated".toByteArray(), 42L)
                } else {
                    OverlayAsset.Missing
                }
            },
        )

        val response = request("GET", "img/generated.png")

        assertEquals(200, response.statusCode)
        assertEquals("image/png", response.mimeType)
        assertArrayEquals("generated".toByteArray(), response.body.readBytes())
    }

    @Test
    fun servesExistingGameFontWhenRequestedFontIsMissing() {
        write("fonts/mplus-1m-regular.woff", "fallback-font")
        val ignored = mutableListOf<Pair<String, String>>()
        responder = GameOriginResponder(
            HOST,
            SESSION,
            GameFileSystem(GameFileIndex.build(root)),
            ignoreMissingFiles = true,
            onMissingFileIgnored = { path, mimeType -> ignored += path to mimeType },
        )

        val response = request("GET", "fonts/missing-main-font.ttf")

        assertEquals(200, response.statusCode)
        assertEquals("font/woff", response.mimeType)
        assertEquals("fallback-font", response.body.bufferedReader().use { it.readText() })
        assertEquals(listOf("fonts/missing-main-font.ttf" to "font/ttf"), ignored)
    }

    @Test
    fun preservesLoggerBundleWhileRewritingImportMetaParserHazard() {
        write(
            "js/libs/logger.js",
            """
                // {"name":"Logger","status":true,"parameters":{"version":"2.1.4"}}
                exports.createDefaultLogger = function() {};
                module.exports = require("path");
                const f = new Function('p', 'return new URL(p, import.meta.url).pathname');
            """.trimIndent(),
        )
        responder = GameOriginResponder(HOST, SESSION, GameFileSystem(GameFileIndex.build(root)))

        val response = request("GET", "js/libs/logger.js")
        assertEquals(200, response.statusCode)
        val script = response.body.bufferedReader().use { it.readText() }
        assertTrue(script.contains("exports.createDefaultLogger"))
        assertTrue(script.contains("require(\"path\")"))
        assertTrue(!script.contains("import.meta"))
        assertTrue(script.contains("document.baseURI"))
    }

    @Test
    fun preservesNodeModManagerBundleForCommonJsRuntime() {
        write(
            "js/modManager.js",
            """
                // {"name":"ModManager","status":true,"parameters":{"version":"2.1.4"}}
                exports.getModsList = getModsList;
                module.exports = require("path");
            """.trimIndent(),
        )
        responder = GameOriginResponder(HOST, SESSION, GameFileSystem(GameFileIndex.build(root)))

        val response = request("GET", "js/modManager.js")
        val script = response.body.bufferedReader().use { it.readText() }
        assertTrue(script.contains("exports.getModsList = getModsList"))
        assertTrue(script.contains("require(\"path\")"))
    }

    private fun request(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
    ): OriginResponse = responder.respond(
        method,
        "https://$HOST/session/$SESSION/asset/$path",
        headers,
    )

    private fun write(path: String, value: String) {
        File(root, path).apply {
            parentFile?.mkdirs()
            writeText(value)
        }
    }

    private companion object {
        const val SESSION = "session-1"
        const val HOST = "g-test.game.local"
    }
}
