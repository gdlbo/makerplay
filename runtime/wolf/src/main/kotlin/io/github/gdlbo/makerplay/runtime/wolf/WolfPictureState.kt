package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.wolfformat.EventCommand
import io.github.gdlbo.makerplay.wolfformat.GameDataSource

/**
 * Presentation state for WOLF picture commands (opcode 150) and screen
 * effects (151/160-162/290). Pictures live in numbered slots; showing a
 * picture replaces its slot, erasing clears it.
 *
 * The command's binary parameter layout varies by editor revision, so the
 * parser uses the documented anchor points: params[0] is the slot index and
 * the first non-empty string names the image file. Erase operations carry no
 * file name.
 */
class WolfPictureState {
    data class Picture(
        val slot: Int,
        val fileName: String,
        val x: Int,
        val y: Int,
    )

    private val slots = LinkedHashMap<Int, Picture>()
    private var revision = 0L

    /** Monotonic change counter; the renderer recomposes when it advances. */
    fun version(): Long = revision

    fun all(): List<Picture> = slots.values.toList()

    fun clear() {
        if (slots.isNotEmpty()) revision++
        slots.clear()
    }

    /** Applies a picture command; returns true when visible state changed. */
    fun apply(command: EventCommand): Boolean {
        // WolfTL Command.hpp: type = (args[0] >> 4) & 0x07 (0 file, 1 fileString,
        // 2 text, 3 windowFile, 4 windowString); slot = args[1]. The low nibble
        // of args[0] selects the process: 0 show, 1 move, 2 erase (observed in
        // observed in shipped v3.5 titles: show logo -> move -> erase -> show title).
        val packed = command.params.getOrNull(0) ?: 0
        val type = (packed ushr 4) and 0x7
        val process = packed and 0xF
        val slot = command.params.getOrNull(1)?.coerceIn(0, 999_999) ?: return false
        val isText = type == 2 || type == 4
        val fileName = command.strings.firstOrNull()?.takeIf { it.isNotBlank() }
        return when {
            process == 2 || (process == 0 && fileName == null && !isText) -> {
                if (slots.remove(slot) != null) {
                    revision++
                    true
                } else {
                    false
                }
            }
            process == 1 -> {
                // Move: reposition the existing picture.
                val existing = slots[slot] ?: return false
                val x = command.params.getOrNull(2) ?: existing.x
                val y = command.params.getOrNull(3) ?: existing.y
                slots[slot] = existing.copy(x = x, y = y)
                revision++
                true
            }
            isText -> {
                // Text pictures render via the message layer, not the image stack.
                revision++
                true
            }
            fileName != null -> {
                val x = command.params.getOrNull(2) ?: 0
                val y = command.params.getOrNull(3) ?: 0
                slots[slot] = Picture(slot = slot, fileName = fileName, x = x, y = y)
                revision++
                true
            }
            else -> false
        }
    }

    /**
     * Resolves a picture file name against the deployment's image folders.
     * Returns the archive path when found, null otherwise.
     */
    fun resolvePath(source: GameDataSource, fileName: String): String? {
        val cleaned = fileName.removePrefix("/").replace("\\", "/")
        if (cleaned.contains("\\\\") || cleaned.contains("\\f") || cleaned.contains("\\c")) {
            return null // unresolved escape tags; skip until interpolation lands
        }
        val candidates = listOf(
            "Data/Picture/$cleaned",
            "Data/SystemFile/$cleaned",
            "Data/CG/$cleaned",
            "Data/$cleaned",
        )
        return candidates.firstOrNull { runCatching { source.has(it) }.getOrDefault(false) }
    }
}
