package io.github.gdlbo.makerplay.runtime.wolf

/**
 * Chooses which save slot Continue should resume.
 *
 * This logic lived inline inside `WolfRuntimeBackend`'s host, where it could only
 * be exercised by launching the app and clicking through the title menu. Extracting
 * it makes the read path testable, which is what the task-14 contract asks for
 * ("a test on the read path").
 *
 * The rules it encodes, all learned from device behaviour:
 *
 * * A snapshot whose `mapPath` looks like a title map is **not** resumable. The
 *   title screen itself saves to a slot in some flows, and resuming into the title
 *   would look like Continue did nothing.
 * * Slots are probed in ascending order, so slot 1 wins when several exist.
 * * A corrupt or unreadable slot is skipped rather than aborting the probe: one bad
 *   file must not make every other save unreachable.
 */
object WolfSaveSelection {

    /** Slots probed by Continue, matching the backend's `MAX_PROBE_SLOTS`. */
    const val MAX_PROBE_SLOTS = 20

    /** True when a snapshot records real gameplay rather than the title screen. */
    fun isResumable(state: WolfSaveFormat.GameState): Boolean =
        state.mapPath.isNotBlank() &&
            !state.mapPath.contains("title", ignoreCase = true)

    /**
     * Picks the first resumable slot, or null when none exists.
     *
     * `load` is injected so callers can supply the real manager in production and a
     * fake in tests, and so a throwing loader can be exercised.
     */
    fun findResumableSlot(load: (String) -> WolfSaveFormat.GameState): Int? {
        for (slot in 1..MAX_PROBE_SLOTS) {
            val state = runCatching { load(slotName(slot)) }.getOrNull() ?: continue
            if (isResumable(state)) return slot
        }
        return null
    }

    /** True when any slot can be resumed; the title's Continue probe uses this. */
    fun hasResumableSave(load: (String) -> WolfSaveFormat.GameState): Boolean =
        findResumableSlot(load) != null

    /** Slot file stem for a numeric slot, matching `WolfRuntimeBackend`. */
    fun slotName(slot: Int): String = if (slot == 0) "slot-0" else "slot-$slot"
}
