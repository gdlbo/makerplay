package io.github.gdlbo.makerplay.input

/** A currently active pointer owned by one input source. */
data class PointerContact(
    val sourceId: String,
    val pointerId: Long,
    val x: Float,
    val y: Float,
)

/** Immutable input state consumed by one bounded runtime frame update. */
data class LogicalInputSnapshot(
    val pressedActions: Set<GameAction>,
    val pointers: Set<PointerContact>,
    val pressedKeyCodes: Set<Int> = emptySet(),
)

/**
 * Merges physical and virtual-controller input without allowing one source to
 * release another source's keys. The owner must call [clearSource] on focus or
 * lifecycle loss.
 */
class InputStateReducer {
    private val digital = linkedMapOf<String, MutableSet<GameAction>>()
    private val keyCodes = linkedMapOf<String, MutableSet<Int>>()
    private val pointers = linkedMapOf<String, MutableMap<Long, PointerContact>>()

    fun press(sourceId: String, action: GameAction) {
        requireSource(sourceId)
        requireDigital(action)
        digital.getOrPut(sourceId, ::linkedSetOf).add(action)
    }

    fun release(sourceId: String, action: GameAction) {
        requireSource(sourceId)
        requireDigital(action)
        digital[sourceId]?.let { actions ->
            actions.remove(action)
            if (actions.isEmpty()) digital.remove(sourceId)
        }
    }

    fun pressKeyCode(sourceId: String, keyCode: Int) {
        requireSource(sourceId)
        require(keyCode in 0..512) { "Key code is invalid" }
        keyCodes.getOrPut(sourceId, ::linkedSetOf).add(keyCode)
    }

    fun releaseKeyCode(sourceId: String, keyCode: Int) {
        requireSource(sourceId)
        keyCodes[sourceId]?.let { keys ->
            keys.remove(keyCode)
            if (keys.isEmpty()) keyCodes.remove(sourceId)
        }
    }

    fun pointerDown(sourceId: String, pointerId: Long, x: Float, y: Float) {
        requireSource(sourceId)
        requirePointer(pointerId, x, y)
        pointers.getOrPut(sourceId, ::linkedMapOf)[pointerId] =
            PointerContact(sourceId, pointerId, x, y)
    }

    fun pointerMove(sourceId: String, pointerId: Long, x: Float, y: Float) {
        requireSource(sourceId)
        requirePointer(pointerId, x, y)
        pointers[sourceId]?.let { contacts ->
            if (pointerId in contacts) contacts[pointerId] =
                PointerContact(sourceId, pointerId, x, y)
        }
    }

    fun pointerUp(sourceId: String, pointerId: Long) {
        requireSource(sourceId)
        require(pointerId >= 0) { "Pointer id must not be negative" }
        pointers[sourceId]?.let { contacts ->
            contacts.remove(pointerId)
            if (contacts.isEmpty()) pointers.remove(sourceId)
        }
    }

    /** Clears all state owned by one physical device or virtual surface. */
    fun clearSource(sourceId: String) {
        requireSource(sourceId)
        digital.remove(sourceId)
        keyCodes.remove(sourceId)
        pointers.remove(sourceId)
    }

    /** Clears all input, for example when the player loses window focus. */
    fun clearAll() {
        digital.clear()
        keyCodes.clear()
        pointers.clear()
    }

    fun snapshot(): LogicalInputSnapshot = LogicalInputSnapshot(
        pressedActions = digital.values.asSequence().flatten().toSet(),
        pointers = pointers.values.asSequence().flatMap { it.values.asSequence() }.toSet(),
        pressedKeyCodes = keyCodes.values.asSequence().flatten().toSet(),
    )

    private fun requireSource(sourceId: String) {
        require(sourceId.isNotBlank()) { "Input source id must not be blank" }
    }

    private fun requireDigital(action: GameAction) {
        require(action !in POINTER_ACTIONS) { "Pointer actions require pointer events" }
    }

    private fun requirePointer(pointerId: Long, x: Float, y: Float) {
        require(pointerId >= 0) { "Pointer id must not be negative" }
        require(x.isFinite() && y.isFinite()) { "Pointer coordinates must be finite" }
    }

    private companion object {
        val POINTER_ACTIONS =
            setOf(GameAction.POINTER_DOWN, GameAction.POINTER_MOVE, GameAction.POINTER_UP)
    }
}