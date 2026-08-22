package io.github.gdlbo.makerplay.vfs

import io.github.gdlbo.makerplay.codec.AssetCodecRegistry
import io.github.gdlbo.makerplay.codec.RpgMakerAssetCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

class GameMountException(message: String) : IllegalStateException(message)

object RpgMakerGameMount {
    fun open(root: File, indexRoot: File = root): GameFileSystem {
        val index = try {
            if (root.canonicalFile == indexRoot.canonicalFile) {
                GameFileIndex.loadOrBuild(root, indexRoot)
            } else {
                GameFileIndex.build(root).also { it.write(indexRoot) }
            }
        } catch (_: Exception) {
            throw GameMountException("The imported game index is unavailable.")
        }
        val metadata = readSystemMetadata(GameFileSystem(index), index)
        val encrypted = metadata.hasEncryptedImages || metadata.hasEncryptedAudio
        val codecs = if (encrypted) {
            val codec = try {
                RpgMakerAssetCodec.fromHexKey(metadata.encryptionKey.orEmpty())
            } catch (_: IllegalArgumentException) {
                throw GameMountException("The imported game has invalid encryption metadata.")
            }
            AssetCodecRegistry.of(codec)
        } else {
            AssetCodecRegistry.EMPTY
        }
        return GameFileSystem(index, codecs = codecs)
    }

    private fun readSystemMetadata(
        vfs: GameFileSystem,
        index: GameFileIndex,
    ): EncryptionMetadata {
        val result = vfs.open(SYSTEM_JSON_PATH)
        if (result !is VfsOpenResult.Found || result.contentLength > MAX_SYSTEM_JSON_BYTES) {
            if (result is VfsOpenResult.Found) result.stream.close()
            throw GameMountException("The imported game has invalid system metadata.")
        }
        val bytes = result.stream.use { it.readUpTo(MAX_SYSTEM_JSON_BYTES + 1) }
        if (bytes.size > MAX_SYSTEM_JSON_BYTES) {
            throw GameMountException("The imported game has invalid system metadata.")
        }
        parseSystemMetadata(bytes)?.let { return it }
        if (!RpgMakerProtectedData.isCryptoJsOpenSslBase64(bytes)) {
            throw GameMountException("The imported game has invalid system metadata.")
        }
        return try {
            recoverProtectedMetadata(index)
        } catch (_: Exception) {
            throw GameMountException("The imported game has invalid system metadata.")
        }
    }

    private fun parseSystemMetadata(bytes: ByteArray): EncryptionMetadata? = try {
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
            .removePrefix(UTF8_BOM)
        val json = Json.parseToJsonElement(text).jsonObject
        EncryptionMetadata(
            hasEncryptedImages = json["hasEncryptedImages"]?.jsonPrimitive?.booleanOrNull == true,
            hasEncryptedAudio = json["hasEncryptedAudio"]?.jsonPrimitive?.booleanOrNull == true,
            encryptionKey = json["encryptionKey"]?.jsonPrimitive?.contentOrNull,
        )
    } catch (_: Exception) {
        null
    }

    private fun recoverProtectedMetadata(index: GameFileIndex): EncryptionMetadata {
        val encryptedImages = index.entries.any { it.path.hasSuffix(ENCRYPTED_IMAGE_SUFFIXES) }
        val encryptedAudio = index.entries.any { it.path.hasSuffix(ENCRYPTED_AUDIO_SUFFIXES) }
        if (!encryptedImages && !encryptedAudio) {
            return EncryptionMetadata(false, false, null)
        }
        val png = index.entries.firstOrNull { it.path.hasSuffix(ENCRYPTED_PNG_SUFFIXES) }
            ?: throw IllegalArgumentException("Protected game has no recoverable image key")
        val file =
            index.file(png) ?: throw IllegalArgumentException("Encrypted image is unavailable")
        val header = file.inputStream().use { it.readUpTo(ENCRYPTED_PNG_HEADER_BYTES) }
        val key = RpgMakerAssetCodec.recoverHexKeyFromEncryptedPngHeader(header)
        return EncryptionMetadata(encryptedImages, encryptedAudio, key)
    }

    private fun GamePath.hasSuffix(suffixes: Set<String>): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        return suffixes.any(lower::endsWith)
    }

    private data class EncryptionMetadata(
        val hasEncryptedImages: Boolean,
        val hasEncryptedAudio: Boolean,
        val encryptionKey: String?,
    )

    private const val SYSTEM_JSON_PATH = "data/System.json"
    private const val UTF8_BOM = "\uFEFF"
    private const val MAX_SYSTEM_JSON_BYTES = 2 * 1024 * 1024
    private const val ENCRYPTED_PNG_HEADER_BYTES = 32
    private val ENCRYPTED_PNG_SUFFIXES = setOf(".png_", ".rpgmvp")
    private val ENCRYPTED_IMAGE_SUFFIXES = ENCRYPTED_PNG_SUFFIXES
    private val ENCRYPTED_AUDIO_SUFFIXES = setOf(".ogg_", ".m4a_", ".rpgmvo", ".rpgmvm")
}

private fun java.io.InputStream.readUpTo(limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var total = 0
    while (total < buffer.size) {
        val read = read(buffer, total, buffer.size - total)
        if (read < 0) break
        total += read
    }
    return buffer.copyOf(total)
}