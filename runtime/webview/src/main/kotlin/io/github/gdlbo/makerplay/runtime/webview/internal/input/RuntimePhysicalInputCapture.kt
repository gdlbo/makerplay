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

    fun onAxes(sourceId: String, x: Float, y: Float): Boolean {
        val previous = normalizer.snapshot()
        val handledX = normalizer.onAxis(sourceId, AXIS_X, x)
        val handledY = normalizer.onAxis(sourceId, AXIS_Y, y)
        if (handledX || handledY) publishIfChanged(previous)
        return handledX || handledY
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
    }
}