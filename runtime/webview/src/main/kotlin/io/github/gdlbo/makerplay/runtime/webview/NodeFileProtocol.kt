package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.runtime.api.GameSaveStore
import io.github.gdlbo.makerplay.vfs.GameFileSystem
import io.github.gdlbo.makerplay.vfs.GamePath
import io.github.gdlbo.makerplay.vfs.VfsOpenResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64
import java.util.Locale

sealed interface OverlayAsset {
    data object Missing : OverlayAsset
    data object Deleted : OverlayAsset
    data class Present(val bytes: ByteArray, val lastModifiedMillis: Long) : OverlayAsset {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Present

            if (lastModifiedMillis != other.lastModifiedMillis) return false
            if (!bytes.contentEquals(other.bytes)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = lastModifiedMillis.hashCode()
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }
}

internal class NodeFileProtocol(
    private val gameFileSystem: GameFileSystem,
    dataRoot: File,
    private val gameId: String? = null,
    private val saveStore: GameSaveStore? = null,
) {
    internal fun overlayAsset(rawPath: String): OverlayAsset =
        when (val path = virtualPath(rawPath)) {
            is VirtualPath.Game -> when {
                isGameDeleted(path) -> OverlayAsset.Deleted
                else -> gameOverlayFile(path, allowRoot = false).let { file ->
                    if (file.isFile && !Files.isSymbolicLink(file.toPath())) {
                        OverlayAsset.Present(file.readBytes(), file.lastModified())
                    } else {
                        OverlayAsset.Missing
                    }
                }
            }

            else -> OverlayAsset.Missing
        }

    private val requestedDataRoot = dataRoot.absoluteFile
    private val dataRoot: File
    private val gameOverlayPath: File
    private val gameDeletedPath: File
    private val gameOverlayRoot: File
    private val gameDeletedRoot: File
    private val lock = Any()

    init {
        require((gameId == null) == (saveStore == null)) {
            "Game ID and save store must be provided together"
        }
        require(requestedDataRoot.mkdirs() || requestedDataRoot.isDirectory) {
            "Unable to create the Node compatibility data directory"
        }
        require(!Files.isSymbolicLink(requestedDataRoot.toPath())) {
            "Node compatibility data directory must not be a symbolic link"
        }
        this.dataRoot = requestedDataRoot.canonicalFile
        gameOverlayPath = File(this.dataRoot, GAME_OVERLAY_DIRECTORY).absoluteFile
        gameDeletedPath = File(this.dataRoot, GAME_DELETED_DIRECTORY).absoluteFile
        gameOverlayRoot = gameOverlayPath.canonicalFile
        gameDeletedRoot = gameDeletedPath.canonicalFile
        val logs = File(this.dataRoot, "logs")
        require(!Files.isSymbolicLink(logs.toPath())) { "Log directory must not be a symbolic link" }
        require(logs.mkdirs() || logs.isDirectory) { "Unable to create the log directory" }
        listOf("tmp", "cache").forEach { name ->
            val directory = File(this.dataRoot, name)
            require(!Files.isSymbolicLink(directory.toPath())) {
                "$name directory must not be a symbolic link"
            }
            require(directory.mkdirs() || directory.isDirectory) { "Unable to create $name directory" }
        }
        require(!Files.isSymbolicLink(gameOverlayPath.toPath())) {
            "Writable game overlay must not be a symbolic link"
        }
        require(gameOverlayPath.mkdirs() || gameOverlayPath.isDirectory) {
            "Unable to create the writable game overlay"
        }
        require(gameOverlayPath.canonicalFile == gameOverlayRoot) {
            "Writable game overlay changed during setup"
        }
        require(gameOverlayRoot.toPath().startsWith(this.dataRoot.toPath())) {
            "Writable game overlay escapes the data root"
        }
        require(!Files.isSymbolicLink(gameDeletedPath.toPath())) {
            "Game deletion index must not be a symbolic link"
        }
        require(gameDeletedPath.mkdirs() || gameDeletedPath.isDirectory) {
            "Unable to create the game deletion index"
        }
        require(gameDeletedPath.canonicalFile == gameDeletedRoot) {
            "Game deletion index changed during setup"
        }
        require(gameDeletedRoot.toPath().startsWith(this.dataRoot.toPath())) {
            "Game deletion index escapes the data root"
        }
        cleanupManagedGarbage()
    }

    fun handle(message: String): String = synchronized(lock) {
        var requestId = INVALID_ID
        try {
            require(message.length <= MAX_MESSAGE_CHARS) { "Message is too large" }
            val request = Json.parseToJsonElement(message).jsonObject
            requestId = request.string("id")
            require(ID.matches(requestId)) { "Invalid request ID" }
            require(request.int("v") == VERSION) { "Unsupported protocol version" }
            when (request.string("op")) {
                "exists" -> success(requestId, JsonPrimitive(exists(request.string("path"))))
                "read" -> success(
                    requestId,
                    JsonPrimitive(Base64.getEncoder().encodeToString(read(request.string("path")))),
                )

                "write" -> {
                    write(request.string("path"), request.payload(), append = false)
                    success(requestId)
                }

                "append" -> {
                    write(request.string("path"), request.payload(), append = true)
                    success(requestId)
                }

                "unlink" -> {
                    unlink(request.string("path"))
                    success(requestId)
                }

                "mkdir" -> {
                    val directory = writableFile(request.string("path"), allowRoot = true)
                    require(directory.mkdirs() || directory.isDirectory) { "Unable to create directory" }
                    success(requestId)
                }

                "rename" -> {
                    rename(request.string("path"), request.string("target"))
                    success(requestId)
                }

                "rmdir" -> {
                    val path = request.string("path")
                    val value = stat(path)
                    require(value.getValue("directory").jsonPrimitive.booleanOrNull == true) { "Not a directory" }
                    remove(path, recursive = false, force = false)
                    success(requestId)
                }

                "readdir" -> {
                    val entries = list(request.string("path"))
                    success(
                        requestId,
                        buildJsonArray {
                            entries.forEach { add(JsonPrimitive(it)) }
                        },
                    )
                }

                "readdirStat" -> {
                    val path = request.string("path")
                    val entries = list(path)
                    success(
                        requestId,
                        buildJsonArray {
                            entries.forEach { name ->
                                val value = stat(joinVirtualPath(path, name))
                                add(buildJsonObject {
                                    put("name", JsonPrimitive(name))
                                    put("file", value.getValue("file"))
                                    put("directory", value.getValue("directory"))
                                })
                            }
                        },
                    )
                }

                "copy" -> {
                    write(request.string("target"), read(request.string("path")), append = false)
                    success(requestId)
                }

                "truncate" -> {
                    truncate(request.string("path"), request.int("size"))
                    success(requestId)
                }

                "readRange" -> {
                    val payload = readRange(
                        request.string("path"),
                        position = request.int("position"),
                        size = request.int("size"),
                    )
                    success(requestId, JsonPrimitive(Base64.getEncoder().encodeToString(payload)))
                }

                "writeRange" -> {
                    val written = writeRange(
                        request.string("path"),
                        position = request.int("position"),
                        payload = request.payload(),
                        append = request.boolean("append", false),
                    )
                    success(requestId, JsonPrimitive(written))
                }

                "rm" -> {
                    remove(
                        request.string("path"),
                        recursive = request.boolean("recursive", false),
                        force = request.boolean("force", false),
                    )
                    success(requestId)
                }

                "stat" -> success(requestId, stat(request.string("path")))
                else -> failure(requestId, "unsupported")
            }
        } catch (error: ProtocolFailure) {
            failure(requestId, error.reason)
        } catch (_: Exception) {
            failure(requestId, "invalid")
        }
    }

    fun busy(message: String): String {
        if (message.length > MAX_MESSAGE_CHARS) return failure(INVALID_ID, "busy")
        val id = runCatching {
            Json.parseToJsonElement(message).jsonObject.string("id").takeIf(ID::matches)
        }.getOrNull() ?: INVALID_ID
        return failure(id, "busy")
    }

    private fun exists(rawPath: String): Boolean = when (val path = virtualPath(rawPath)) {
        VirtualPath.GameRoot -> true
        is VirtualPath.Game -> {
            val overlayExists = gameOverlayFile(path, allowRoot = false).exists()
            val nativeExists = nativeSaveKey(path)?.let(::nativeSaveExists) == true
            overlayExists || nativeExists || !isGameDeleted(path) &&
                    (gameFileSystem.resolve(path.path) != null || gameFileSystem.list(path.path) != null)
        }

        VirtualPath.DataRoot -> dataRoot.isDirectory
        is VirtualPath.Data -> runCatching {
            writableFile(
                path,
                allowRoot = false
            ).exists()
        }.getOrDefault(false)
    }

    private fun list(rawPath: String): List<String> = when (val path = virtualPath(rawPath)) {
        VirtualPath.GameRoot -> mergedEntries(
            gameFileSystem.list(""),
            gameOverlayRoot,
            deletedEntries(VirtualPath.Game("")),
        )

        is VirtualPath.Game -> {
            val overlay = gameOverlayFile(path, allowRoot = false)
            if (overlay.exists()) {
                require(overlay.isDirectory && !Files.isSymbolicLink(overlay.toPath())) {
                    "Not a directory"
                }
            }
            val entries = mergedEntries(
                gameFileSystem.list(path.path).orEmpty() + nativeSaveEntries(path),
                overlay.takeIf(File::isDirectory),
                deletedEntries(path),
            )
            if (entries.isNotEmpty() || exists(rawPath)) entries else throw IllegalArgumentException(
                "Not a directory"
            )
        }

        VirtualPath.DataRoot -> dataRoot.entries().filterNot(::isReservedDataEntry)
        is VirtualPath.Data -> writableFile(path, allowRoot = true).entries()
    }

    private fun File.entries(): List<String> {
        require(isDirectory && !Files.isSymbolicLink(toPath())) { "Not a directory" }
        return listFiles().orEmpty().map(File::getName).sorted()
    }

    private fun mergedEntries(
        gameEntries: List<String>?,
        overlay: File?,
        deleted: Set<String> = emptySet(),
    ): List<String> = (gameEntries.orEmpty() + overlay?.entries().orEmpty())
        .distinct()
        .filterNot(deleted::contains)
        .sorted()

    private fun read(rawPath: String): ByteArray = when (val path = virtualPath(rawPath)) {
        VirtualPath.GameRoot, VirtualPath.DataRoot -> throw IllegalArgumentException("Cannot read a directory")
        is VirtualPath.Game -> {
            nativeSaveKey(path)?.let { key ->
                nativeSaveRead(key)?.let { return it }
            }
            val overlay = gameOverlayFile(path, allowRoot = false)
            if (overlay.exists()) {
                require(overlay.isFile && !Files.isSymbolicLink(overlay.toPath())) { "File not found" }
                require(overlay.length() <= MAX_PAYLOAD_BYTES) { "File is too large" }
                overlay.readBytes()
            } else {
                if (isGameDeleted(path)) missing()
                when (val opened = gameFileSystem.open(path.path)) {
                    is VfsOpenResult.Found -> opened.stream.use { stream ->
                        require(opened.contentLength <= MAX_PAYLOAD_BYTES) { "File is too large" }
                        stream.readBytes()
                    }

                    else -> missing()
                }
            }
        }

        is VirtualPath.Data -> {
            val file = writableFile(path, allowRoot = false)
            if (!file.exists()) missing()
            require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "File not found" }
            require(file.length() <= MAX_PAYLOAD_BYTES) { "File is too large" }
            file.readBytes()
        }
    }

