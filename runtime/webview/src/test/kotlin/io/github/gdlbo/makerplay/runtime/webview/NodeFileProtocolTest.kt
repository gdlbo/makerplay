package io.github.gdlbo.makerplay.runtime.webview

import android.webkit.JavascriptInterface
import io.github.gdlbo.makerplay.runtime.api.FileGameSaveStore
import io.github.gdlbo.makerplay.runtime.api.GameSaveStore
import io.github.gdlbo.makerplay.vfs.GameFileIndex
import io.github.gdlbo.makerplay.vfs.GameFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.Base64

class NodeFileProtocolTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readsGameFilesAndRejectsPathsOutsideVirtualRoots() {
        val gameRoot = temporaryFolder.newFolder("game")
        File(gameRoot, "js/module.js").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("module.exports = 42;")
        }
        val protocol = protocol(gameRoot)

        val exists = response(protocol, "exists", "/game/js/module.js")
        assertTrue(exists.jsonObject.getValue("data").jsonPrimitive.content.toBoolean())
        val read = response(protocol, "read", "/game/js/module.js")
        val payload =
            Base64.getDecoder().decode(read.jsonObject.getValue("data").jsonPrimitive.content)
        assertEquals("module.exports = 42;", payload.decodeToString())
        val entries = response(protocol, "readdir", "/game/js").jsonObject
            .getValue("data").toString()
        assertTrue(entries.contains("module.js"))

        listOf("/game/../secret", "C:/secret", "/storage/emulated/0/secret").forEach { path ->
            val rejected = response(protocol, "read", path)
            assertFalse(rejected.jsonObject.getValue("ok").jsonPrimitive.content.toBoolean())
        }
    }

    @Test
    fun reportsMissingFilesAsEnoentCompatibleFailures() {
        val protocol = protocol(temporaryFolder.newFolder("game"))

        listOf("/game/save/config.rpgsave", "/data/missing.json").forEach { path ->
            val missing = response(protocol, "read", path).jsonObject

            assertFalse(missing.getValue("ok").jsonPrimitive.content.toBoolean())
            assertEquals("missing", missing.getValue("error").jsonPrimitive.content)
        }
    }

    @Test
    fun sharesMvSaveFilesWithCommonJsFsOperations() {
        val saveStore = FileGameSaveStore(temporaryFolder.newFolder("saves"))
        saveStore.write("game", "file3", "slot-three".encodeToByteArray())
        val protocol = protocol(temporaryFolder.newFolder("game"), saveStore = saveStore)

        assertTrue(response(protocol, "exists", "/game/save/file3.rpgsave").dataBoolean())
        assertEquals("slot-three", readText(protocol, "/game/save/file3.rpgsave"))
        assertTrue(
            response(
                protocol,
                "copy",
                "/game/save/file3.rpgsave",
                target = "/game/save/lastsave.autobak",
            ).ok(),
        )
        assertEquals(
            "slot-three",
            saveStore.read("game", "node-lastsave.autobak")?.decodeToString(),
        )

        val slotSeven = Base64.getEncoder().encodeToString("slot-seven".encodeToByteArray())
        assertTrue(response(protocol, "write", "/game/save/file7.rpgsave", slotSeven).ok())
        assertEquals("slot-seven", saveStore.read("game", "file7")?.decodeToString())
        val entries =
            response(protocol, "readdir", "/game/save").jsonObject.getValue("data").toString()
        assertTrue(entries.contains("file3.rpgsave"))
        assertTrue(entries.contains("file7.rpgsave"))
        assertTrue(entries.contains("lastsave.autobak"))

        assertTrue(response(protocol, "unlink", "/game/save/file3.rpgsave").ok())
        assertFalse("file3" in saveStore.keys("game"))
    }

    @Test
    fun overlaysGameWritesInsideDataRootAndBridgeRequiresItsToken() {
        val gameRoot = temporaryFolder.newFolder("game")
        val dataRoot = temporaryFolder.newFolder("data")
        val protocol = protocol(gameRoot, dataRoot)
        val encoded = Base64.getEncoder().encodeToString("settings".encodeToByteArray())

        assertTrue(response(protocol, "write", "/data/config/value", encoded).ok())
        assertEquals("settings", File(dataRoot, "config/value").readText())
        assertTrue(response(protocol, "write", "/logs/runtime.log", encoded).ok())
        assertEquals("settings", File(dataRoot, "logs/runtime.log").readText())
        assertTrue(
            response(
                protocol,
                "rename",
                "/logs/runtime.log",
                target = "/logs/renamed.log"
            ).ok()
        )
        assertEquals("settings", File(dataRoot, "logs/renamed.log").readText())
        assertTrue(response(protocol, "write", "/game/config", encoded).ok())
        assertEquals("settings", File(dataRoot, "game-overlay/config").readText())
        assertFalse(File(gameRoot, "config").exists())
        val overlaid = response(protocol, "read", "/game/config")
        val overlaidPayload = Base64.getDecoder().decode(
            overlaid.jsonObject.getValue("data").jsonPrimitive.content,
        )
        assertEquals("settings", overlaidPayload.decodeToString())

        val bridge = SynchronousNodeBridge("secret", protocol)
        val request = request("exists", "/data/config/value")
        assertFalse(Json.parseToJsonElement(bridge.transact("wrong", request)).jsonObject.ok())
        assertTrue(Json.parseToJsonElement(bridge.transact("secret", request)).jsonObject.ok())
        val exposed = SynchronousNodeBridge::class.java.declaredMethods.filter {
            it.getAnnotation(JavascriptInterface::class.java) != null
        }
        assertEquals(listOf("transact"), exposed.map { it.name })
    }

    @Test
    fun rejectsTraversalAndRuntimeInternalDataPaths() {
        val gameRoot = temporaryFolder.newFolder("game")
        val dataRoot = temporaryFolder.newFolder("data")
        val outside = temporaryFolder.newFolder("outside")
        val protocol = protocol(gameRoot, dataRoot)
        val encoded = Base64.getEncoder().encodeToString("blocked".encodeToByteArray())

        listOf(
            "/game/../escape.bin",
            "/data/../escape.bin",
            "../escape.bin",
            "C:/escape.bin",
            "//server/share/escape.bin",
            "/data/game-overlay/escape.bin",
            "/data/game-deleted/escape.bin",
            "/data/.makerplay-last-cleanup",
        ).forEach { path ->
            assertFalse(response(protocol, "write", path, encoded).ok())
        }

        val dataEntries = response(protocol, "readdir", "/data").jsonObject
            .getValue("data").toString()
        assertFalse(dataEntries.contains("game-overlay"))
        assertFalse(dataEntries.contains("game-deleted"))
        assertFalse(dataEntries.contains(".makerplay-last-cleanup"))
        assertTrue(outside.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun rejectsSymlinkedGameOverlayRoot() {
        val gameRoot = temporaryFolder.newFolder("game")
        val dataRoot = temporaryFolder.newFolder("data")
        val outside = temporaryFolder.newFolder("outside")
        createSymbolicLinkOrSkip(File(dataRoot, "game-overlay"), outside)

        assertThrows(IllegalArgumentException::class.java) {
            protocol(gameRoot, dataRoot)
        }
    }

    @Test
    fun allowsDataRootBelowSymlinkedSystemAncestor() {
        val gameRoot = temporaryFolder.newFolder("game")
        val actualParent = temporaryFolder.newFolder("actual-parent")
        val linkedParent = File(temporaryFolder.root, "linked-parent")
        createSymbolicLinkOrSkip(linkedParent, actualParent)

        val protocol = protocol(gameRoot, File(linkedParent, "data"))

        assertTrue(response(protocol, "mkdir", "/data/config").ok())
        assertTrue(File(actualParent, "data/config").isDirectory)
    }

    @Test
    fun rejectsNestedSymlinkWritesAndDoesNotFollowLinksDuringRecursiveRemoval() {
        val gameRoot = temporaryFolder.newFolder("game")
        val dataRoot = temporaryFolder.newFolder("data")
        val outside = temporaryFolder.newFolder("outside")
        val retained = File(outside, "keep.bin").apply { writeText("keep") }
        val protocol = protocol(gameRoot, dataRoot)
        val encoded = Base64.getEncoder().encodeToString("blocked".encodeToByteArray())

        createSymbolicLinkOrSkip(File(dataRoot, "game-overlay/link"), outside)
        assertFalse(response(protocol, "write", "/game/link/escape.bin", encoded).ok())
        assertFalse(File(outside, "escape.bin").exists())

        val sandbox = File(dataRoot, "sandbox").apply { mkdirs() }
        createSymbolicLinkOrSkip(File(sandbox, "outside"), outside)
        assertTrue(response(protocol, "rm", "/data/sandbox", recursive = true).ok())
        assertTrue(retained.isFile)
    }

    @Test
    fun supportsNativeCopyTruncateAndBoundedRecursiveRemoval() {
        val protocol = protocol(temporaryFolder.newFolder("game"))
        val payload = Base64.getEncoder().encodeToString("abcdef".encodeToByteArray())

        assertTrue(response(protocol, "write", "/data/folder/source", payload).ok())
        val entries = response(protocol, "readdirStat", "/data/folder").jsonObject
            .getValue("data").toString()
        assertTrue(entries.contains("source"))
        assertTrue(
            response(
                protocol,
                "copy",
                "/data/folder/source",
                target = "/data/folder/copy"
            ).ok()
        )
        assertTrue(response(protocol, "truncate", "/data/folder/copy", size = 2).ok())
        val replacement = Base64.getEncoder().encodeToString("XY".encodeToByteArray())
        assertTrue(
            response(
                protocol,
                "writeRange",
                "/data/folder/copy",
                replacement,
                position = 1
            ).ok()
        )
        val range = response(protocol, "readRange", "/data/folder/copy", position = 1, size = 2)
        assertEquals(
            "XY",
            Base64.getDecoder().decode(range.jsonObject.getValue("data").jsonPrimitive.content)
                .decodeToString(),
        )
        val read = response(protocol, "read", "/data/folder/copy")
        assertEquals(
            "aXY",
            Base64.getDecoder().decode(read.jsonObject.getValue("data").jsonPrimitive.content)
                .decodeToString()
        )
        assertTrue(response(protocol, "rm", "/data/folder", recursive = true).ok())
        assertFalse(
            response(protocol, "exists", "/data/folder/source").jsonObject
                .getValue("data").jsonPrimitive.content.toBoolean(),
        )
    }

    @Test
    fun immutableGameFilesSupportAppendDeleteAndOverlayRecreation() {
        val gameRoot = temporaryFolder.newFolder("game")
        File(gameRoot, "js/base.js").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("base")
        }
        val protocol = protocol(gameRoot)
        val appended = Base64.getEncoder().encodeToString("!".encodeToByteArray())
        assertTrue(response(protocol, "append", "/game/js/base.js", appended).ok())
        assertEquals("base!", readText(protocol, "/game/js/base.js"))
        assertTrue(response(protocol, "unlink", "/game/js/base.js").ok())
        assertFalse(
            response(protocol, "exists", "/game/js/base.js").jsonObject
                .getValue("data").jsonPrimitive.content.toBoolean(),
        )
        assertTrue(response(protocol, "unlink", "/game/js/base.js").ok())
        val replacement = Base64.getEncoder().encodeToString("replacement".encodeToByteArray())
        assertTrue(response(protocol, "write", "/game/js/base.js", replacement).ok())
        assertEquals("replacement", readText(protocol, "/game/js/base.js"))
    }

    @Test
    fun softlyRemovesOnlyExpiredManagedGarbage() {
        val dataRoot = temporaryFolder.newFolder("data")
        val temp = File(dataRoot, "tmp").apply { mkdirs() }
        val expired = File(temp, "expired.bin").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)
        }
        val recent = File(temp, "recent.bin").apply { writeBytes(byteArrayOf(2)) }
        val persistent = File(dataRoot, "player-data.bin").apply { writeBytes(byteArrayOf(3)) }

        protocol(temporaryFolder.newFolder("game"), dataRoot)

        assertFalse(expired.exists())
        assertTrue(recent.isFile)
        assertTrue(persistent.isFile)
    }

    private fun protocol(
        gameRoot: File,
        dataRoot: File = temporaryFolder.newFolder("data"),
        saveStore: GameSaveStore? = null,
    ) = NodeFileProtocol(
        GameFileSystem(GameFileIndex.build(gameRoot)),
        dataRoot,
        gameId = "game".takeIf { saveStore != null },
        saveStore = saveStore,
    )

    private fun request(
        op: String,
        path: String,
        data: String? = null,
        target: String? = null,
        size: Int? = null,
        position: Int? = null,
        append: Boolean? = null,
        recursive: Boolean? = null,
        force: Boolean? = null,
    ): String = buildJsonObject {
        put("v", JsonPrimitive(1))
        put("id", JsonPrimitive("test-1"))
        put("op", JsonPrimitive(op))
        put("path", JsonPrimitive(path))
        data?.let { put("data", JsonPrimitive(it)) }
        target?.let { put("target", JsonPrimitive(it)) }
        size?.let { put("size", JsonPrimitive(it)) }
        position?.let { put("position", JsonPrimitive(it)) }
        append?.let { put("append", JsonPrimitive(it)) }
        recursive?.let { put("recursive", JsonPrimitive(it)) }
        force?.let { put("force", JsonPrimitive(it)) }
    }.toString()

    private fun response(
        protocol: NodeFileProtocol,
        op: String,
        path: String,
        data: String? = null,
        target: String? = null,
        size: Int? = null,
        position: Int? = null,
        append: Boolean? = null,
        recursive: Boolean? = null,
        force: Boolean? = null,
    ) = Json.parseToJsonElement(
        protocol.handle(request(op, path, data, target, size, position, append, recursive, force)),
    )

    private fun readText(protocol: NodeFileProtocol, path: String): String = Base64.getDecoder()
        .decode(response(protocol, "read", path).jsonObject.getValue("data").jsonPrimitive.content)
        .decodeToString()

    private fun createSymbolicLinkOrSkip(link: File, target: File) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (error: Exception) {
            Assume.assumeNoException(error)
        }
    }

    private fun kotlinx.serialization.json.JsonElement.ok(): Boolean = jsonObject.ok()

    private fun kotlinx.serialization.json.JsonElement.dataBoolean(): Boolean =
        jsonObject.getValue("data").jsonPrimitive.content.toBoolean()

    private fun kotlinx.serialization.json.JsonObject.ok(): Boolean =
        getValue("ok").jsonPrimitive.content.toBoolean()
}
