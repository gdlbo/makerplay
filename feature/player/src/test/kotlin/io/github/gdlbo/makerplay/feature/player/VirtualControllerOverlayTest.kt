package io.github.gdlbo.makerplay.feature.player

import io.github.gdlbo.makerplay.feature.player.controller.model.DefaultGamepadProfile
import io.github.gdlbo.makerplay.feature.player.controller.ui.dPadActionsForPosition
import io.github.gdlbo.makerplay.feature.player.controller.ui.moveVirtualControl
import io.github.gdlbo.makerplay.input.GameAction
import io.github.gdlbo.makerplay.input.VirtualControlType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualControllerOverlayTest {
    @Test
    fun `default profile exposes directional and action controls`() {
        assertEquals(
            setOf("dpad", "enter", "escape", "key-z", "key-b"),
            DefaultGamepadProfile.controls.map { it.id }.toSet(),
        )
        assertEquals(VirtualControlType.D_PAD, DefaultGamepadProfile.controls.single { it.id == "dpad" }.type)
        assertEquals(setOf(66, 111, 54, 30), DefaultGamepadProfile.controls.mapNotNull { it.keyCode }.toSet())
    }

    @Test
    fun `dpad resolves cardinal diagonal and center positions`() {
        assertEquals(setOf(GameAction.UP), dPadActionsForPosition(.5f, 0f))
        assertEquals(setOf(GameAction.UP, GameAction.RIGHT), dPadActionsForPosition(.8f, .2f))
        assertEquals(setOf(GameAction.RIGHT), dPadActionsForPosition(1f, .5f))
        assertEquals(setOf(GameAction.DOWN, GameAction.RIGHT), dPadActionsForPosition(.8f, .8f))
        assertEquals(setOf(GameAction.DOWN), dPadActionsForPosition(.5f, 1f))
        assertEquals(setOf(GameAction.DOWN, GameAction.LEFT), dPadActionsForPosition(.2f, .8f))
        assertEquals(setOf(GameAction.LEFT), dPadActionsForPosition(0f, .5f))
        assertEquals(setOf(GameAction.UP, GameAction.LEFT), dPadActionsForPosition(.2f, .2f))
        assertEquals(emptySet<GameAction>(), dPadActionsForPosition(.5f, .5f))
        assertEquals(emptySet<GameAction>(), dPadActionsForPosition(0f, 0f))
    }

    @Test
    fun `dragging remains cumulative and bounded inside the canvas`() {
        val first = moveVirtualControl(DefaultGamepadProfile, "enter", -.1f, .1f)
        val second = moveVirtualControl(first, "enter", -.1f, .5f)
        val moved = second.controls.single { it.id == "enter" }

        assertEquals(.62f, moved.x, .0001f)
        assertEquals(1f - moved.height, moved.y, .0001f)
        assertTrue(second.controls.all { it.x >= 0f && it.y >= 0f && it.x + it.width <= 1f && it.y + it.height <= 1f })
    }
}