    private fun missing(): Nothing = throw ProtocolFailure("missing")

    private fun write(rawPath: String, payload: ByteArray, append: Boolean) {
        require(payload.size <= MAX_PAYLOAD_BYTES) { "File is too large" }
        val virtualPath = virtualPath(rawPath)
        if (virtualPath is VirtualPath.Game) {
            nativeSaveKey(virtualPath)?.let { key ->
                val output =
                    if (append) (nativeSaveRead(key) ?: byteArrayOf()) + payload else payload
                require(output.size <= MAX_PAYLOAD_BYTES) { "File is too large" }
                nativeSaveStore().write(nativeGameId(), key, output)
                val overlay = gameOverlayFile(virtualPath, allowRoot = false)
                if (overlay.exists()) {
                    require(overlay.isFile && !Files.isSymbolicLink(overlay.toPath())) { "Not a file" }
                    check(overlay.delete()) { "Unable to remove legacy save overlay" }
                }
                clearGameDeleted(virtualPath)
                return
            }
        }
        val file = writableFile(rawPath, allowRoot = false)
        file.parentFile?.let { parent ->
            require(parent.mkdirs() || parent.isDirectory) { "Unable to create parent directory" }
            require(!Files.isSymbolicLink(parent.toPath())) { "Symbolic links are not allowed" }
        }
        require(!file.exists() || file.isFile && !Files.isSymbolicLink(file.toPath())) { "Not a file" }
        if (append && !file.exists() && virtualPath is VirtualPath.Game && !isGameDeleted(
                virtualPath
            )
        ) {
            when (val opened = gameFileSystem.open(virtualPath.path)) {
                is VfsOpenResult.Found -> opened.stream.use { stream ->
                    require(opened.contentLength <= MAX_PAYLOAD_BYTES) { "File is too large" }
                    FileOutputStream(file).use { output -> stream.copyTo(output) }
                }

                else -> Unit
            }
        }
        if (virtualPath is VirtualPath.Game) clearGameDeleted(virtualPath)
        val finalSize = if (append) file.length() + payload.size else payload.size.toLong()
        require(finalSize <= MAX_PAYLOAD_BYTES) { "File is too large" }
        if (append) {
            FileOutputStream(file, true).use { it.write(payload) }
        } else {
            val temporary = File.createTempFile(".makerplay-", ".tmp", file.parentFile)
            try {
                FileOutputStream(temporary).use { it.write(payload) }
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
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } finally {
                Files.deleteIfExists(temporary.toPath())
            }
        }
    }

