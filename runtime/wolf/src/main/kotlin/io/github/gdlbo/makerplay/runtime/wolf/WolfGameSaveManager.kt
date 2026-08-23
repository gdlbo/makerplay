package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.wolfformat.WolfFormatException
import java.io.File
import java.nio.file.Files

/**
 * Persists [WolfSaveFormat] payloads with atomic file replacement: content is
 * written to a temp file in the same directory then moved, so a crash can
 * never leave a half-written save behind.
 */
class WolfGameSaveManager(private val savesRoot: File) {

    init {
        savesRoot.mkdirs()
    }

    fun save(slot: String, state: WolfSaveFormat.GameState) {
        val target = slotFile(slot)
        val temp = File(savesRoot, "${target.name}.tmp")
        temp.writeBytes(WolfSaveFormat.encode(state))
        if (!temp.renameTo(target)) {
            // Cross-device or platform quirk: fall back to copy + delete.
            target.outputStream().use { output ->
                temp.inputStream().use { input -> input.copyTo(output) }
            }
            temp.delete()
        }
    }

    fun load(slot: String): WolfSaveFormat.GameState =
        WolfSaveFormat.decode(slotFile(slot).readBytes())

    fun has(slot: String): Boolean = slotFile(slot).isFile

    private fun slotFile(slot: String): File {
        val safe = slot.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
        val target = File(savesRoot, "$safe.mkpsave")
        if (!target.canonicalPath.startsWith(savesRoot.canonicalPath + File.separator) &&
            target.canonicalFile.parentFile != savesRoot.canonicalFile
        ) {
            throw WolfFormatException("Save slot escapes directory: $slot")
        }
        return target
    }
}
