package io.github.gdlbo.makerplay.feature.player

import androidx.compose.ui.unit.IntOffset
import io.github.gdlbo.makerplay.feature.player.controller.model.DefaultGamepadProfile
import io.github.gdlbo.makerplay.feature.player.controller.ui.circularControlSidePx
import io.github.gdlbo.makerplay.feature.player.controller.ui.controlsFitViewport
import android.view.KeyEvent
import io.github.gdlbo.makerplay.feature.player.controller.ui.controlBoundToKey
import io.github.gdlbo.makerplay.feature.player.controller.ui.dPadActionsForPosition
import io.github.gdlbo.makerplay.feature.player.controller.ui.isLiteralKeyCode
import io.github.gdlbo.makerplay.input.VirtualControl
import io.github.gdlbo.makerplay.feature.player.controller.ui.moveVirtualControl
import io.github.gdlbo.makerplay.feature.player.controller.ui.minimumControlPressRemainingMillis
import io.github.gdlbo.makerplay.feature.player.controller.ui.normalizeOverlayPointer
import io.github.gdlbo.makerplay.feature.player.controller.ui.overlayPageOffsetPx
import io.github.gdlbo.makerplay.input.GameAction
import io.github.gdlbo.makerplay.input.VirtualControlShape
import io.github.gdlbo.makerplay.input.VirtualControlType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualControllerOverlayTest {
    @Test
    fun `short taps span runtime frames but held buttons release immediately`() {
        assertEquals(64L, minimumControlPressRemainingMillis(0))
        assertEquals(59L, minimumControlPressRemainingMillis(5))
        assertEquals(0L, minimumControlPressRemainingMillis(64))
        assertEquals(0L, minimumControlPressRemainingMillis(200))
    }

    @Test
    fun `keyboard capture stores letters and treats back as escape`() {
        val button = VirtualControl(
            id = "custom",
            type = VirtualControlType.BUTTON,
            action = GameAction.OK,
            x = 0f,
            y = 0f,
            width = .1f,
            height = .1f,
            opacity = 1f,
        )
        val letter = controlBoundToKey(button, KeyEvent.KEYCODE_A)
        assertEquals(KeyEvent.KEYCODE_A, letter.keyCode)
        assertTrue(isLiteralKeyCode(letter.keyCode))
        val escape = controlBoundToKey(button, KeyEvent.KEYCODE_BACK)
        assertEquals(KeyEvent.KEYCODE_ESCAPE, escape.keyCode)
        assertEquals(GameAction.ESCAPE, escape.action)
        assertEquals(false, isLiteralKeyCode(escape.keyCode))
    }

    @Test
    fun `default profile exposes directional and action controls`() {
        assertEquals(
            setOf("dpad", "enter", "escape", "shift", "menu"),
            DefaultGamepadProfile.controls.map { it.id }.toSet(),
        )
        assertEquals(VirtualControlType.D_PAD, DefaultGamepadProfile.controls.single { it.id == "dpad" }.type)
        assertEquals(setOf(66, 111, 59, 82), DefaultGamepadProfile.controls.mapNotNull { it.keyCode }.toSet())
        assertTrue(DefaultGamepadProfile.controls.filter { it.id != "dpad" }.all { it.shape == VirtualControlShape.CIRCLE })
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
    fun `dpad favors cardinal directions and retains captured drags beyond edge`() {
        assertEquals(setOf(GameAction.RIGHT), dPadActionsForPosition(.9f, .7f))
        assertEquals(emptySet<GameAction>(), dPadActionsForPosition(.59f, .55f))
        assertEquals(setOf(GameAction.LEFT), dPadActionsForPosition(-.3f, .5f, captured = true))
        assertEquals(emptySet<GameAction>(), dPadActionsForPosition(-.3f, .5f))
    }

    @Test
    fun `circular controls square to the shorter axis`() {
        val dpad = DefaultGamepadProfile.controls.single { it.id == "dpad" }
        val button = DefaultGamepadProfile.controls.single { it.id == "enter" }

        assertEquals(364.8f, circularControlSidePx(dpad, canvasWidthPx = 1920f, canvasHeightPx = 1080f), .001f)
        assertEquals(182.4f, circularControlSidePx(button, canvasWidthPx = 1920f, canvasHeightPx = 1080f), .001f)
    }

    @Test
    fun `default controls fit the emulator landscape viewport`() {
        assertTrue(
            controlsFitViewport(
                DefaultGamepadProfile,
                canvasWidthPx = 2400f,
                canvasHeightPx = 1080f,
            ),
        )
    }

    @Test
    fun `overlay pointers normalize to the true surface fraction`() {
        assertEquals(
            0.5f to 0.25f,
            normalizeOverlayPointer(
                localX = 960f,
                localY = 270f,
                overlayWidthPx = 1920,
                overlayHeightPx = 1080,
            ),
        )
        assertEquals(
            0.25f to 0.5f,
            normalizeOverlayPointer(
                localX = 400f,
                localY = 540f,
                overlayWidthPx = 1920,
                overlayHeightPx = 1080,
                pageOffsetXPx = 80f,
                pageOffsetYPx = 0f,
            ),
        )
        assertEquals(
            0f to 1f,
            normalizeOverlayPointer(
                localX = -20f,
                localY = 2000f,
                overlayWidthPx = 800,
                overlayHeightPx = 600,
            ),
        )
    }

    @Test
    fun `overlay page offset keeps touches in the runtime surface space`() {
        // The Popup window hosting the overlay is displaced 40px right and 128px down of the
        // WebView surface (for example by a display cutout inset applied to the sub-panel window).
        val (offsetX, offsetY) = overlayPageOffsetPx(
            overlayPositionOnScreen = IntOffset(x = 40, y = 128),
            runtimeSurfacePositionOnScreen = IntOffset.Zero,
        )

        assertEquals(40f, offsetX, .0001f)
        assertEquals(128f, offsetY, .0001f)

        // A touch at popup-local (200, 400) is physically at surface-relative (240, 528), so the
        // normalized fraction must be the surface fraction, not the popup-local fraction.
        val (x, y) = normalizeOverlayPointer(
            localX = 200f,
            localY = 400f,
            overlayWidthPx = 2400,
            overlayHeightPx = 1080,
            pageOffsetXPx = offsetX,
            pageOffsetYPx = offsetY,
        )

        assertEquals(240f / 2400f, x, .0001f)
        assertEquals(528f / 1080f, y, .0001f)

        // Regression proof: the pre-fix call site supplied no offset, so the same touch
        // normalized to the popup-local fraction and landed 40px left / 128px above the
        // finger in the game. The corrected fraction must differ from that value.
        val (preFixX, preFixY) = normalizeOverlayPointer(
            localX = 200f,
            localY = 400f,
            overlayWidthPx = 2400,
            overlayHeightPx = 1080,
        )
        assertEquals(200f / 2400f, preFixX, .0001f)
        assertEquals(400f / 1080f, preFixY, .0001f)
        assertNotEquals(x, preFixX, .0001f)
        assertNotEquals(y, preFixY, .0001f)
    }

    @Test
    fun `dragging remains cumulative and bounded inside the canvas`() {
        val enter = DefaultGamepadProfile.controls.single { it.id == "enter" }
        val first = moveVirtualControl(DefaultGamepadProfile, "enter", -.1f, .1f)
        val second = moveVirtualControl(first, "enter", -.1f, .5f)
        val moved = second.controls.single { it.id == "enter" }

        assertEquals((enter.x - .2f).coerceAtLeast(0f), moved.x, .0001f)
        assertEquals(1f - moved.height, moved.y, .0001f)
        assertTrue(second.controls.all { it.x >= 0f && it.y >= 0f && it.x + it.width <= 1f && it.y + it.height <= 1f })
    }
}
