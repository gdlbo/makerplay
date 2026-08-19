package io.github.gdlbo.makerplay.runtime.webview.internal.save

import io.github.gdlbo.makerplay.runtime.api.GameSaveStore
import io.github.gdlbo.makerplay.runtime.api.InitialGameSaveStore
import io.github.gdlbo.makerplay.runtime.webview.SaveBridgeProtocol
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files

internal object InitialGameSaveImporter {
    fun import(root: File, gameId: String, store: GameSaveStore, extension: String) {
        val destination = store as? InitialGameSaveStore ?: return
        val saveDirectory = File(root, "save")
        val maxFileBytes = if (extension.equals(MZ_EXTENSION, ignoreCase = true)) {
            SaveBridgeProtocol.MAX_PAYLOAD_BYTES.toLong() * 2
        } else {
            SaveBridgeProtocol.MAX_PAYLOAD_BYTES.toLong()
        }
        val entries = if (
            saveDirectory.isDirectory &&
            !Files.isSymbolicLink(saveDirectory.toPath()) &&
            saveDirectory.canonicalFile.parentFile == root.canonicalFile
        ) {
            saveDirectory.listFiles().orEmpty()
                .asSequence()
                .filter { file ->
                    file.isFile &&
                            !Files.isSymbolicLink(file.toPath()) &&
                            file.name.endsWith(extension, ignoreCase = true) &&
                            file.length() in 0..maxFileBytes
                }
                .map { file ->
                    file.name.dropLast(extension.length) to payload(file, extension)
                }
                .filter { (key) -> SAVE_KEY.matches(key) }
                .toMap()
        } else {
            emptyMap()
        }
        destination.importInitial(gameId, entries)
    }

    private fun payload(file: File, extension: String): ByteArray {
        val bytes = file.readBytes()
        if (!extension.equals(MZ_EXTENSION, ignoreCase = true)) return bytes
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        require(text.length <= SaveBridgeProtocol.MAX_PAYLOAD_BYTES) { "MZ save is too large" }
        return ByteArray(text.length) { index ->
            val code = text[index].code
            require(code <= 0xff) { "MZ save contains invalid binary text" }
            code.toByte()
        }
    }

    private const val MZ_EXTENSION = ".rmmzsave"
    private val SAVE_KEY = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
}