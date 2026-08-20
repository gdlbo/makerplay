package io.github.gdlbo.makerplay.runtime.webview.internal.input

import io.github.gdlbo.makerplay.input.LogicalInputSnapshot
import io.github.gdlbo.makerplay.input.PhysicalInputNormalizer
import io.github.gdlbo.makerplay.input.PhysicalKeyAction
import io.github.gdlbo.makerplay.input.PhysicalKeyEvent

internal class RuntimePhysicalInputCapture(
    private val normalizer: PhysicalInputNormalizer = PhysicalInputNormalizer(),
    private var onChanged: (LogicalInputSnapshot) -> Unit,
) {
    fun setOnChanged(listener: (LogicalInputSnapshot) -> Unit) {
        onChanged = listener
    }

    fun onKey(
        sourceId: String,
        keyCode: Int,
        action: PhysicalKeyAction,
        repeatCount: Int,
    ): Boolean {
        val previous = normalizer.snapshot()
        val handled = normalizer.onKey(
            PhysicalKeyEvent(
                sourceId = sourceId,
                keyCode = keyCode,
                action = action,
                repeatCount = repeatCount,
            ),
        )
        if (handled) publishIfChanged(previous)
        return handled
    }

    fun onAxes(
        sourceId: String,
        x: Float,
        y: Float,
        hatX: Float = 0f,
        hatY: Float = 0f,
    ): Boolean {
        val previous = normalizer.snapshot()
        val handledX = normalizer.onAxis(sourceId, AXIS_X, x)
        val handledY = normalizer.onAxis(sourceId, AXIS_Y, y)
        val handledHatX = normalizer.onAxis(sourceId, AXIS_HAT_X, hatX)
        val handledHatY = normalizer.onAxis(sourceId, AXIS_HAT_Y, hatY)
        if (handledX || handledY || handledHatX || handledHatY) publishIfChanged(previous)
        return handledX || handledY || handledHatX || handledHatY
    }

    fun clearAll() {
        val previous = normalizer.snapshot()
        normalizer.clearAll()
        if (previous.pressedActions.isNotEmpty() || previous.pointers.isNotEmpty()) {
            onChanged(normalizer.snapshot())
        }
    }

    private fun publishIfChanged(previous: LogicalInputSnapshot) {
        normalizer.snapshot().takeIf { it != previous }?.let(onChanged)
    }

    private companion object {
        const val AXIS_X = 0
        const val AXIS_Y = 1
        const val AXIS_HAT_X = 15
        const val AXIS_HAT_Y = 16
    }
}