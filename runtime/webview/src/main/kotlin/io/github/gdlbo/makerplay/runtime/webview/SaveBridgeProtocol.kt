package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.runtime.api.GameSaveCorruptionException
import io.github.gdlbo.makerplay.runtime.api.GameSaveLimitException
import io.github.gdlbo.makerplay.runtime.api.GameSaveStorageException
import io.github.gdlbo.makerplay.runtime.api.GameSaveStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

class SaveBridgeProtocol(
    private val gameId: String,
    private val store: GameSaveStore,
    private val onFailure: (code: String, failureClass: String?) -> Unit = { _, _ -> },
) {
    fun handle(message: String): String {
        var requestId = INVALID_ID
        return try {
            require(message.length <= MAX_MESSAGE_CHARS) { "Message is too large" }
            val request = Json.parseToJsonElement(message).jsonObject
            requestId = request.string("id")
            require(ID.matches(requestId)) { "Invalid request ID" }
            require(request.int("v") == VERSION) { "Unsupported protocol version" }
            when (request.string("op")) {
                "write" -> {
                    val key = request.string("key")
                    val payload = decodePayload(request.string("data"))
                    store.write(gameId, key, payload)
                    success(requestId)
                }

                "read" -> {
                    val payload = store.read(gameId, request.string("key"))
                    success(
                        requestId,
                        payload?.let { JsonPrimitive(Base64.getEncoder().encodeToString(it)) })
                }

                "delete" -> {
                    store.delete(gameId, request.string("key"))
                    success(requestId)
                }

                "exists" -> {
                    val exists = request.string("key") in store.keys(gameId)
                    success(requestId, JsonPrimitive(exists))
                }

                "list" -> {
                    val keys =
                        buildJsonArray { store.keys(gameId).forEach { add(JsonPrimitive(it)) } }
                    success(requestId, keys)
                }

                else -> reportedFailure(requestId, "invalid")
            }
        } catch (error: GameSaveLimitException) {
            reportedFailure(requestId, "limit", error)
        } catch (error: GameSaveCorruptionException) {
            reportedFailure(requestId, "corrupt", error)
        } catch (error: GameSaveStorageException) {
            reportedFailure(requestId, "storage", error)
        } catch (error: Exception) {
            reportedFailure(requestId, "invalid", error)
        }
    }

    fun busy(message: String): String {
        if (message.length > MAX_MESSAGE_CHARS) return failure(INVALID_ID, "busy")
        val id = runCatching {
            Json.parseToJsonElement(message).jsonObject.string("id").takeIf(ID::matches)
        }.getOrNull() ?: INVALID_ID
        return failure(id, "busy")
    }

    private fun decodePayload(value: String): ByteArray {
        require(value.length <= MAX_BASE64_CHARS && value.length % 4 == 0) { "Invalid payload size" }
        require(BASE64.matches(value)) { "Invalid Base64 payload" }
        return Base64.getDecoder().decode(value).also {
            require(it.size <= MAX_PAYLOAD_BYTES) { "Payload is too large" }
        }
    }

    private fun success(id: String, data: kotlinx.serialization.json.JsonElement? = null): String =
        response(id, ok = true, data = data)

    private fun failure(id: String, code: String): String =
        response(id, ok = false, error = code)

    private fun reportedFailure(id: String, code: String, error: Exception? = null): String {
        runCatching { onFailure(code, error?.javaClass?.name) }
        return failure(id, code)
    }

    private fun response(
        id: String,
        ok: Boolean,
        data: kotlinx.serialization.json.JsonElement? = null,
        error: String? = null,
    ): String = buildJsonObject {
        put("v", JsonPrimitive(VERSION))
        put("id", JsonPrimitive(id))
        put("ok", JsonPrimitive(ok))
        data?.let { put("data", it) }
        error?.let { put("error", JsonPrimitive(it)) }
    }.toString()

    private fun JsonObject.string(name: String): String =
        get(name)?.jsonPrimitive?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("Missing field")

    private fun JsonObject.int(name: String): Int =
        get(name)?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("Missing field")

    companion object {
        const val VERSION = 1
        const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024
        private const val MAX_BASE64_CHARS = ((MAX_PAYLOAD_BYTES + 2) / 3) * 4
        private const val MAX_MESSAGE_CHARS = MAX_BASE64_CHARS + 1024
        private const val INVALID_ID = "invalid"
        private val ID = Regex("[A-Za-z0-9-]{1,64}")
        private val BASE64 = Regex("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?")
    }
}