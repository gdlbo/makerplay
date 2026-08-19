package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.runtime.api.FileGameSaveStore
import io.github.gdlbo.makerplay.runtime.api.GameSaveStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

class SaveBridgeProtocolTest {
    @Test
    fun writesReadsListsAndDeletesWithinOneGameNamespace() {
        val store = MemoryStore()
        val protocol = SaveBridgeProtocol("game-1", store)
        val payload = byteArrayOf(0, 1, 0x7f, 0x80.toByte(), 0xff.toByte())

        assertOk(protocol.handle(request("write", "file1", payload)))
        assertArrayEquals(payload, store.read("game-1", "file1"))

        val read = response(protocol.handle(request("read", "file1")))
        assertTrue(read.getValue("ok").jsonPrimitive.boolean)
        assertArrayEquals(
            payload,
            Base64.getDecoder().decode(read.getValue("data").jsonPrimitive.content)
        )

        val listed = response(protocol.handle("""{"v":1,"id":"r3","op":"list"}"""))
        assertEquals("[\"file1\"]", listed.getValue("data").toString())
        assertOk(protocol.handle(request("delete", "file1")))
        assertNull(store.read("game-1", "file1"))
    }

    @Test
    fun missingReadReturnsSuccessfulNull() {
        val response =
            response(SaveBridgeProtocol("game", MemoryStore()).handle(request("read", "missing")))
        assertTrue(response.getValue("ok").jsonPrimitive.boolean)
        assertFalse(response.containsKey("data"))
    }

    @Test
    fun existsDoesNotReturnTheSavePayload() {
        val store = MemoryStore()
        val protocol = SaveBridgeProtocol("game", store)
        assertOk(protocol.handle(request("write", "file1", byteArrayOf(1, 2, 3))))
        val found = response(protocol.handle("""{"v":1,"id":"r2","op":"exists","key":"file1"}"""))
        assertTrue(found.getValue("data").jsonPrimitive.boolean)
        assertFalse(found.containsKey("error"))
    }

    @Test
    fun rejectsMalformedVersionOperationIdentifiersAndBase64() {
        val protocol = SaveBridgeProtocol("game", MemoryStore())
        listOf(
            "not-json",
            """{"v":2,"id":"r1","op":"list"}""",
            """{"v":1,"id":"../bad","op":"list"}""",
            """{"v":1,"id":"r1","op":"unknown"}""",
            """{"v":1,"id":"r1","op":"write","key":"file1","data":"A==="}""",
            """{"v":1,"id":"r1","op":"write","key":"../file","data":"AA=="}""",
        ).forEach { message ->
            assertFalse(response(protocol.handle(message)).getValue("ok").jsonPrimitive.boolean)
        }
    }

    @Test
    fun reportsFailedBridgeCallsWithoutPayload() {
        val failures = mutableListOf<Pair<String, String?>>()
        val protocol = SaveBridgeProtocol("game", MemoryStore()) { code, failureClass ->
            failures += code to failureClass
        }

        protocol.handle("not-json")
        protocol.handle("""{"v":1,"id":"r1","op":"unknown"}""")

        assertEquals(2, failures.size)
        assertEquals("invalid", failures[0].first)
        assertTrue(failures[0].second?.isNotBlank() == true)
        assertEquals("invalid" to null, failures[1])
    }

    @Test
    fun boundsMessagesBeforeBase64AllocationAndProvidesBusyReply() {
        val protocol = SaveBridgeProtocol("game", MemoryStore())
        val oversized = "A".repeat(((SaveBridgeProtocol.MAX_PAYLOAD_BYTES + 2) / 3) * 4 + 4)
        val result = response(
            protocol.handle("""{"v":1,"id":"large","op":"write","key":"file1","data":"$oversized"}"""),
        )
        assertFalse(result.getValue("ok").jsonPrimitive.boolean)
        assertEquals(
            "busy",
            response(protocol.busy("""{"id":"r9"}""")).getValue("error").jsonPrimitive.content
        )
    }

    @Test
    fun nativeBridgePayloadSurvivesStoreAndProtocolRecreation() {
        val root = Files.createTempDirectory("makerplay-save-bridge").toFile()
        try {
            val bytes = byteArrayOf(0x78, 0x01, 0x00, 0xff.toByte())
            assertOk(
                SaveBridgeProtocol("game-a", FileGameSaveStore(root)).handle(
                    request(
                        "write",
                        "file1",
                        bytes
                    )
                )
            )

            val restarted = SaveBridgeProtocol("game-a", FileGameSaveStore(root))
            val read = response(restarted.handle(request("read", "file1")))
            assertArrayEquals(
                bytes,
                Base64.getDecoder().decode(read.getValue("data").jsonPrimitive.content)
            )

            val otherGame = response(
                SaveBridgeProtocol("game-b", FileGameSaveStore(root)).handle(
                    request(
                        "read",
                        "file1"
                    )
                ),
            )
            assertFalse(otherGame.containsKey("data"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun request(op: String, key: String, payload: ByteArray? = null): String {
        val data =
            payload?.let { ",\"data\":\"${Base64.getEncoder().encodeToString(it)}\"" }.orEmpty()
        return """{"v":1,"id":"r1","op":"$op","key":"$key"$data}"""
    }

    private fun assertOk(value: String) {
        assertTrue(response(value).getValue("ok").jsonPrimitive.boolean)
    }

    private fun response(value: String) = Json.parseToJsonElement(value).jsonObject

    private class MemoryStore : GameSaveStore {
        private val data = linkedMapOf<Pair<String, String>, ByteArray>()

        override fun read(gameId: String, key: String): ByteArray? = data[gameId to key]?.copyOf()
        override fun write(gameId: String, key: String, payload: ByteArray) {
            require(!key.contains('/'))
            data[gameId to key] = payload.copyOf()
        }

        override fun delete(gameId: String, key: String): Boolean =
            data.remove(gameId to key) != null

        override fun keys(gameId: String): Set<String> =
            data.keys.filter { it.first == gameId }.map { it.second }.toSet()
    }
}
