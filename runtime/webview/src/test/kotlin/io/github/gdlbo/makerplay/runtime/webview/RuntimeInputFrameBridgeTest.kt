package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.input.GameAction
import io.github.gdlbo.makerplay.input.LogicalInputSnapshot
import io.github.gdlbo.makerplay.input.PointerContact
import io.github.gdlbo.makerplay.runtime.webview.internal.input.RuntimeInputFrameBatcher
import io.github.gdlbo.makerplay.runtime.webview.internal.input.RuntimeInputFrameBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeInputFrameBridgeTest {
    @Test
    fun `document bridge overlays input after the engine gamepad poll`() {
        val source = RuntimeInputFrameBridge.source(runtimeAsset("bridges/input-bridge.js"))

        assertTrue(source.contains("const result = pollGamepads.call(this)"))
        assertTrue(source.indexOf("pollGamepads.call(this)") < source.indexOf("apply(state.current)"))
        assertFalse(source.contains("requestAnimationFrame"))
    }

    @Test
    fun `document bridge maps overlay pointers through the canvas client rect`() {
        val source = RuntimeInputFrameBridge.source(runtimeAsset("bridges/input-bridge.js"))

        assertTrue(source.contains("toCanvasPoint"))
        assertTrue(source.contains("getBoundingClientRect()"))
        assertTrue(source.contains("normalized 0..1 fractions"))
        assertTrue(source.contains("clientWidth"))
        assertTrue(source.contains("canvas.width / rect.width"))
    }

    @Test
    fun `document bridge clears state when the page loses visibility or focus`() {
        val source = RuntimeInputFrameBridge.source(runtimeAsset("bridges/input-bridge.js"))

        assertTrue(source.contains("addEventListener(\"blur\", clear)"))
        assertTrue(source.contains("addEventListener(\"pagehide\", clear)"))
        assertTrue(source.contains("visibilitychange"))
        assertTrue(source.contains("document.hidden"))
    }

    @Test
    fun `document bridge defers input until game variables are initialized`() {
        val source = RuntimeInputFrameBridge.source(runtimeAsset("bridges/input-bridge.js"))

        assertTrue(source.contains("state.current = payload"))
        assertTrue(source.contains("${'$'}gameVariables"))
        assertTrue(source.contains("typeof globalThis.${'$'}gameVariables.setValue !== \"function\""))
    }

    @Test
    fun `input submitted before document bridge is installed is queued`() {
        val source = RuntimeInputFrameBridge.source(runtimeAsset("bridges/input-bridge.js"))
        val script = RuntimeInputFrameBridge.script(snapshot(GameAction.OK))

        assertTrue(script.contains("typeof apply === \"function\""))
        assertTrue(script.contains("pending.push(payload)"))
        assertTrue(script.contains("__makerplayPendingInputSnapshots = [payload]"))
        assertTrue(source.contains("const pending = globalThis.__makerplayPendingInputSnapshots"))
        assertTrue(source.contains("for (const payload of pending) apply(payload)"))
    }

    @Test
    fun `multiple changes in one frame dispatch only the latest snapshot`() {
        val frames = mutableListOf<() -> Unit>()
        val scripts = mutableListOf<String>()
        val batcher = RuntimeInputFrameBatcher(frames::add, scripts::add)

        batcher.submit(snapshot(GameAction.LEFT))
        batcher.submit(snapshot(GameAction.RIGHT, GameAction.OK))

        assertEquals(1, frames.size)
        frames.removeFirst().invoke()
        assertEquals(1, scripts.size)
        assertFalse(scripts.single().contains("left"))
        assertTrue(scripts.single().contains("\"right\",\"ok\""))
    }

    @Test
    fun `unchanged state is not dispatched on another frame`() {
        val frames = mutableListOf<() -> Unit>()
        val scripts = mutableListOf<String>()
        val batcher = RuntimeInputFrameBatcher(frames::add, scripts::add)
        val snapshot = snapshot(GameAction.DOWN)

        batcher.submit(snapshot)
        frames.removeFirst().invoke()
        batcher.submit(snapshot)

        assertTrue(frames.isEmpty())
        assertEquals(1, scripts.size)
    }

    @Test
    fun `close prevents a queued update from reaching a released WebView`() {
        val frames = mutableListOf<() -> Unit>()
        val scripts = mutableListOf<String>()
        val batcher = RuntimeInputFrameBatcher(frames::add, scripts::add)

        batcher.submit(snapshot(GameAction.CANCEL))
        batcher.close()
        frames.single().invoke()

        assertTrue(scripts.isEmpty())
    }

    @Test
    fun `payload is deterministic and keeps pointer sources distinct`() {
        val snapshot = LogicalInputSnapshot(
            pressedActions = setOf(GameAction.PAGE_DOWN, GameAction.UP),
            pointers = setOf(
                PointerContact("touch-b", 1, 30f, 40f),
                PointerContact("touch-a", 1, 10f, 20f),
            ),
        )

        val script = RuntimeInputFrameBridge.script(snapshot)

        assertTrue(script.contains("\"actions\":[\"up\",\"pagedown\"]"))
        assertTrue(script.contains("\"id\":\"touch-a:1\""))
        assertTrue(script.contains("\"id\":\"touch-b:1\""))
        assertTrue(script.length <= 4096)
    }

    @Test
    fun `android keyboard codes are serialized as web key descriptors`() {
        val script = RuntimeInputFrameBridge.script(
            LogicalInputSnapshot(emptySet(), emptySet(), setOf(54, 66)),
        )

        assertTrue(script.contains("\"d\":90,\"k\":\"z\",\"c\":\"KeyZ\""))
        assertTrue(script.contains("\"d\":13,\"k\":\"Enter\",\"c\":\"Enter\""))
    }

    @Test
    fun `short virtual key press keeps both frame edges`() {
        val frames = mutableListOf<() -> Unit>()
        val scripts = mutableListOf<String>()
        val batcher = RuntimeInputFrameBatcher(frames::add, scripts::add)

        batcher.submit(LogicalInputSnapshot(emptySet(), emptySet(), setOf(54)))
        batcher.submit(LogicalInputSnapshot(emptySet(), emptySet(), emptySet()))
        frames.removeFirst().invoke()
        frames.removeFirst().invoke()

        assertEquals(2, scripts.size)
        assertTrue(scripts.first().contains("\"KeyZ\""))
        assertTrue(scripts.last().contains("\"keys\":[]"))
    }

    @Test
    fun `short virtual touch keeps both frame edges`() {
        val frames = mutableListOf<() -> Unit>()
        val scripts = mutableListOf<String>()
        val batcher = RuntimeInputFrameBatcher(frames::add, scripts::add)
        val pointer = PointerContact("touch", 1, 30f, 40f)

        batcher.submit(LogicalInputSnapshot(emptySet(), setOf(pointer)))
        batcher.submit(LogicalInputSnapshot(emptySet(), emptySet()))
        frames.removeFirst().invoke()
        frames.removeFirst().invoke()

        assertEquals(2, scripts.size)
        assertTrue(scripts.first().contains("\"pointers\":[{\"id\":\"touch:1\""))
        assertTrue(scripts.last().contains("\"pointers\":[]"))
    }

    @Test
    fun `oversized pointer snapshot is rejected before JavaScript dispatch`() {
        val pointers = (0L..16L).mapTo(linkedSetOf()) { PointerContact("touch", it, 0f, 0f) }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeInputFrameBridge.script(LogicalInputSnapshot(emptySet(), pointers))
        }
    }

    @Test
    fun `document bridge maps actions to default dom key events when explicit keys are absent`() {
        val source = RuntimeInputFrameBridge.source(runtimeAsset("bridges/input-bridge.js"))

        assertTrue(source.contains("ACTION_DEFAULT_KEYS"))
        assertTrue(source.contains("ArrowUp"))
        assertTrue(source.contains("ArrowDown"))
        assertTrue(source.contains("ArrowLeft"))
        assertTrue(source.contains("ArrowRight"))
    }

    @Test
    fun `document bridge dispatches dom mouse and pointer events for overlay pointers`() {
        val source = RuntimeInputFrameBridge.source(runtimeAsset("bridges/input-bridge.js"))

        assertTrue(source.contains("elementFromPoint"))
        assertTrue(source.contains("pointerdown"))
        assertTrue(source.contains("mousedown"))
        assertTrue(source.contains("mouseup"))
        assertTrue(source.contains("click"))
    }

    private fun snapshot(vararg actions: GameAction) =
        LogicalInputSnapshot(actions.toSet(), emptySet())
}
