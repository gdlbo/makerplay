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
        val anchor: Int = 0,
        val opacity: Int = 255,
        val divisionWidth: Int = 1,
        val divisionHeight: Int = 1,
        val pattern: Int = 0,
        /** Percent zoom (100 = native). */
        val zoom: Int = 100,
        /** When true, [fileName] is display text rather than an image path. */
        val isText: Boolean = false,
        /** Built-in &lt;SQUARE&gt; fill size/color; null for normal pictures. */
        val fillWidth: Int? = null,
        val fillHeight: Int? = null,
        val fillColor: Int? = null,
    ) {
        val centerOrigin: Boolean get() = anchor == 1
    }

    private val slots = LinkedHashMap<Int, Picture>()
    private var revision = 0L
    private val pathCache = HashMap<String, String?>()

    /** Monotonic change counter; the renderer recomposes when it advances. */
    fun version(): Long = revision

    fun all(): List<Picture> = slots.values.toList()

    fun clear() {
        if (slots.isNotEmpty()) revision++
        slots.clear()
    }

    /**
     * Applies a picture effect (opcode 290). Full tweening is not modeled yet;
     * when a slot range is present, those pictures are snapped visible so
     * opening fades that start at opacity 0 still reveal their layers.
     */
    fun applyEffect(command: EventCommand): Boolean {
        // 290: [options, processTime, slotFrom, slotTo, a, b, c]
        // options low nibble = target (0 picture); bits 4-7 = effect type.
        val options = command.params.getOrNull(0) ?: return false
        val target = options and 0x0F
        if (target != 0) return false // character/map effects handled elsewhere
        val effectType = (options ushr 4) and 0x0F
        val a = command.params.getOrNull(2) ?: return false
        val b = command.params.getOrNull(3) ?: a
        val from = minOf(a, b)
        val to = maxOf(a, b)
        if (to < from || to - from > 1_000) return false
        val p4 = command.params.getOrNull(4) ?: 0
        val p5 = command.params.getOrNull(5) ?: 0
        var changed = false
        for (slot in from..to) {
            val existing = slots[slot] ?: continue
            val updated = when (effectType) {
                // Flash, color-correction, shake, and flicker are transient;
                // they have no stable slot state in this renderer.
                2 -> existing.copy(x = existing.x + p4, y = existing.y + p5)
                4 -> existing.copy(zoom = p4.takeIf { it in 1..400 } ?: existing.zoom)
                7 -> existing.copy(zoom = (existing.zoom + 10).coerceAtMost(400))
                8 -> existing.copy(
                    pattern = (existing.pattern + 1).coerceAtMost(
                        (existing.divisionWidth * existing.divisionHeight - 1).coerceAtLeast(0),
                    ),
                )
                9, 10 -> existing.copy(
                    pattern = (existing.pattern + 1) %
                        (existing.divisionWidth * existing.divisionHeight).coerceAtLeast(1),
                )
                else -> existing
            }
            if (updated != existing) {
                slots[slot] = updated
                changed = true
            }
        }
        if (changed) revision++
        return changed
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
        // Negative ids are background picture layers (behind the map).
        val slot = command.params.getOrNull(1)?.coerceIn(-99_999, 999_999) ?: return false
        val fileName = command.strings.firstOrNull()?.takeIf { it.isNotBlank() }
        // Type 2/4 are explicit text; also treat non-path labels (New Game) as text.
        val looksLikePath = fileName != null && (
            fileName.contains('/') || fileName.contains('\\') ||
                fileName.substringAfterLast('.').lowercase() in IMAGE_EXTS
            )
        val isText = type == 2 || type == 4
        return when {
            // Process 2 is the documented erase; some fade scripts also send 3.
            process == 2 || process == 3 || (process == 0 && fileName == null && !isText) -> {
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
                val x = command.params.getOrNull(7) ?: command.params.getOrNull(2) ?: existing.x
                val y = command.params.getOrNull(8) ?: command.params.getOrNull(3) ?: existing.y
                val opacity = normalizeOpacity(command.params.getOrNull(6), existing.opacity)
                slots[slot] = existing.copy(
                    x = x,
                    y = y,
                    anchor = (packed ushr 12) and 0xF,
                    opacity = opacity,
                    zoom = command.params.getOrNull(9)?.takeIf { it in 1..400 } ?: existing.zoom,
                )
                revision++
                true
            }
            isText && fileName != null -> {
                val x = command.params.getOrNull(7) ?: command.params.getOrNull(2) ?: 0
                val y = command.params.getOrNull(8) ?: command.params.getOrNull(3) ?: 0
                val opacity = normalizeOpacity(command.params.getOrNull(6), 255)
                val square = isSquarePrimitive(fileName)
                val fillW = if (square) command.params.getOrNull(3)?.takeIf { it > 0 } else null
                val fillH = if (square) command.params.getOrNull(4)?.takeIf { it > 0 } else null
                val fillColor = if (square) {
                    val r = command.params.getOrNull(command.params.size - 3)?.coerceIn(0, 255) ?: 0
                    val g = command.params.getOrNull(command.params.size - 2)?.coerceIn(0, 255) ?: 0
                    val b = command.params.getOrNull(command.params.size - 1)?.coerceIn(0, 255) ?: 0
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                } else {
                    null
                }
                slots[slot] = Picture(
                    slot = slot,
                    fileName = fileName,
                    x = x,
                    y = y,
                    anchor = (packed ushr 12) and 0xF,
                    opacity = opacity,
                    isText = true,
                    fillWidth = fillW,
                    fillHeight = fillH,
                    fillColor = fillColor,
                )
                revision++
                true
            }
            fileName != null -> {
                val x = command.params.getOrNull(7) ?: command.params.getOrNull(2) ?: 0
                val y = command.params.getOrNull(8) ?: command.params.getOrNull(3) ?: 0
                val opacity = normalizeOpacity(command.params.getOrNull(6), 255)
                slots[slot] = Picture(
                    slot = slot,
                    fileName = fileName,
                    x = x,
                    y = y,
                    anchor = (packed ushr 12) and 0xF,
                    opacity = opacity,
                    divisionWidth = command.params.getOrNull(3)?.coerceIn(1, 64) ?: 1,
                    divisionHeight = command.params.getOrNull(4)?.coerceIn(1, 64) ?: 1,
                    pattern = command.params.getOrNull(5)?.coerceAtLeast(0) ?: 0,
                    zoom = command.params.getOrNull(9)?.takeIf { it in 1..400 } ?: 100,
                )
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
            .removePrefix("/")
        if (cleaned.contains('\\') || cleaned.isEmpty()) {
            return null
        }
        if (pathCache.containsKey(cleaned)) return pathCache[cleaned]
        val subDirs = listOf(
            "",
            "Picture/",
            "scene/",
            "cg/",
            "CG/",
            "SystemGraphic/",
            "SystemFile/",
            "FIcon/",
            "cutin/",
            "hscene/",
            "hword/",
            "Kagura/",
            "DLC_data/",
            "Fog_BackGround/",
            "BattleEffect/",
            "CharaChip/",
            "MapChip/",
        )
        val hasExt = cleaned.contains('.')
        val exts = if (hasExt) listOf("") else listOf("", ".png", ".jpg", ".jpeg", ".bmp", ".webp")

        for (ext in exts) {
            val withExt = "$cleaned$ext"
            for (dir in subDirs) {
                val candidate = "Data/$dir$withExt"
                if (runCatching { source.has(candidate) }.getOrDefault(false)) {
                    pathCache[cleaned] = candidate
                    return candidate
                }
            }
        }
        for (ext in exts) {
            val withExt = "$cleaned$ext"
            for (dir in subDirs) {
                val candidate = "Data/$dir$withExt"
                val matched = caseInsensitivePath(source, candidate)
                if (matched != null) {
                    pathCache[cleaned] = matched
                    return matched
                }
            }
        }
        pathCache[cleaned] = null
        return null
    }

    private fun caseInsensitivePath(source: GameDataSource, relative: String): String? {
        val parts = relative.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var dir = ""
        val resolved = ArrayList<String>(parts.size)
        for (index in parts.indices) {
            val want = parts[index]
            val entries = runCatching { source.list(if (dir.isEmpty()) "" else dir) }.getOrDefault(emptyList())
            val match = entries.firstOrNull { it.equals(want, ignoreCase = true) } ?: return null
            resolved.add(match)
            dir = resolved.joinToString("/")
            if (index == parts.lastIndex) {
                return dir.takeIf { runCatching { source.has(it) }.getOrDefault(false) }
            }
        }
        return null
    }

    companion object {
        private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "bmp", "gif", "webp")

        /**
         * wolfrpg-map-parser Options::anchor — bits 12..15 of params[0]
         * (0 top-left, 1 center, 2 bottom-left, 3 top-right, 4 bottom-right).
         * params[5] is the animation pattern, not the anchor.
         */
        internal fun isCenterOrigin(params: IntArray): Boolean {
            val packed = params.getOrNull(0) ?: 0
            return ((packed ushr 12) and 0xF) == 1
        }

        /** Out-of-range opacity (e.g. -1000000 sentinels) means "use default". */
        internal fun normalizeOpacity(raw: Int?, fallback: Int): Int {
            val value = raw ?: return fallback.coerceIn(0, 255)
            return if (value in 0..255) value else fallback.coerceIn(0, 255)
        }

        internal fun isSquarePrimitive(fileName: String): Boolean =
            fileName.equals("<SQUARE>", ignoreCase = true) ||
                fileName.equals("SQUARE", ignoreCase = true)
    }
}
