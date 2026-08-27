package io.github.gdlbo.makerplay.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalInputNormalizerTest {
    @Test
    fun `keyboard and gamepad aliases map to independent logical actions`() {
        val input = PhysicalInputNormalizer()
        assertTrue(input.onKey(PhysicalKeyEvent("keyboard", 21, PhysicalKeyAction.DOWN)))
        assertTrue(input.onKey(PhysicalKeyEvent("pad", 96, PhysicalKeyAction.DOWN)))
        assertEquals(setOf(GameAction.LEFT, GameAction.OK), input.snapshot().pressedActions)
        assertEquals(setOf(21, 96), input.snapshot().pressedKeyCodes)
        input.clearSource("keyboard")
        assertEquals(setOf(GameAction.OK), input.snapshot().pressedActions)
        assertEquals(setOf(96), input.snapshot().pressedKeyCodes)
    }

    @Test
    fun `axis hysteresis prevents jitter and changes direction atomically`() {
        val input = PhysicalInputNormalizer()
        input.onAxis("pad", 0, -0.8f)
        input.onAxis("pad", 0, -0.4f)
        assertEquals(setOf(GameAction.LEFT), input.snapshot().pressedActions)
        input.onAxis("pad", 0, 0.8f)
        assertEquals(setOf(GameAction.RIGHT), input.snapshot().pressedActions)
        input.onAxis("pad", 0, 0.1f)
        assertEquals(emptySet<GameAction>(), input.snapshot().pressedActions)
    }

    @Test
    fun `unknown keys and axes are ignored while invalid samples fail`() {
        val input = PhysicalInputNormalizer()
        assertFalse(input.onKey(PhysicalKeyEvent("pad", 999, PhysicalKeyAction.DOWN)))
        assertFalse(input.onAxis("pad", 2, 0.8f))
        assertThrows(IllegalArgumentException::class.java) {
            input.onAxis("pad", 0, Float.NaN)
        }
    }

    @Test
    fun `clear source releases axes and digital keys`() {
        val input = PhysicalInputNormalizer()
        input.onKey(PhysicalKeyEvent("pad", 22, PhysicalKeyAction.DOWN))
        input.onAxis("pad", 1, 0.8f)
        input.clearSource("pad")
        assertEquals(emptySet<GameAction>(), input.snapshot().pressedActions)
    }

    @Test
    fun `axis release does not release same direction held by a dpad key`() {
        val input = PhysicalInputNormalizer()
        input.onKey(PhysicalKeyEvent("pad", 21, PhysicalKeyAction.DOWN))
        input.onAxis("pad", 0, -0.8f)
        input.onAxis("pad", 0, 0f)
        assertEquals(setOf(GameAction.LEFT), input.snapshot().pressedActions)
        input.onKey(PhysicalKeyEvent("pad", 21, PhysicalKeyAction.UP))
        assertEquals(emptySet<GameAction>(), input.snapshot().pressedActions)
    }
}
