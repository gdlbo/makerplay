package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.wolfformat.EventCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Picture slot semantics for opcode 150 overlays. */
class WolfPictureStateTest {

    private fun cmd(type0: Int, slot: Int, file: String? = null, x: Int = 0, y: Int = 0) = EventCommand(
        paramCount = 4,
        commandType = 150,
        params = intArrayOf(type0, slot, x, y),
        branchDepth = 0,
        strings = if (file == null) emptyList() else listOf(file),
        route = null,
    )

    @Test
    fun showStoresSlotAndCountsRevision() {
        val state = WolfPictureState()
        val before = state.version()
        // type nibble 0 = file picture; slot lives in args[1].
        assertTrue(state.apply(cmd(0x0F, 3, "window.png", x = 10, y = 20)))
        assertEquals(listOf(WolfPictureState.Picture(3, "window.png", 10, 20)), state.all())
        assertTrue(state.version() > before)
    }

    @Test
    fun showReplacesSameSlot() {
        val state = WolfPictureState()
        state.apply(cmd(0x0F, 3, "a.png"))
        state.apply(cmd(0x0F, 3, "b.png", x = 5, y = 6))
        assertEquals(listOf(WolfPictureState.Picture(3, "b.png", 5, 6)), state.all())
    }

    @Test
    fun eraseWithoutFileNameRemovesSlot() {
        val state = WolfPictureState()
        state.apply(cmd(0x0F, 3, "a.png"))
        assertTrue(state.apply(cmd(0x02, 3)))
        assertTrue(state.all().isEmpty())
        assertFalse(state.apply(cmd(0x02, 3))) // erasing empty slot is a no-op
    }

    @Test
    fun clearResetsEverything() {
        val state = WolfPictureState()
        state.apply(cmd(0x0F, 1, "a.png"))
        state.apply(cmd(0x0F, 2, "b.png"))
        state.clear()
        assertTrue(state.all().isEmpty())
    }

    @Test
    fun textPicturesDoNotEnterImageLayer() {
        val state = WolfPictureState()
        // type nibble 2 = text picture.
        assertTrue(state.apply(cmd(0x2F, 5, "Hello")))
        assertTrue(state.all().isEmpty())
    }

    @Test
    fun resolvePathSkipsUnresolvedEscapes() {
        val state = WolfPictureState()
        assertNull(state.resolvePath(DummySource, "\\f[\\cself[18]]\\space[0]"))
    }

    private object DummySource : io.github.gdlbo.makerplay.wolfformat.GameDataSource {
        override fun read(path: String): ByteArray = throw java.io.FileNotFoundException(path)
        override fun list(path: String) = emptyList<String>()
        override fun close() = Unit
    }
}
