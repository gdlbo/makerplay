package io.github.gdlbo.makerplay.vfs

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.Locale

data class IndexedGameFile(
    val path: GamePath,
    val size: Long,
    val lastModifiedMillis: Long,
)

class GameFileIndex private constructor(
    private val root: File,
    entries: List<IndexedGameFile>,
) {
    val entries: List<IndexedGameFile> =
        Collections.unmodifiableList(entries.sortedBy { it.path.value })
    private val exact = buildMap {
        this@GameFileIndex.entries.forEach { entry ->
            require(put(entry.path.value, entry) == null) { "Game index contains duplicate paths" }
        }
    }
    private val folded = buildMap {
        this@GameFileIndex.entries.forEach { entry ->
            val previous = put(entry.path.folded, entry)
            require(previous == null || previous.path == entry.path) {
                "Game files collide when matched case-insensitively"
            }
        }
    }
    private val directoryChildren: Map<String, List<String>> =
        buildMap<String, MutableSet<String>> {
            this@GameFileIndex.entries.forEach { entry ->
                val segments = entry.path.value.split('/')
                for (childIndex in segments.indices) {
                    val directory =
                        segments.take(childIndex).joinToString("/").lowercase(Locale.ROOT)
                    getOrPut(directory, ::linkedSetOf).add(segments[childIndex])
                }
            }
        }.mapValues { (_, children) -> children.sorted() }

    fun exact(path: GamePath): IndexedGameFile? = exact[path.value]

    fun folded(path: GamePath): IndexedGameFile? = folded[path.folded]

    fun list(rawPath: String): List<String>? {
        val key = rawPath.trim('/').lowercase(Locale.ROOT)
        return directoryChildren[key]
    }

    fun file(entry: IndexedGameFile): File? {
        var file = root
        entry.path.value.split('/').forEach { segment ->
            file = File(file, segment)
            if (Files.isSymbolicLink(file.toPath())) return null
        }
        val canonicalFile = file.canonicalFile
        return canonicalFile.takeIf { it.toPath().startsWith(root.toPath()) }
    }

    fun write(indexRoot: File = root) {
        indexRoot.mkdirs()
        val target = File(indexRoot, INDEX_FILE)
        val temporary = File(indexRoot, INDEX_TEMP_FILE)
        FileOutputStream(temporary).use { fileOutput ->
            DataOutputStream(BufferedOutputStream(fileOutput)).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeInt(entries.size)
                entries.forEach { entry ->
                    output.writeUTF(entry.path.value)
                    output.writeLong(entry.size)
                    output.writeLong(entry.lastModifiedMillis)
                }
                output.flush()
                fileOutput.fd.sync()
            }
        }
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    companion object {
        const val INDEX_FILE = ".makerplay-vfs-index"
        private const val INDEX_TEMP_FILE = ".makerplay-vfs-index.tmp"
        private const val MAGIC = 0x47455646
        private const val FORMAT_VERSION = 1
        private const val MAX_DEPTH = 64
        private const val PRIVATE_METADATA_FILE = ".makerplay.properties"
        private const val CONTROLLER_LAYOUT_FILE = "gamepad.json"
        val RESERVED_FILE_NAMES: Set<String> = setOf(
            INDEX_FILE.lowercase(Locale.ROOT),
            INDEX_TEMP_FILE.lowercase(Locale.ROOT),
            CONTROLLER_LAYOUT_FILE.lowercase(Locale.ROOT),
        )

        fun loadOrBuild(root: File, indexRoot: File = root): GameFileIndex = try {
            read(root, indexRoot)
        } catch (_: Exception) {
            build(root).also { it.write(indexRoot) }
        }

        fun build(root: File): GameFileIndex {
            val canonicalRoot = root.canonicalFile
            require(canonicalRoot.isDirectory) { "Game root is not a directory" }
            val entries = ArrayList<IndexedGameFile>()

            fun walk(directory: File, depth: Int) {
                require(depth <= MAX_DEPTH) { "Game tree is nested too deeply" }
                Files.newDirectoryStream(directory.toPath()).use { children ->
                    children.forEach { childPath ->
                        val child = childPath.toFile()
                        require(!Files.isSymbolicLink(child.toPath())) { "Game tree contains a file link" }
                        val canonicalChild = child.canonicalFile
                        require(canonicalChild.toPath().startsWith(canonicalRoot.toPath())) {
                            "Game file escaped game root"
                        }
                        when {
                            canonicalChild.isDirectory -> walk(canonicalChild, depth + 1)
                            canonicalChild.isFile -> {
                                val isRootIndexFile = canonicalChild.parentFile == canonicalRoot &&
                                        canonicalChild.name.lowercase(Locale.ROOT) in RESERVED_FILE_NAMES
                                val isRootMetadata = canonicalChild.parentFile == canonicalRoot &&
                                        canonicalChild.name.equals(
                                            PRIVATE_METADATA_FILE,
                                            ignoreCase = true
                                        )
                                if (!isRootIndexFile && !isRootMetadata) {
                                    entries += IndexedGameFile(
                                        path = GamePath.parse(
                                            canonicalChild.relativeTo(canonicalRoot).invariantSeparatorsPath,
                                        ),
                                        size = canonicalChild.length(),
                                        lastModifiedMillis = canonicalChild.lastModified(),
                                    )
                                }
                            }

                            else -> error("Game tree contains an unsupported item")
                        }
                    }
                }
            }

            walk(canonicalRoot, 0)
            return GameFileIndex(canonicalRoot, entries)
        }

        private fun read(root: File, indexRoot: File): GameFileIndex {
            val canonicalRoot = root.canonicalFile
            DataInputStream(
                BufferedInputStream(
                    FileInputStream(
                        File(
                            indexRoot,
                            INDEX_FILE
                        )
                    )
                )
            ).use { input ->
                require(input.readInt() == MAGIC) { "Invalid VFS index" }
                require(input.readInt() == FORMAT_VERSION) { "Unsupported VFS index version" }
                val count = input.readInt()
                require(count >= 0) { "Invalid VFS index size" }
                val entries = List(count) {
                    val path = GamePath.parse(input.readUTF())
                    val size = input.readLong()
                    val modified = input.readLong()
                    require(size >= 0L && modified >= 0L) { "Invalid VFS file metadata" }
                    val file = File(canonicalRoot, path.value)
                    require(
                        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                                !Files.isSymbolicLink(file.toPath()) &&
                                file.length() == size && file.lastModified() == modified,
                    ) { "VFS index is stale" }
                    IndexedGameFile(path, size, modified)
                }
                require(input.read() == -1) { "VFS index contains trailing data" }
                return GameFileIndex(canonicalRoot, entries)
            }
        }
    }
}