package io.github.gdlbo.makerplay.feature.player

import io.github.gdlbo.makerplay.feature.player.controller.model.ControllerLayouts
import io.github.gdlbo.makerplay.feature.player.controller.model.ControllerMode
import io.github.gdlbo.makerplay.feature.player.controller.model.DefaultGamepadProfile
import io.github.gdlbo.makerplay.feature.player.controller.model.DefaultKeyboardProfile
import io.github.gdlbo.makerplay.input.GameAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ControllerLayoutsTest {
    @Test
    fun `reset restores only the active layout`() {
        val changedGamepad = DefaultGamepadProfile.copy(
            controls = DefaultGamepadProfile.controls.map {
                if (it.id == "enter") it.copy(action = GameAction.MENU) else it
            },
        )
        val changedKeyboard = DefaultKeyboardProfile.copy(
            controls = DefaultKeyboardProfile.controls.dropLast(1),
        )
        val layouts = ControllerLayouts(
            mode = ControllerMode.GAMEPAD,
            gamepad = changedGamepad,
            keyboard = changedKeyboard,
        )

        val resetGamepad = layouts.resetActive()

        assertEquals(ControllerMode.GAMEPAD, resetGamepad.mode)
        assertEquals(DefaultGamepadProfile, resetGamepad.gamepad)
        assertEquals(changedKeyboard, resetGamepad.keyboard)
        assertNotEquals(DefaultKeyboardProfile, resetGamepad.keyboard)

        val resetKeyboard = layouts.copy(mode = ControllerMode.KEYBOARD).resetActive()

        assertEquals(ControllerMode.KEYBOARD, resetKeyboard.mode)
        assertEquals(changedGamepad, resetKeyboard.gamepad)
        assertEquals(DefaultKeyboardProfile, resetKeyboard.keyboard)
        assertNotEquals(DefaultGamepadProfile, resetKeyboard.gamepad)
    }
}
