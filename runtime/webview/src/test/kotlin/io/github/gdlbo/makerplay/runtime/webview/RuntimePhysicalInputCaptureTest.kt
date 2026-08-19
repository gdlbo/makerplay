package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.input.GameAction
import io.github.gdlbo.makerplay.input.LogicalInputSnapshot
import io.github.gdlbo.makerplay.input.PhysicalKeyAction
import io.github.gdlbo.makerplay.runtime.webview.internal.input.RuntimePhysicalInputCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePhysicalInputCaptureTest {
    @Test
    fun `supported events publish merged snapshots and clear releases them`() {
        val snapshots = mutableListOf<LogicalInputSnapshot>()
        val capture = RuntimePhysicalInputCapture(onChanged = snapshots::add)

        assertTrue(capture.onKey("device:1", 21, PhysicalKeyAction.DOWN, repeatCount = 0))
        assertTrue(capture.onAxes("device:2", x = 0f, y = 0.8f))
        assertEquals(setOf(GameAction.LEFT, GameAction.DOWN), snapshots.last().pressedActions)

        capture.clearAll()

        assertEquals(emptySet<GameAction>(), snapshots.last().pressedActions)
    }

    @Test
    fun `unknown key is observed without publishing a state change`() {
        val snapshots = mutableListOf<LogicalInputSnapshot>()
        val capture = RuntimePhysicalInputCapture(onChanged = snapshots::add)

        assertFalse(capture.onKey("device:1", 999, PhysicalKeyAction.DOWN, repeatCount = 0))

        assertTrue(snapshots.isEmpty())
    }

    @Test
    fun `repeat and unchanged axis samples do not republish logical state`() {
        val snapshots = mutableListOf<LogicalInputSnapshot>()
        val capture = RuntimePhysicalInputCapture(onChanged = snapshots::add)

        capture.onKey("device:1", 21, PhysicalKeyAction.DOWN, repeatCount = 0)
        capture.onKey("device:1", 21, PhysicalKeyAction.DOWN, repeatCount = 1)
        capture.onAxes("device:2", x = 0.8f, y = 0f)
        capture.onAxes("device:2", x = 0.8f, y = 0f)

        assertEquals(2, snapshots.size)
    }
}
