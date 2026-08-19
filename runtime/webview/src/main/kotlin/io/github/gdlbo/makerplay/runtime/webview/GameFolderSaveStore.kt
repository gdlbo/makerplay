package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.runtime.api.GameSaveStorageException
import io.github.gdlbo.makerplay.runtime.api.GameSaveStore
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Uses the game's own save directory as the authoritative save store. */
internal class GameFolderSaveStore(
    root: File,
    private val extension: String,
) : GameSaveStore {
    private val saveDirectory = File(root, "save").absoluteFile
    private val lock = Any()

    init {
        require(extension == ".rpgsave" || extension == ".rmmzsave")
        require(!Files.isSymbolicLink(saveDirectory.toPath())) { "Save directory must not be a symbolic link" }
        require(saveDirectory.mkdirs() || saveDirectory.isDirectory) { "Unable to create game save directory" }
        require(saveDirectory.canonicalFile.parentFile == root.canonicalFile) {
            "Save directory escapes the game root"
        }
    }

    override fun read(gameId: String, key: String): ByteArray? = synchronized(lock) {
        val file = fileFor(key)
        if (!file.exists()) return null
        try {
            requireRegular(file)
            decode(file.readBytes())
        } catch (error: Exception) {
            throw GameSaveStorageException(gameId, key, "read save data")
        }
    }

    override fun write(gameId: String, key: String, payload: ByteArray) {
        synchronized(lock) {
            require(payload.size <= SaveBridgeProtocol.MAX_PAYLOAD_BYTES) { "Save payload is too large" }
            val file = fileFor(key)
            val temporary = File(saveDirectory, ".${file.name}.makerplay.tmp")
            try {
                requireNoSymlink(temporary)
                FileOutputStream(temporary).use { output ->
                    output.write(encode(payload))
                    output.fd.sync()
                }
                try {
                    Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            } catch (error: Exception) {
                Files.deleteIfExists(temporary.toPath())
                throw GameSaveStorageException(gameId, key, "write save data")
            }
        }
    }

    override fun delete(gameId: String, key: String): Boolean = synchronized(lock) {
        val file = fileFor(key)
        try {
            requireNoSymlink(file)
            Files.deleteIfExists(file.toPath())
        } catch (error: Exception) {
            throw GameSaveStorageException(gameId, key, "delete save data")
        }
    }

    override fun keys(gameId: String): Set<String> = synchronized(lock) {
        saveDirectory.listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .filter { it.name.endsWith(extension, ignoreCase = true) }
            .map { it.name.dropLast(extension.length) }
            .filter(KEY::matches)
            .toSortedSet()
    }

    private fun fileFor(key: String): File {
        require(KEY.matches(key)) { "Invalid save key" }
        val file = File(saveDirectory, key + extension).absoluteFile
        require(file.parentFile == saveDirectory) { "Save path escapes the save directory" }
        requireNoSymlink(file)
        return file
    }

    private fun encode(payload: ByteArray): ByteArray {
        if (extension == ".rpgsave") return payload
        return String(CharArray(payload.size) { index -> (payload[index].toInt() and 0xff).toChar() })
            .toByteArray(StandardCharsets.UTF_8)
    }

    private fun decode(payload: ByteArray): ByteArray {
        if (extension == ".rpgsave") return payload
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload))
        return ByteArray(text.length) { index ->
            val value = text[index].code
            require(value <= 0xff) { "Invalid MZ save data" }
            value.toByte()
        }
    }

    private fun requireRegular(file: File) {
        requireNoSymlink(file)
        require(file.isFile) { "Save file is not regular" }
        val maxDiskBytes = SaveBridgeProtocol.MAX_PAYLOAD_BYTES.toLong() *
                if (extension == ".rmmzsave") 2 else 1
        require(file.length() in 0..maxDiskBytes) { "Save file is too large" }
    }

    private fun requireNoSymlink(file: File) {
        require(!Files.isSymbolicLink(file.toPath())) { "Symbolic links are not allowed" }
    }

    private companion object {
        val KEY = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}