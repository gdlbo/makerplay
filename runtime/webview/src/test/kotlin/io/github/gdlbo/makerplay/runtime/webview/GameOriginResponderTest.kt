package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.runtime.webview.nativebridge.RpgmNative
import io.github.gdlbo.makerplay.vfs.GameFileIndex
import io.github.gdlbo.makerplay.vfs.GameFileSystem
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
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
    fun overlayHeadUsesMetadataWithoutLoadingPayload() {
        responder = GameOriginResponder(
            HOST, SESSION, GameFileSystem(GameFileIndex.build(root)),
            overlayAsset = { error("HEAD must not load overlay bytes") },
            overlayMetadata = { OverlayAsset.Metadata(1_000_000, 42L) },
        )
        val response = request("HEAD", "img/generated.png", mapOf("Range" to "bytes=7-11"))
        assertEquals(206, response.statusCode)
        assertEquals("5", response.headers["Content-Length"])
        assertEquals("bytes 7-11/1000000", response.headers["Content-Range"])
        assertEquals(-1, response.body.read())
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
    fun streamsUncachedAssetsAndKeepsHeadBodiesEmpty() {
        val bytes = ByteArray(512 * 1024) { index -> index.toByte() }
        File(root, "audio/large.ogg").apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
        responder = GameOriginResponder(HOST, SESSION, GameFileSystem(GameFileIndex.build(root)))

        val get = request("GET", "audio/large.ogg")
        assertEquals(bytes.size.toString(), get.headers["Content-Length"])
        assertFalse(get.body is ByteArrayInputStream)
        get.body.use { assertArrayEquals(bytes, it.readBytes()) }

        val head = request("HEAD", "audio/large.ogg")
        assertEquals(bytes.size.toString(), head.headers["Content-Length"])
        head.body.use { assertEquals(-1, it.read()) }
    }

    @Test
    fun systemJsonRemainsReadableWhenNativeCodecIsUnavailable() {
        val systemJson = """{"hasEncryptedImages":true,"hasEncryptedAudio":true}"""
        write("data/System.json", systemJson)
        responder = GameOriginResponder(HOST, SESSION, GameFileSystem(GameFileIndex.build(root)))
        RpgmNative.setForceDisabled(true)
        try {
            val response = request("GET", "data/System.json")

            assertEquals(systemJson.toByteArray().size.toString(), response.headers["Content-Length"])
            assertEquals(systemJson, response.body.bufferedReader().use { it.readText() })
        } finally {
            RpgmNative.setForceDisabled(false)
        }
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
    fun missingJsonFilesReturnParseablePlaceholdersWhenIgnored() {
        val ignored = mutableListOf<Pair<String, String>>()
        responder = GameOriginResponder(
            HOST,
            SESSION,
            GameFileSystem(GameFileIndex.build(root)),
            ignoreMissingFiles = true,
            onMissingFileIgnored = { path, mimeType -> ignored += path to mimeType },
        )

        val actors = request("GET", "data/Actors.json")
        assertEquals(200, actors.statusCode)
        assertEquals("application/json", actors.mimeType)
        assertEquals("[]", actors.body.bufferedReader().use { it.readText() })
        assertEquals("2", actors.headers["Content-Length"])

        val system = request("GET", "data/System.json")
        assertEquals("{}", system.body.bufferedReader().use { it.readText() })

        val map = request("GET", "data/Map001.json")
        assertEquals("{}", map.body.bufferedReader().use { it.readText() })

        val infos = request("GET", "data/MapInfos.json")
        assertEquals("[]", infos.body.bufferedReader().use { it.readText() })

        val head = request("HEAD", "data/Actors.json")
        assertEquals("2", head.headers["Content-Length"])
        assertEquals(-1, head.body.read())

        assertEquals(
            listOf(
                "data/Actors.json" to "application/json",
                "data/System.json" to "application/json",
                "data/Map001.json" to "application/json",
                "data/MapInfos.json" to "application/json",
                "data/Actors.json" to "application/json",
            ),
            ignored,
        )
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

        val partial = request("GET", "img/generated.png", mapOf("Range" to "bytes=2-5"))
        assertEquals(206, partial.statusCode)
        assertEquals("4", partial.headers["Content-Length"])
        assertEquals("bytes 2-5/9", partial.headers["Content-Range"])
        partial.body.use { assertArrayEquals("nera".toByteArray(), it.readBytes()) }

        val head = request("HEAD", "img/generated.png", mapOf("Range" to "bytes=2-5"))
        assertEquals("4", head.headers["Content-Length"])
        head.body.use { assertEquals(-1, it.read()) }
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
        assertEquals(script.toByteArray().size.toString(), response.headers["Content-Length"])
        assertTrue(script.contains("exports.createDefaultLogger"))
        assertTrue(script.contains("require(\"path\")"))
        assertTrue(!script.contains("import.meta"))
        assertTrue(script.contains("document.baseURI"))

        val head = request("HEAD", "js/libs/logger.js")
        assertEquals(script.toByteArray().size.toString(), head.headers["Content-Length"])
        head.body.use { assertEquals(-1, it.read()) }
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
