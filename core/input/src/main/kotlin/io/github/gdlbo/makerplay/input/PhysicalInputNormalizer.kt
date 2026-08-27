package io.github.gdlbo.makerplay.input

/** Android key action values accepted by [PhysicalKeyEvent]. */
enum class PhysicalKeyAction { DOWN, UP }

/** Platform event DTO. [sourceId] must identify one physical device. */
data class PhysicalKeyEvent(
    val sourceId: String,
    val keyCode: Int,
    val action: PhysicalKeyAction,
    val repeatCount: Int = 0,
)

/**
 * Maps Android keyboard/gamepad key codes and axes to the logical reducer.
 * Android is intentionally not a dependency of core:input; callers pass
 * KeyEvent.keyCode/action and MotionEvent axis values at the platform edge.
 */
class PhysicalInputNormalizer(
    private val reducer: InputStateReducer = InputStateReducer(),
    private val pressThreshold: Float = DEFAULT_PRESS_THRESHOLD,
    private val releaseThreshold: Float = DEFAULT_RELEASE_THRESHOLD,
) {
    private val axisStates = linkedMapOf<String, MutableMap<Int, AxisDirection>>()
    private val pressedKeys = linkedMapOf<String, MutableSet<Int>>()

    init {
        require(pressThreshold in 0f..1f) { "Press threshold must be between 0 and 1" }
        require(releaseThreshold in 0f..pressThreshold) {
            "Release threshold must be between 0 and press threshold"
        }
    }

    fun onKey(event: PhysicalKeyEvent): Boolean {
        require(event.sourceId.isNotBlank()) { "Input source id must not be blank" }
        require(event.repeatCount >= 0) { "Repeat count must not be negative" }
        val action = keyMap[event.keyCode] ?: return false
        val reducerSource = keySource(event.sourceId, event.keyCode)
        when (event.action) {
            PhysicalKeyAction.DOWN -> {
                pressedKeys.getOrPut(event.sourceId, ::linkedSetOf).add(event.keyCode)
                reducer.press(reducerSource, action)
                reducer.pressKeyCode(reducerSource, event.keyCode)
            }

            PhysicalKeyAction.UP -> {
                reducer.release(reducerSource, action)
                reducer.releaseKeyCode(reducerSource, event.keyCode)
                pressedKeys[event.sourceId]?.let { keys ->
                    keys.remove(event.keyCode)
                    if (keys.isEmpty()) pressedKeys.remove(event.sourceId)
                }
            }
        }
        return true
    }

    /** Applies a centered gamepad stick or hat axis sample. */
    fun onAxis(sourceId: String, axis: Int, value: Float): Boolean {
        require(sourceId.isNotBlank()) { "Input source id must not be blank" }
        require(value.isFinite()) { "Axis value must be finite" }
        val actions = when (axis) {
            AXIS_X, AXIS_HAT_X -> GameAction.LEFT to GameAction.RIGHT
            AXIS_Y, AXIS_HAT_Y -> GameAction.UP to GameAction.DOWN
            else -> return false
        }
        val directions = axisStates.getOrPut(sourceId, ::linkedMapOf)
        val previous = directions[axis] ?: AxisDirection.CENTER
        val next = direction(value, previous)
        if (next != previous) {
            val reducerSource = axisSource(sourceId, axis)
            transition(reducerSource, previous, next, actions.first, actions.second)
            directions[axis] = next
        }
        return true
    }

    fun clearSource(sourceId: String) {
        require(sourceId.isNotBlank()) { "Input source id must not be blank" }
        pressedKeys.remove(sourceId)?.forEach { reducer.clearSource(keySource(sourceId, it)) }
        axisStates.remove(sourceId)?.keys?.forEach { reducer.clearSource(axisSource(sourceId, it)) }
    }

    fun clearAll() {
        reducer.clearAll()
        axisStates.clear()
        pressedKeys.clear()
    }

    fun snapshot(): LogicalInputSnapshot = reducer.snapshot()

    private fun transition(
        sourceId: String,
        previous: AxisDirection,
        next: AxisDirection,
        negative: GameAction,
        positive: GameAction,
    ) {
        val negCode = when (negative) {
            GameAction.UP -> 19
            GameAction.LEFT -> 21
            else -> null
        }
        val posCode = when (positive) {
            GameAction.DOWN -> 20
            GameAction.RIGHT -> 22
            else -> null
        }
        if (previous == AxisDirection.NEGATIVE) {
            reducer.release(sourceId, negative)
            negCode?.let { reducer.releaseKeyCode(sourceId, it) }
        }
        if (previous == AxisDirection.POSITIVE) {
            reducer.release(sourceId, positive)
            posCode?.let { reducer.releaseKeyCode(sourceId, it) }
        }
        if (next == AxisDirection.NEGATIVE) {
            reducer.press(sourceId, negative)
            negCode?.let { reducer.pressKeyCode(sourceId, it) }
        }
        if (next == AxisDirection.POSITIVE) {
            reducer.press(sourceId, positive)
            posCode?.let { reducer.pressKeyCode(sourceId, it) }
        }
    }

    private fun direction(value: Float, previous: AxisDirection): AxisDirection = when {
        value <= -pressThreshold -> AxisDirection.NEGATIVE
        value >= pressThreshold -> AxisDirection.POSITIVE
        previous == AxisDirection.NEGATIVE && value > -releaseThreshold -> AxisDirection.CENTER
        previous == AxisDirection.POSITIVE && value < releaseThreshold -> AxisDirection.CENTER
        else -> previous
    }

    private enum class AxisDirection { NEGATIVE, CENTER, POSITIVE }

    private fun keySource(sourceId: String, keyCode: Int): String =
        "physical:${sourceId.length}:$sourceId:key:$keyCode"

    private fun axisSource(sourceId: String, axis: Int): String =
        "physical:${sourceId.length}:$sourceId:axis:$axis"

    companion object {
        const val AXIS_X = 0
        const val AXIS_Y = 1
        const val AXIS_HAT_X = 15
        const val AXIS_HAT_Y = 16
        const val DEFAULT_PRESS_THRESHOLD = 0.5f
        const val DEFAULT_RELEASE_THRESHOLD = 0.35f

        // Android KeyEvent constants, kept here to keep core:input platform-neutral.
        val keyMap = mapOf(
            19 to GameAction.UP,
            20 to GameAction.DOWN,
            21 to GameAction.LEFT,
            22 to GameAction.RIGHT,
            4 to GameAction.CANCEL,
            61 to GameAction.TAB,
            66 to GameAction.OK,
            59 to GameAction.SHIFT,
            60 to GameAction.SHIFT,
            82 to GameAction.MENU,
            92 to GameAction.PAGE_UP,
            93 to GameAction.PAGE_DOWN,
            111 to GameAction.ESCAPE,
            113 to GameAction.CONTROL,
            96 to GameAction.OK,
            97 to GameAction.CANCEL,
            99 to GameAction.SHIFT,
            100 to GameAction.MENU,
            102 to GameAction.PAGE_UP,
            103 to GameAction.PAGE_DOWN,
            108 to GameAction.MENU,
            109 to GameAction.TAB,
        )
    }
}