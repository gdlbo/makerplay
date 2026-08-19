package io.github.gdlbo.makerplay.runtime.api

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class FileGameSaveStore(
    root: File,
    private val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
    private val maxEntriesPerGame: Int = DEFAULT_MAX_ENTRIES_PER_GAME,
) : InitialGameSaveStore {
    private val root = root.absoluteFile
    private val storeLock: Any

    init {
        require(maxPayloadBytes > 0) { "maxPayloadBytes must be positive" }
        require(maxEntriesPerGame > 0) { "maxEntriesPerGame must be positive" }
        ensureDirectory(this.root)
        storeLock = ROOT_LOCKS.computeIfAbsent(this.root.canonicalPath) { Any() }
    }

    override fun read(gameId: String, key: String): ByteArray? = operation(gameId, key, "read") {
        val files = files(gameId, key, createGameDirectory = false) ?: return null
        val candidates = listOf(files.primary, files.backup1, files.backup2)
        var found = false
        for ((index, candidate) in candidates.withIndex()) {
            if (!candidate.exists()) continue
            found = true
            val payload = decode(candidate) ?: continue
            if (index != 0) runCatching { repairPrimary(files, payload) }
            return payload
        }
        if (found) throw GameSaveCorruptionException(gameId, key)
        return null
    }

    override fun write(gameId: String, key: String, payload: ByteArray) =
        operation(gameId, key, "write") {
            validatePayload(payload)
            val files = requireNotNull(files(gameId, key, createGameDirectory = true))
            if (!files.exists() && keyCount(files.directory) >= maxEntriesPerGame) {
                throw GameSaveLimitException("A game may contain at most $maxEntriesPerGame save entries")
            }

            rejectSymbolicLink(files.temporary)
            FileOutputStream(files.temporary).use { output ->
                output.write(encode(payload))
                output.fd.sync()
            }
            try {
                rotate(files)
                moveReplacing(files.temporary, files.primary)
                syncDirectory(files.directory)
            } finally {
                Files.deleteIfExists(files.temporary.toPath())
            }
        }

    override fun delete(gameId: String, key: String): Boolean = operation(gameId, key, "delete") {
        val files = files(gameId, key, createGameDirectory = false) ?: return false
        var deleted = false
        listOf(files.primary, files.backup1, files.backup2, files.temporary).forEach { file ->
            rejectSymbolicLink(file)
            deleted = Files.deleteIfExists(file.toPath()) || deleted
        }
        if (deleted) syncDirectory(files.directory)
        return deleted
    }

    override fun keys(gameId: String): Set<String> = operation(gameId, "entries", "list") {
        val directory = gameDirectory(gameId, create = false) ?: return emptySet()
        return directory.listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .mapNotNull { saveKey(it.name) }
            .filter(::isValidIdentifier)
            .toSortedSet()
    }

    fun deleteGame(gameId: String): Boolean = operation(gameId, "all", "delete game saves") {
        val directory = gameDirectory(gameId, create = false) ?: return false
        check(directory.deleteRecursively() && !directory.exists()) { "Unable to delete game saves" }
        true
    }

    override fun importInitial(gameId: String, entries: Map<String, ByteArray>) =
        operation(gameId, "initial", "import initial") {
            entries.forEach { (key, payload) ->
                validateIdentifier(key, "key")
                validatePayload(payload)
            }
            val directory = gameDirectory(gameId, create = true)!!
            val marker = child(directory, INITIAL_IMPORT_MARKER)
            if (marker.exists()) {
                require(marker.isFile) { "Initial save marker is not a file" }
                return@operation
            }

            val existingKeys = keys(gameId)
            val missing = entries.filterKeys { it !in existingKeys }
            if (existingKeys.size + missing.size > maxEntriesPerGame) {
                throw GameSaveLimitException("A game may contain at most $maxEntriesPerGame save entries")
            }
            missing.forEach { (key, payload) -> write(gameId, key, payload) }

            val temporary = child(directory, INITIAL_IMPORT_TEMP)
            FileOutputStream(temporary).use { output ->
                output.write(FORMAT_VERSION)
                output.fd.sync()
            }
            try {
                moveReplacing(temporary, marker)
                syncDirectory(directory)
            } finally {
                Files.deleteIfExists(temporary.toPath())
            }
        }

    private fun files(gameId: String, key: String, createGameDirectory: Boolean): SaveFiles? {
        validateIdentifier(gameId, "gameId")
        validateIdentifier(key, "key")
        val directory = gameDirectory(gameId, createGameDirectory) ?: return null
        return SaveFiles(
            directory = directory,
            primary = child(directory, "$key$PRIMARY_SUFFIX"),
            backup1 = child(directory, "$key$BACKUP_1_SUFFIX"),
            backup2 = child(directory, "$key$BACKUP_2_SUFFIX"),
            temporary = child(directory, "$key$TEMP_SUFFIX"),
        )
    }

    private fun gameDirectory(gameId: String, create: Boolean): File? {
        validateIdentifier(gameId, "gameId")
        val directory = child(root, gameId)
        if (!directory.exists()) {
            if (!create) return null
            ensureDirectory(directory)
        } else {
            rejectSymbolicLink(directory)
            require(directory.isDirectory) { "Save game path is not a directory" }
        }
        require(directory.canonicalFile.parentFile == root.canonicalFile) { "Save game path escapes the store" }
        return directory
    }

    private fun child(parent: File, name: String): File {
        val child = File(parent, name)
        require(child.absoluteFile.parentFile == parent.absoluteFile) { "Save path escapes its parent" }
        rejectSymbolicLink(child)
        return child
    }

    private fun rotate(files: SaveFiles) {
        Files.deleteIfExists(files.backup2.toPath())
        if (files.backup1.exists()) moveReplacing(files.backup1, files.backup2)
        if (files.primary.exists()) moveReplacing(files.primary, files.backup1)
    }

    private fun repairPrimary(files: SaveFiles, payload: ByteArray) {
        FileOutputStream(files.temporary).use { output ->
            output.write(encode(payload))
            output.fd.sync()
        }
        try {
            moveReplacing(files.temporary, files.primary)
            syncDirectory(files.directory)
        } finally {
            Files.deleteIfExists(files.temporary.toPath())
        }
    }

    private fun encode(payload: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream(HEADER_BYTES + payload.size)
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeByte(FORMAT_VERSION)
            output.writeInt(payload.size)
            output.write(checksum(payload))
            output.write(payload)
        }
        return bytes.toByteArray()
    }

    private fun decode(file: File): ByteArray? = try {
        rejectSymbolicLink(file)
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        if (file.length() < HEADER_BYTES || file.length() > HEADER_BYTES.toLong() + maxPayloadBytes) return null
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            if (input.readInt() != MAGIC || input.readUnsignedByte() != FORMAT_VERSION) return null
            val size = input.readInt()
            if (size < 0 || size > maxPayloadBytes || file.length() != HEADER_BYTES.toLong() + size) return null
            val expectedChecksum = ByteArray(CHECKSUM_BYTES)
            input.readFully(expectedChecksum)
            val payload = ByteArray(size)
            input.readFully(payload)
            if (!MessageDigest.isEqual(expectedChecksum, checksum(payload))) return null
            payload
        }
    } catch (_: EOFException) {
        null
    }

    private fun validatePayload(payload: ByteArray) {
        if (payload.size > maxPayloadBytes) {
            throw GameSaveLimitException("Save payload may contain at most $maxPayloadBytes bytes")
        }
    }

    private fun keyCount(directory: File): Int = directory.listFiles().orEmpty()
        .asSequence()
        .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
        .mapNotNull { saveKey(it.name) }
        .filter(::isValidIdentifier)
        .distinct()
        .count()

    private fun saveKey(fileName: String): String? = when {
        fileName.endsWith(BACKUP_1_SUFFIX) -> fileName.removeSuffix(BACKUP_1_SUFFIX)
        fileName.endsWith(BACKUP_2_SUFFIX) -> fileName.removeSuffix(BACKUP_2_SUFFIX)
        fileName.endsWith(PRIMARY_SUFFIX) -> fileName.removeSuffix(PRIMARY_SUFFIX)
        else -> null
    }

    private fun moveReplacing(source: File, target: File) {
        rejectSymbolicLink(source)
        rejectSymbolicLink(target)
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun ensureDirectory(directory: File) {
        rejectSymbolicLink(directory)
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create save directory" }
    }

    private fun rejectSymbolicLink(file: File) {
        require(!Files.isSymbolicLink(file.toPath())) { "Symbolic links are not allowed in the save store" }
    }

    private fun syncDirectory(directory: File) {
        try {
            FileInputStream(directory).channel.use { it.force(true) }
        } catch (_: Exception) {
            // Directory fsync is not available on every Android/JVM filesystem.
        }
    }

    private inline fun <T> operation(
        gameId: String,
        key: String,
        name: String,
        block: () -> T,
    ): T = synchronized(storeLock) {
        try {
            block()
        } catch (error: GameSaveLimitException) {
            throw error
        } catch (error: GameSaveCorruptionException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (_: Exception) {
            throw GameSaveStorageException(gameId, key, name)
        }
    }

    private data class SaveFiles(
        val directory: File,
        val primary: File,
        val backup1: File,
        val backup2: File,
        val temporary: File,
    ) {
        fun exists(): Boolean = primary.exists() || backup1.exists() || backup2.exists()
    }

    companion object {
        const val DEFAULT_MAX_PAYLOAD_BYTES = 4 * 1024 * 1024
        const val DEFAULT_MAX_ENTRIES_PER_GAME = 32

        private const val MAGIC = 0x47535631
        private const val FORMAT_VERSION = 1
        private const val CHECKSUM_BYTES = 32
        private const val HEADER_BYTES = Int.SIZE_BYTES + 1 + Int.SIZE_BYTES + CHECKSUM_BYTES
        private const val PRIMARY_SUFFIX = ".sav"
        private const val BACKUP_1_SUFFIX = ".sav.bak1"
        private const val BACKUP_2_SUFFIX = ".sav.bak2"
        private const val TEMP_SUFFIX = ".sav.tmp"
        private const val INITIAL_IMPORT_MARKER = ".initial-saves-imported"
        private const val INITIAL_IMPORT_TEMP = ".initial-saves-imported.tmp"
        private val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val ROOT_LOCKS = ConcurrentHashMap<String, Any>()

        private fun checksum(payload: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(payload)

        private fun validateIdentifier(value: String, name: String) {
            require(isValidIdentifier(value) && value != "." && value != "..") {
                "$name must be 1-128 ASCII letters, digits, dots, underscores, or hyphens"
            }
        }

        private fun isValidIdentifier(value: String): Boolean = IDENTIFIER.matches(value)
    }
}