package io.github.gdlbo.makerplay.feature.importer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.runBlocking

class SafImportSource(
    private val context: Context,
    private val treeUri: Uri,
) : ImportSource {
    private var selectedRootName: String? = null

    override val rootName: String?
        get() = selectedRootName

    override fun entries(): List<ImportEntry> = runBlocking { scanEntries {} }

    override suspend fun scanEntries(
        onEntryDiscovered: suspend (ImportEntry) -> Unit,
    ): List<ImportEntry> {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw ImportFailure("The selected folder is no longer available.")
        if (!root.isDirectory) throw ImportFailure("The selected item is not a folder.")
        selectedRootName = root.name
        val entries = ArrayList<ImportEntry>()
        val visitedDirectories = HashSet<String>()
        walk(root, prefix = "", depth = 0, visitedDirectories, entries, onEntryDiscovered)
        return entries
    }

    private suspend fun walk(
        directory: DocumentFile,
        prefix: String,
        depth: Int,
        visitedDirectories: MutableSet<String>,
        entries: MutableList<ImportEntry>,
        onEntryDiscovered: suspend (ImportEntry) -> Unit,
    ) {
        if (depth > MAX_DEPTH) throw ImportFailure("The selected folder is nested too deeply.")
        if (!visitedDirectories.add(directory.uri.toString())) {
            throw ImportFailure("The selected folder contains a directory cycle.")
        }
        directory.listFiles().forEach { child ->
            val name =
                child.name ?: throw ImportFailure("The selected folder contains an unnamed file.")
            validateProviderName(name)
            val relativePath = if (prefix.isEmpty()) name else "$prefix/$name"
            when {
                child.isDirectory -> walk(
                    child,
                    relativePath,
                    depth + 1,
                    visitedDirectories,
                    entries,
                    onEntryDiscovered,
                )

                child.isFile -> {
                    val uri = child.uri
                    val entry = ImportEntry(
                        relativePath = relativePath,
                        size = child.length().coerceAtLeast(0L),
                        open = {
                            context.contentResolver.openInputStream(uri)
                                ?: throw ImportFailure("A game file could not be opened.")
                        },
                    )
                    entries += entry
                    onEntryDiscovered(entry)
                }

                else -> throw ImportFailure("The selected folder contains an unsupported document.")
            }
        }
    }

    private fun validateProviderName(name: String) {
        if (name.isBlank() || name == "." || name == ".." || '/' in name || '\\' in name) {
            throw ImportFailure("The selected folder contains an unsafe file name.")
        }
    }

    private companion object {
        const val MAX_DEPTH = 64
    }
}