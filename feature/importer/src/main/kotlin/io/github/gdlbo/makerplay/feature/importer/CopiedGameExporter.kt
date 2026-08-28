package io.github.gdlbo.makerplay.feature.importer

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packages a privately copied game install (and optional saves) into a zip for sharing.
 * DIRECT/link installs are rejected by the caller; this only archives local folders.
 */
object CopiedGameExporter {
    const val GAME_PREFIX = "game/"
    const val SAVES_PREFIX = "saves/"

    fun packageZip(
        gameDirectory: File,
        savesDirectory: File?,
        outputZip: File,
        bufferBytes: Int = DEFAULT_BUFFER_BYTES,
    ): File {
        require(gameDirectory.isDirectory && !Files.isSymbolicLink(gameDirectory.toPath())) {
            "Game directory must be a real directory"
        }
        require(bufferBytes > 0) { "bufferBytes must be positive" }
        outputZip.parentFile?.mkdirs()
        if (outputZip.exists()) {
            check(outputZip.delete()) { "Unable to replace existing export archive" }
        }
        val temporary = File(outputZip.parentFile, "${outputZip.name}.tmp")
        temporary.delete()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(temporary), bufferBytes)).use { zip ->
            addDirectory(zip, gameDirectory, GAME_PREFIX, bufferBytes)
            if (savesDirectory != null &&
                savesDirectory.isDirectory &&
                !Files.isSymbolicLink(savesDirectory.toPath())
            ) {
                addDirectory(zip, savesDirectory, SAVES_PREFIX, bufferBytes)
            }
        }
        check(temporary.renameTo(outputZip) || (outputZip.delete() && temporary.renameTo(outputZip))) {
            "Unable to finalize export archive"
        }
        return outputZip
    }

    fun sanitizeFileName(title: String, gameId: String): String {
        val cleaned = title.trim()
            .replace(UNSAFE_FILE_CHARS, "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(MAX_TITLE_CHARS)
        val base = cleaned.ifBlank { gameId }.ifBlank { "game" }
        return "$base.zip"
    }

    private fun addDirectory(
        zip: ZipOutputStream,
        root: File,
        entryPrefix: String,
        bufferBytes: Int,
    ) {
        val rootPath = root.canonicalFile.toPath()
        val buffer = ByteArray(bufferBytes)
        Files.walk(rootPath).use { stream ->
            stream.forEach { path ->
                if (Files.isSymbolicLink(path)) return@forEach
                val relative = rootPath.relativize(path).toString().replace('\\', '/')
                if (relative.contains("..")) return@forEach
                val entryName = if (relative.isEmpty()) {
                    entryPrefix
                } else {
                    entryPrefix + relative.trimStart('/')
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    if (!entryName.endsWith("/")) {
                        zip.putNextEntry(ZipEntry("$entryName/"))
                        zip.closeEntry()
                    } else {
                        zip.putNextEntry(ZipEntry(entryName))
                        zip.closeEntry()
                    }
                    return@forEach
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@forEach
                zip.putNextEntry(ZipEntry(entryName))
                FileInputStream(path.toFile()).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        zip.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private const val DEFAULT_BUFFER_BYTES = 64 * 1024
    private const val MAX_TITLE_CHARS = 64
    private val UNSAFE_FILE_CHARS = Regex("""[^\p{L}\p{N}._\- ]+""")
}
