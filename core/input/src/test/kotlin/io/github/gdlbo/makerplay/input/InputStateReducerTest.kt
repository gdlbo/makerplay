package io.github.gdlbo.makerplay.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InputStateReducerTest {
    @Test
    fun `sources can hold same action independently and clear without stuck keys`() {
        val reducer = InputStateReducer()
        reducer.press("physical", GameAction.LEFT)
        reducer.press("virtual", GameAction.LEFT)
        reducer.release("physical", GameAction.LEFT)
        assertEquals(setOf(GameAction.LEFT), reducer.snapshot().pressedActions)

        reducer.clearSource("virtual")
        assertEquals(emptySet<GameAction>(), reducer.snapshot().pressedActions)
    }

    @Test
    fun `pointer ids are source scoped and focus clear removes all contacts`() {
        val reducer = InputStateReducer()
        reducer.pointerDown("touch", 1L, 0.2f, 0.3f)
        reducer.pointerDown("touch", 2L, 0.8f, 0.9f)
        reducer.pointerMove("touch", 1L, 0.4f, 0.5f)
        assertEquals(
            setOf(
                PointerContact("touch", 1L, 0.4f, 0.5f),
                PointerContact("touch", 2L, 0.8f, 0.9f),
            ),
            reducer.snapshot().pointers,
        )
        reducer.clearAll()
        assertEquals(emptySet<PointerContact>(), reducer.snapshot().pointers)
    }

    @Test
    fun `pointer actions cannot become stuck digital state`() {
        assertThrows(IllegalArgumentException::class.java) {
            InputStateReducer().press("virtual", GameAction.POINTER_DOWN)
        }
    }

    @Test
    fun `pointer coordinates and ids are validated`() {
        assertThrows(IllegalArgumentException::class.java) {
            InputStateReducer().pointerDown("touch", -1L, 0f, 0f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InputStateReducer().pointerDown("touch", 1L, Float.NaN, 0f)
        }
    }
}
