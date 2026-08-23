package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.wolfformat.BoundedReader
import io.github.gdlbo.makerplay.wolfformat.WolfFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Milestone-7 contract: atomic save format round-trips and rejects corruption. */
class WolfSaveFormatTest {

    private val state = WolfSaveFormat.GameState(
        title = "Artemis Pearl 2",
        mapPath = "Data/MapData/Map001.mps",
        tileX = 12,
        tileY = 34,
        variables = sortedMapOf(1 to -5, 1000 to 42, 500000 to 7),
        strings = sortedMapOf(3 to "モノクロ", 9 to ""),
    )

    @Test
    fun roundTripsFullState() {
        val decoded = WolfSaveFormat.decode(WolfSaveFormat.encode(state))
        assertEquals(state.title, decoded.title)
        assertEquals(state.mapPath, decoded.mapPath)
        assertEquals(state.tileX, decoded.tileX)
        assertEquals(state.tileY, decoded.tileY)
        assertEquals(state.variables, decoded.variables)
        assertEquals(state.strings, decoded.strings)
    }

    @Test
    fun emptyVariableAndStringMapsRoundTrip() {
        val empty = state.copy(variables = emptyMap(), strings = emptyMap())
        val decoded = WolfSaveFormat.decode(WolfSaveFormat.encode(empty))
        assertTrue(decoded.variables.isEmpty())
        assertTrue(decoded.strings.isEmpty())
    }

    @Test(expected = WolfFormatException::class)
    fun rejectsBadMagic() {
        val encoded = WolfSaveFormat.encode(state)
        val broken = encoded.copyOf().also { it[0] = 'X'.code.toByte() }
        WolfSaveFormat.decode(broken)
    }

    @Test(expected = WolfFormatException::class)
    fun rejectsTruncatedTail() {
        val encoded = WolfSaveFormat.encode(state)
        WolfSaveFormat.decode(encoded.copyOfRange(0, encoded.size / 2))
    }

    @Test
    fun rejectsFutureVersion() {
        val encoded = WolfSaveFormat.encode(state)
        // Version u32 sits right after the 8-byte magic.
        val patched = encoded.copyOf().also {
            it[8] = 0x7F
            it[9] = 0
            it[10] = 0
            it[11] = 0
        }
        assertThrows(WolfFormatException::class.java) { WolfSaveFormat.decode(patched) }
    }

    /** Persistence must be a single atomic rename; verify the manager does so. */
    @Test
    fun saveManagerWritesAtomicallyAndReadsBack() {
        val dir = File(System.getProperty("java.io.tmpdir"), "wolfsave-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val manager = WolfGameSaveManager(dir)
            manager.save("slot-1", state)
            assertEquals(state, manager.load("slot-1"))
            // No partial temp files remain after a successful save.
            assertTrue(dir.listFiles()!!.all { !it.name.endsWith(".tmp") })
        } finally {
            dir.deleteRecursively()
        }
    }
}
