package io.github.gdlbo.makerplay.feature.importer

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

class FileImportSource private constructor(
    private val root: File,
    allowedRoots: List<File>,
    private val opener: ConfinedFileOpener,
) : ImportSource {
    private val rootPath = validateRoot(root, allowedRoots)

    override val rootName: String = root.name

    constructor(root: File, allowedRoots: List<File>) : this(
        root = root,
        allowedRoots = allowedRoots,
        opener = AndroidConfinedFileOpener,
    )

    override fun entries(): List<ImportEntry> = runBlocking { scanEntries {} }

    override suspend fun scanEntries(
        onEntryDiscovered: suspend (ImportEntry) -> Unit,
    ): List<ImportEntry> {
        val entries = ArrayList<ImportEntry>()

        suspend fun walk(directory: Path, prefix: String, depth: Int) {
            if (depth > MAX_DEPTH) throw ImportFailure("The selected folder is nested too deeply.")
            val children = directory.toFile().listFiles()
                ?: throw ImportFailure("A directory in the selected folder could not be read.")
            children.forEach { child ->
                val childPath = child.toPath()
                val attributes = readAttributes(childPath)
                if (attributes.isSymbolicLink || Files.isSymbolicLink(childPath)) {
                    throw ImportFailure("The selected folder contains an unsupported file link.")
                }
                val confinedPath = childPath.toRealPath(LinkOption.NOFOLLOW_LINKS)
                if (!confinedPath.startsWith(rootPath)) {
                    throw ImportFailure("The selected folder contains a file outside its root.")
                }
                val name = child.name
                validateName(name)
                val relativePath = if (prefix.isEmpty()) name else "$prefix/$name"
                when {
                    attributes.isDirectory -> walk(confinedPath, relativePath, depth + 1)
                    attributes.isRegularFile -> {
                        val entry = ImportEntry(
                            relativePath = relativePath,
                            size = attributes.size().coerceAtLeast(0L),
                            open = { openConfined(confinedPath) },
                        )
                        entries += entry
                        onEntryDiscovered(entry)
                    }

                    else -> throw ImportFailure("The selected folder contains an unsupported file.")
                }
            }
        }

        walk(rootPath, prefix = "", depth = 0)
        return entries
    }

    private fun openConfined(path: Path): InputStream {
        if (Files.isSymbolicLink(path)) {
            throw ImportFailure("A game file changed while it was being imported.")
        }
        val currentPath = runCatching { path.toRealPath(LinkOption.NOFOLLOW_LINKS) }
            .getOrElse { throw ImportFailure("A game file is no longer available.", it) }
        if (!currentPath.startsWith(rootPath) || !Files.isRegularFile(
                currentPath,
                LinkOption.NOFOLLOW_LINKS
            )
        ) {
            throw ImportFailure("A game file changed while it was being imported.")
        }
        return runCatching { opener.open(currentPath, rootPath) }
            .getOrElse { throw ImportFailure("A game file could not be opened.", it) }
    }

    private fun readAttributes(path: Path): BasicFileAttributes = runCatching {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }.getOrElse { throw ImportFailure("An item in the selected folder could not be read.", it) }

    private fun validateName(name: String) {
        if (name.isBlank() || name == "." || name == ".." || '/' in name || '\\' in name) {
            throw ImportFailure("The selected folder contains an unsafe file name.")
        }
    }

    internal companion object {
        const val MAX_DEPTH = 64

        fun forJvmTest(root: File, allowedRoots: List<File>): FileImportSource =
            FileImportSource(root, allowedRoots, JdkConfinedFileOpener)

        fun validateRoot(root: File, allowedRoots: List<File>): Path {
            if (Files.isSymbolicLink(root.toPath())) {
                throw ImportFailure("The selected folder cannot be a file link.")
            }
            val rootPath = runCatching { root.toPath().toRealPath(LinkOption.NOFOLLOW_LINKS) }
                .getOrElse {
                    throw ImportFailure(
                        "The selected folder is no longer available.",
                        it
                    )
                }
            if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
                throw ImportFailure("The selected item is not a folder.")
            }
            val allowed = allowedRoots.any { allowedRoot ->
                runCatching { rootPath.startsWith(allowedRoot.toPath().toRealPath()) }.getOrDefault(
                    false
                )
            }
            if (!allowed) throw ImportFailure("The selected folder is outside shared storage.")
            return rootPath
        }
    }
}

private fun interface ConfinedFileOpener {
    fun open(path: Path, rootPath: Path): InputStream
}

private object JdkConfinedFileOpener : ConfinedFileOpener {
    override fun open(path: Path, rootPath: Path): InputStream = FileInputStream(path.toFile())
}

private object AndroidConfinedFileOpener : ConfinedFileOpener {
    override fun open(path: Path, rootPath: Path): InputStream {
        val rawDescriptor = Os.open(
            path.toString(),
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
            0,
        )
        val parcelDescriptor = try {
            ParcelFileDescriptor.dup(rawDescriptor)
        } finally {
            Os.close(rawDescriptor)
        }
        val openedTarget = runCatching {
            File("/proc/self/fd/${parcelDescriptor.fd}").canonicalFile.toPath()
        }.getOrElse { error ->
            parcelDescriptor.close()
            throw error
        }
        if (!openedTarget.startsWith(rootPath)) {
            parcelDescriptor.close()
            throw ImportFailure("A game file changed while it was being imported.")
        }
        return ParcelFileDescriptor.AutoCloseInputStream(parcelDescriptor)
    }
}