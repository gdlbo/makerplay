package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.input.GameAction
import io.github.gdlbo.makerplay.input.LogicalInputSnapshot
import io.github.gdlbo.makerplay.runtime.webview.internal.input.EMPTY_INPUT
import io.github.gdlbo.makerplay.runtime.webview.internal.input.RuntimeInputMixer
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeInputMixerTest {
    @Test
    fun `physical and virtual actions are unioned without cross source release`() {
        val output = mutableListOf<LogicalInputSnapshot>()
        val mixer = RuntimeInputMixer(output::add)

        mixer.setPhysical(snapshot(GameAction.LEFT, GameAction.OK))
        mixer.setVirtual(snapshot(GameAction.LEFT, GameAction.CANCEL))
        mixer.setPhysical(EMPTY_INPUT)

        assertEquals(
            setOf(GameAction.LEFT, GameAction.OK, GameAction.CANCEL),
            output[1].pressedActions
        )
        assertEquals(setOf(GameAction.LEFT, GameAction.CANCEL), output.last().pressedActions)
    }

    @Test
    fun `disabled input clears state and rejects stale virtual press until neutral`() {
        val output = mutableListOf<LogicalInputSnapshot>()
        val mixer = RuntimeInputMixer(output::add)

        mixer.setVirtual(snapshot(GameAction.OK))
        mixer.setUiEnabled(false)
        mixer.setUiEnabled(true)
        mixer.setVirtual(snapshot(GameAction.OK))
        assertEquals(emptySet<GameAction>(), output.last().pressedActions)

        mixer.setVirtual(EMPTY_INPUT)
        mixer.setVirtual(snapshot(GameAction.OK))
        assertEquals(setOf(GameAction.OK), output.last().pressedActions)
    }

    @Test
    fun `platform pause clears state independently from ui enablement`() {
        val output = mutableListOf<LogicalInputSnapshot>()
        val mixer = RuntimeInputMixer(output::add)

        mixer.setPhysical(snapshot(GameAction.RIGHT))
        mixer.setPlatformActive(false)
        mixer.setPlatformActive(true)
        mixer.setPhysical(snapshot(GameAction.DOWN))

        assertEquals(emptySet<GameAction>(), output[1].pressedActions)
        assertEquals(setOf(GameAction.DOWN), output.last().pressedActions)
    }

    @Test
    fun `physical and virtual key codes are merged`() {
        val output = mutableListOf<LogicalInputSnapshot>()
        val mixer = RuntimeInputMixer(output::add)

        mixer.setPhysical(LogicalInputSnapshot(emptySet(), emptySet(), setOf(29)))
        mixer.setVirtual(LogicalInputSnapshot(emptySet(), emptySet(), setOf(54)))

        assertEquals(setOf(29, 54), output.last().pressedKeyCodes)
    }

    private fun snapshot(vararg actions: GameAction) =
        LogicalInputSnapshot(actions.toSet(), emptySet())
}