    private fun truncate(rawPath: String, size: Int) {
        require(size in 0..MAX_PAYLOAD_BYTES) { "Invalid file size" }
        val path = virtualPath(rawPath)
        if (path is VirtualPath.Game && nativeSaveKey(path) != null) {
            val payload = read(rawPath)
            write(rawPath, payload.copyOf(size), append = false)
            return
        }
        val file = writableFile(rawPath, allowRoot = false)
        if (!file.exists() && path is VirtualPath.Game && !isGameDeleted(path)) {
            val payload = read(rawPath)
            write(rawPath, payload, append = false)
        }
        require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "File not found" }
        RandomAccessFile(file, "rw").use { it.setLength(size.toLong()) }
    }

    private fun readRange(rawPath: String, position: Int, size: Int): ByteArray {
        require(position in 0..MAX_PAYLOAD_BYTES && size in 0..MAX_PAYLOAD_BYTES) {
            "Invalid read range"
        }
        val payload = read(rawPath)
        if (position >= payload.size || size == 0) return byteArrayOf()
        return payload.copyOfRange(position, minOf(payload.size, position + size))
    }

    private fun writeRange(
        rawPath: String,
        position: Int,
        payload: ByteArray,
        append: Boolean
    ): Int {
        require(position in 0..MAX_PAYLOAD_BYTES) { "Invalid write position" }
        val path = virtualPath(rawPath)
        if (path is VirtualPath.Game && nativeSaveKey(path) != null) {
            val current = runCatching { read(rawPath) }.getOrDefault(byteArrayOf())
            val targetPosition = if (append) current.size else position
            require(targetPosition + payload.size <= MAX_PAYLOAD_BYTES) { "File is too large" }
            val output = current.copyOf(maxOf(current.size, targetPosition + payload.size))
            payload.copyInto(output, targetPosition)
            write(rawPath, output, append = false)
            return payload.size
        }
        val file = writableFile(rawPath, allowRoot = false)
        file.parentFile?.let { parent ->
            require(parent.mkdirs() || parent.isDirectory) { "Unable to create parent directory" }
            require(!Files.isSymbolicLink(parent.toPath())) { "Symbolic links are not allowed" }
        }
        if (!file.exists() && path is VirtualPath.Game && !isGameDeleted(path)) {
            val immutable = gameFileSystem.resolve(path.path)
            if (immutable != null) write(rawPath, read(rawPath), append = false)
        }
        require(!file.exists() || file.isFile && !Files.isSymbolicLink(file.toPath())) { "Not a file" }
        if (path is VirtualPath.Game) clearGameDeleted(path)
        RandomAccessFile(file, "rw").use { output ->
            val targetPosition = if (append) output.length() else position.toLong()
            require(targetPosition + payload.size <= MAX_PAYLOAD_BYTES) { "File is too large" }
            output.seek(targetPosition)
            output.write(payload)
        }
        return payload.size
    }

    private fun unlink(rawPath: String) {
        when (val path = virtualPath(rawPath)) {
            VirtualPath.GameRoot, VirtualPath.DataRoot -> throw IllegalArgumentException("Not a file")
            is VirtualPath.Data -> {
                val file = writableFile(path, allowRoot = false)
                require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "Not a file" }
                check(file.delete()) { "Unable to delete file" }
            }

            is VirtualPath.Game -> {
                nativeSaveKey(path)?.let { key ->
                    val overlay = gameOverlayFile(path, allowRoot = false)
                    val existed = nativeSaveStore().delete(nativeGameId(), key) || overlay.isFile
                    require(existed) { "Not a file" }
                    if (overlay.exists()) {
                        require(overlay.isFile && !Files.isSymbolicLink(overlay.toPath())) { "Not a file" }
                        check(overlay.delete()) { "Unable to delete file" }
                    }
                    clearGameDeleted(path)
                    return
                }
                val overlay = gameOverlayFile(path, allowRoot = false)
                val immutableExists =
                    !isGameDeleted(path) && gameFileSystem.resolve(path.path) != null
                if (!overlay.isFile && !immutableExists) return
                if (overlay.exists()) {
                    require(overlay.isFile && !Files.isSymbolicLink(overlay.toPath())) { "Not a file" }
                    check(overlay.delete()) { "Unable to delete file" }
                }
                if (immutableExists) markGameDeleted(path)
            }
        }
    }

    private fun rename(rawPath: String, rawTarget: String) {
        val sourcePath = virtualPath(rawPath)
        val targetPath = virtualPath(rawTarget)
        if (
            sourcePath is VirtualPath.Game && nativeSaveKey(sourcePath) != null ||
            targetPath is VirtualPath.Game && nativeSaveKey(targetPath) != null
        ) {
            write(rawTarget, read(rawPath), append = false)
            unlink(rawPath)
            return
        }
        if (sourcePath is VirtualPath.Game && targetPath is VirtualPath.Game) {
            val sourceOverlay = gameOverlayFile(sourcePath, allowRoot = false)
            if (!sourceOverlay.exists() && !isGameDeleted(sourcePath) && gameFileSystem.resolve(
                    sourcePath.path
                ) != null
            ) {
                write(rawTarget, read(rawPath), append = false)
                unlink(rawPath)
                return
            }
        }
        val source = writableFile(rawPath, allowRoot = false)
        val target = writableFile(rawTarget, allowRoot = false)
        require(source.isFile && !Files.isSymbolicLink(source.toPath())) { "Not a file" }
        target.parentFile?.let { parent ->
            require(parent.mkdirs() || parent.isDirectory) { "Unable to create target directory" }
        }
        require(!Files.isSymbolicLink(target.toPath())) { "Symbolic links are not allowed" }
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        if (targetPath is VirtualPath.Game) clearGameDeleted(targetPath)
        if (sourcePath is VirtualPath.Game) clearGameDeleted(sourcePath)
    }

    private fun remove(rawPath: String, recursive: Boolean, force: Boolean) {
        val path = virtualPath(rawPath)
        if (path is VirtualPath.Game && nativeSaveKey(path) != null) {
            if (!exists(rawPath)) {
                require(force) { "Missing path" }
                return
            }
            unlink(rawPath)
            return
        }
        if (path is VirtualPath.Game) {
            if (!exists(rawPath)) {
                require(force) { "Missing path" }
                return
            }
            val value = stat(rawPath)
            val directory = value.getValue("directory").jsonPrimitive.booleanOrNull == true
            if (directory && !recursive) {
                require(list(rawPath).isEmpty()) { "Directory is not empty" }
            }
            val overlay = gameOverlayFile(path, allowRoot = false)
            if (overlay.exists()) {
                val deleted = if (overlay.isDirectory) deleteTree(overlay) else overlay.delete()
                check(deleted && !overlay.exists()) { "Unable to remove overlay path" }
            }
            markGameDeleted(path)
            return
        }
        val file = writableFile(rawPath, allowRoot = false)
        if (!file.exists()) {
            require(force) { "Missing path" }
            return
        }
        require(!Files.isSymbolicLink(file.toPath())) { "Symbolic links are not allowed" }
        if (file.isDirectory) {
            require(recursive || file.listFiles().orEmpty().isEmpty()) { "Directory is not empty" }
            val deleted = if (recursive) deleteTree(file) else file.delete()
            check(deleted && !file.exists()) { "Unable to remove directory" }
        } else {
            check(file.delete()) { "Unable to remove file" }
        }
    }

    private fun stat(rawPath: String): JsonObject = when (val path = virtualPath(rawPath)) {
        VirtualPath.GameRoot, VirtualPath.DataRoot -> statResult(
            isFile = false,
            isDirectory = true,
            size = 0
        )

        is VirtualPath.Game -> {
            nativeSaveKey(path)?.let { key ->
                nativeSaveRead(key)?.let { payload ->
                    return statResult(
                        isFile = true,
                        isDirectory = false,
                        size = payload.size.toLong()
                    )
                }
            }
            val overlay = gameOverlayFile(path, allowRoot = false)
            if (overlay.exists()) {
                require(!Files.isSymbolicLink(overlay.toPath())) { "Missing path" }
                statResult(overlay.isFile, overlay.isDirectory, overlay.length())
            } else {
                require(!isGameDeleted(path)) { "Missing path" }
                val asset = gameFileSystem.resolve(path.path)
                when {
                    asset != null -> statResult(
                        isFile = true,
                        isDirectory = false,
                        size = asset.storedSize
                    )

                    gameFileSystem.list(path.path) != null -> statResult(
                        isFile = false,
                        isDirectory = true,
                        size = 0,
                    )

                    else -> throw IllegalArgumentException("Missing path")
                }
            }
        }

        is VirtualPath.Data -> {
            val file = writableFile(path, allowRoot = false)
            require(file.exists() && !Files.isSymbolicLink(file.toPath())) { "Missing path" }
            statResult(file.isFile, file.isDirectory, file.length())
        }
    }

    private fun statResult(isFile: Boolean, isDirectory: Boolean, size: Long) = buildJsonObject {
        put("file", JsonPrimitive(isFile))
        put("directory", JsonPrimitive(isDirectory))
        put("size", JsonPrimitive(size))
    }

    private fun nativeSaveKey(path: VirtualPath.Game): String? {
        if (saveStore == null || gameId == null) return null
        val filename = path.path.removePrefix("save/")
        if (filename == path.path || '/' in filename || !SAVE_FILENAME.matches(filename)) return null
        val backup = filename.endsWith(ENGINE_BACKUP_SUFFIX, ignoreCase = true)
        val primaryName = if (backup) filename.dropLast(ENGINE_BACKUP_SUFFIX.length) else filename
        val key = when {
            primaryName.equals("config.rpgsave", ignoreCase = true) -> "config"
            primaryName.equals("global.rpgsave", ignoreCase = true) -> "global"
            FILE_SAVE.matches(primaryName) -> {
                val suffix = FILE_SAVE.matchEntire(primaryName)!!.groupValues[1]
                if (suffix.all(Char::isDigit)) "file$suffix" else "plugin-$suffix"
            }

            primaryName.endsWith(".rpgsave", ignoreCase = true) ->
                primaryName.dropLast(".rpgsave".length)

            else -> "node-$primaryName"
        }
        if (!SAVE_KEY.matches(key)) return null
        return if (backup) "$key-engine-backup" else key
    }

    private fun nativeSaveEntries(path: VirtualPath.Game): List<String> {
        if (path.path != "save" || saveStore == null || gameId == null) return emptyList()
        return saveStore.keys(gameId).mapNotNull(::nativeSaveFilename)
    }

    private fun nativeSaveFilename(key: String): String? {
        val backup = key.endsWith("-engine-backup")
        val primaryKey = if (backup) key.removeSuffix("-engine-backup") else key
        val filename = when {
            primaryKey == "config" -> "config.rpgsave"
            primaryKey == "global" -> "global.rpgsave"
            FILE_KEY.matches(primaryKey) -> "$primaryKey.rpgsave"
            primaryKey.startsWith("plugin-") -> "file${primaryKey.removePrefix("plugin-")}.rpgsave"
            primaryKey.startsWith("node-") -> primaryKey.removePrefix("node-")
            SAVE_KEY.matches(primaryKey) -> "$primaryKey.rpgsave"
            else -> return null
        }
        return if (backup) "$filename.bak" else filename
    }

    private fun nativeSaveRead(key: String): ByteArray? =
        nativeSaveStore().read(nativeGameId(), key)

    private fun nativeSaveExists(key: String): Boolean =
        key in nativeSaveStore().keys(nativeGameId())

    private fun nativeSaveStore(): GameSaveStore = checkNotNull(saveStore)

    private fun nativeGameId(): String = checkNotNull(gameId)

    private fun writableFile(rawPath: String, allowRoot: Boolean): File = when (
        val path = virtualPath(rawPath)
    ) {
        VirtualPath.DataRoot -> {
            require(allowRoot) { "The data root is not a file" }
            dataRoot
        }

        is VirtualPath.Data -> writableFile(path, allowRoot)
        is VirtualPath.Game -> gameOverlayFile(path, allowRoot)
        VirtualPath.GameRoot -> {
            require(allowRoot) { "The game root is not a file" }
            gameOverlayRoot
        }
    }

    private fun writableFile(path: VirtualPath.Data, allowRoot: Boolean): File {
        require(!isReservedDataPath(path.path)) { "Path is reserved for runtime internals" }
        val unresolved = File(dataRoot, path.path).absoluteFile
        requireNoSymbolicLinkComponents(dataRoot, unresolved)
        val candidate = unresolved.canonicalFile
        require(candidate.toPath().startsWith(dataRoot.toPath())) { "Path escapes the data root" }
        require(allowRoot || candidate != dataRoot) { "The data root is not a file" }
        return candidate
    }

    private fun gameOverlayFile(path: VirtualPath.Game, allowRoot: Boolean): File {
        val unresolved = File(gameOverlayRoot, path.path).absoluteFile
        requireNoSymbolicLinkComponents(gameOverlayRoot, unresolved)
        val candidate = unresolved.canonicalFile
        require(
            candidate.toPath().startsWith(gameOverlayRoot.toPath())
        ) { "Path escapes the game overlay" }
        require(allowRoot || candidate != gameOverlayRoot) { "The game root is not a file" }
        return candidate
    }

    private fun gameDeletedFile(path: VirtualPath.Game): File {
        val unresolved = File(gameDeletedRoot, path.path + DELETED_SUFFIX).absoluteFile
        requireNoSymbolicLinkComponents(gameDeletedRoot, unresolved)
        val candidate = unresolved.canonicalFile
        require(
            candidate.toPath().startsWith(gameDeletedRoot.toPath())
        ) { "Path escapes deletion index" }
        return candidate
    }

    private fun isGameDeleted(path: VirtualPath.Game): Boolean {
        val segments = path.path.split('/')
        return segments.indices.any { index ->
            gameDeletedFile(VirtualPath.Game(segments.take(index + 1).joinToString("/"))).isFile
        }
    }

    private fun markGameDeleted(path: VirtualPath.Game) {
        val marker = gameDeletedFile(path)
        marker.parentFile?.let { require(it.mkdirs() || it.isDirectory) { "Unable to update deletion index" } }
        require(!Files.isSymbolicLink(marker.toPath())) { "Symbolic links are not allowed" }
        if (!marker.exists()) check(marker.createNewFile()) { "Unable to update deletion index" }
    }

    private fun clearGameDeleted(path: VirtualPath.Game) {
        val segments = path.path.split('/')
        segments.indices.forEach { index ->
            Files.deleteIfExists(
                gameDeletedFile(
                    VirtualPath.Game(
                        segments.take(index + 1).joinToString("/")
                    )
                ).toPath(),
            )
        }
    }

    private fun deletedEntries(path: VirtualPath.Game): Set<String> {
        val unresolved = File(gameDeletedRoot, path.path).absoluteFile
        requireNoSymbolicLinkComponents(gameDeletedRoot, unresolved)
        val directory = unresolved.canonicalFile
        require(
            directory.toPath().startsWith(gameDeletedRoot.toPath())
        ) { "Path escapes deletion index" }
        return directory.listFiles().orEmpty()
            .asSequence()
            .filter {
                it.isFile && !Files.isSymbolicLink(it.toPath()) && it.name.endsWith(
                    DELETED_SUFFIX
                )
            }
            .map { it.name.removeSuffix(DELETED_SUFFIX) }
            .toSet()
    }

    private fun dataPath(rawPath: String): VirtualPath.Data {
        val path = GamePath.parse(rawPath).value
        require(!isReservedDataPath(path)) { "Path is reserved for runtime internals" }
        return VirtualPath.Data(path)
    }

    private fun isReservedDataPath(path: String): Boolean =
        isReservedDataEntry(path.substringBefore('/'))

    private fun isReservedDataEntry(name: String): Boolean =
        name.lowercase(Locale.ROOT) in RESERVED_DATA_ENTRIES

    private fun requireNoSymbolicLinkComponents(root: File, candidate: File) {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        var current: Path? = candidate.toPath().toAbsolutePath().normalize()
        require(current?.startsWith(rootPath) == true) { "Path escapes its writable root" }
        while (current != null) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(current)) { "Symbolic links are not allowed" }
            }
            if (current == rootPath) return
            current = current.parent
        }
        throw IllegalArgumentException("Path escapes its writable root")
    }

    private fun deleteTree(directory: File): Boolean {
        val root = directory.toPath()
        require(!Files.isSymbolicLink(root)) { "Symbolic links are not allowed" }
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    error: java.io.IOException?
                ): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return !Files.exists(root, LinkOption.NOFOLLOW_LINKS)
    }

    private fun virtualPath(rawPath: String): VirtualPath {
        require(rawPath.length <= MAX_PATH_CHARS && rawPath.none { it.code < 0x20 }) {
            "Invalid path"
        }
        val normalized = rawPath.replace('\\', '/').ifBlank { "/game" }
        return when {
            normalized == "/game" -> VirtualPath.GameRoot
            normalized.startsWith("/game/") -> VirtualPath.Game(
                GamePath.parse(normalized.removePrefix("/game/")).value,
            )

            normalized == "/data" -> VirtualPath.DataRoot
            normalized.startsWith("/data/") -> dataPath(normalized.removePrefix("/data/"))

            normalized == "/logs" -> VirtualPath.Data("logs")
            normalized.startsWith("/logs/") -> VirtualPath.Data(
                GamePath.parse("logs/" + normalized.removePrefix("/logs/")).value,
            )

            normalized.startsWith('/') || DRIVE_PATH.matches(normalized) ->
                throw IllegalArgumentException("Path is outside the virtual roots")

            else -> VirtualPath.Game(GamePath.parse(normalized).value)
        }
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.jsonPrimitive?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("Missing field")

    private fun JsonObject.int(name: String): Int =
        get(name)?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("Missing field")

    private fun JsonObject.boolean(name: String, fallback: Boolean): Boolean =
        get(name)?.jsonPrimitive?.booleanOrNull ?: fallback

    private fun JsonObject.payload(): ByteArray {
        val value = string("data")
        require(value.length <= MAX_BASE64_CHARS && value.length % 4 == 0) { "Invalid payload" }
        return Base64.getDecoder().decode(value).also {
            require(it.size <= MAX_PAYLOAD_BYTES) { "File is too large" }
        }
    }

    private fun success(id: String, data: JsonElement? = null): String = response(id, true, data)

    private fun failure(id: String, error: String): String = response(id, false, error = error)

    private fun response(
        id: String,
        ok: Boolean,
        data: JsonElement? = null,
        error: String? = null,
    ): String = buildJsonObject {
        put("v", JsonPrimitive(VERSION))
        put("id", JsonPrimitive(id))
        put("ok", JsonPrimitive(ok))
        data?.let { put("data", it) }
        error?.let { put("error", JsonPrimitive(it)) }
    }.toString()

    private fun joinVirtualPath(parent: String, child: String): String =
        parent.trimEnd('/') + "/" + child

    private fun cleanupManagedGarbage(now: Long = System.currentTimeMillis()) {
        val marker = File(dataRoot, CLEANUP_MARKER)
        if (Files.isSymbolicLink(marker.toPath())) return
        if (marker.isFile && now - marker.lastModified() < CLEANUP_INTERVAL_MILLIS) return
        MANAGED_GARBAGE.forEach { policy ->
            runCatching { cleanupDirectory(File(dataRoot, policy.name), policy, now) }
        }
        runCatching {
            if (!marker.exists()) marker.createNewFile()
            marker.setLastModified(now)
        }
    }

    private fun cleanupDirectory(directory: File, policy: GarbagePolicy, now: Long) {
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) return
        val files = mutableListOf<File>()
        val directories = mutableListOf<File>()
        var visited = 0
        Files.walkFileTree(
            directory.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (visited++ >= MAX_CLEANUP_ENTRIES) return FileVisitResult.TERMINATE
                    directories += dir.toFile()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (visited++ >= MAX_CLEANUP_ENTRIES) return FileVisitResult.TERMINATE
                    if (attrs.isRegularFile && !attrs.isSymbolicLink) files += file.toFile()
                    return FileVisitResult.CONTINUE
                }
            },
        )
        files.filter { now - it.lastModified() > policy.maxAgeMillis }.forEach { it.delete() }
        val retained = files.filter(File::exists).sortedBy(File::lastModified).toMutableList()
        var bytes = retained.sumOf(File::length)
        while (bytes > policy.maxBytes && retained.isNotEmpty()) {
            val oldest = retained.removeAt(0)
            val size = oldest.length()
            if (oldest.delete()) bytes -= size
        }
        directories.asReversed()
            .filter { it != directory }
            .forEach { if (it.listFiles().orEmpty().isEmpty()) it.delete() }
    }

    private sealed interface VirtualPath {
        data object GameRoot : VirtualPath
        data class Game(val path: String) : VirtualPath
        data object DataRoot : VirtualPath
        data class Data(val path: String) : VirtualPath
    }

    private class ProtocolFailure(val reason: String) : RuntimeException()

    private companion object {
        const val GAME_OVERLAY_DIRECTORY = "game-overlay"
        const val GAME_DELETED_DIRECTORY = "game-deleted"
        const val DELETED_SUFFIX = ".deleted"
        const val VERSION = 1
        const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024
        const val MAX_BASE64_CHARS = ((MAX_PAYLOAD_BYTES + 2) / 3) * 4
        const val MAX_MESSAGE_CHARS = MAX_BASE64_CHARS + 2048
        const val MAX_PATH_CHARS = 1024
        const val INVALID_ID = "invalid"
        const val CLEANUP_MARKER = ".makerplay-last-cleanup"
        const val CLEANUP_INTERVAL_MILLIS = 6 * 60 * 60 * 1000L
        const val MAX_CLEANUP_ENTRIES = 2048
        val ID = Regex("[A-Za-z0-9-]{1,64}")
        val DRIVE_PATH = Regex("^[a-zA-Z]:.*")
        val SAVE_FILENAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SAVE_KEY = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val FILE_SAVE = Regex("file([A-Za-z0-9._-]+)\\.rpgsave", RegexOption.IGNORE_CASE)
        val FILE_KEY = Regex("file[0-9]+")
        const val ENGINE_BACKUP_SUFFIX = ".bak"
        val RESERVED_DATA_ENTRIES = setOf(
            GAME_OVERLAY_DIRECTORY,
            GAME_DELETED_DIRECTORY,
            CLEANUP_MARKER,
        )
        val MANAGED_GARBAGE = listOf(
            GarbagePolicy("tmp", 7 * 24 * 60 * 60 * 1000L, 32L * 1024 * 1024),
            GarbagePolicy("cache", 14 * 24 * 60 * 60 * 1000L, 64L * 1024 * 1024),
            GarbagePolicy("logs", 30 * 24 * 60 * 60 * 1000L, 16L * 1024 * 1024),
        )
    }

    private data class GarbagePolicy(
        val name: String,
        val maxAgeMillis: Long,
        val maxBytes: Long,
    )
}