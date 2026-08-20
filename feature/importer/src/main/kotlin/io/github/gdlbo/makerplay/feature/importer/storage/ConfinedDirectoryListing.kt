package io.github.gdlbo.makerplay.feature.importer.storage

import java.io.File
import java.nio.file.Files

internal fun listDirectories(current: File, roots: List<File>): Result<List<File>> = runCatching {
    require(roots.any(current::isInside))
    current.listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory && it.canRead() && !Files.isSymbolicLink(it.toPath()) }
        ?.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        ?.filter { child -> roots.any(child::isInside) }
        ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        ?.toList()
        ?: error("Directory cannot be listed")
}

internal fun hasNwRuntime(directory: File): Boolean {
    if (sequenceOf("nw.exe", "nw.dll").any { File(directory, it).isFile }) return true
    return directory.listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
        ?.any { child -> sequenceOf("nw.exe", "nw.dll").any { File(child, it).isFile } }
        ?: false
}

internal fun File.isInside(root: File): Boolean = runCatching {
    canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
}.getOrDefault(false)