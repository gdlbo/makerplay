package io.github.gdlbo.makerplay.runtime.wolf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The save/Continue read path.
 *
 * The task-14 contract asks for "a test on the read path" because the write side
 * alone proves nothing: a save that is written but never selected, or selected from
 * the wrong slot, produces exactly the bug that was observed on device — a cold
 * start after saving in Map119 resumed into Map114 (25,15) instead of the saved
 * (16,20).
 */
class WolfSaveRoundTripTest {

    private fun state(
        mapPath: String = "Data/MapData/Map119.mps",
        tileX: Int = 16,
        tileY: Int = 20,
        title: String = "Synthetic WOLF Game",
    ) = WolfSaveFormat.GameState(
        title = title,
        mapPath = mapPath,
        tileX = tileX,
        tileY = tileY,
        variables = mapOf(10 to 42, 11 to -3),
        strings = mapOf(5 to "モノクロ"),
    )

    private fun tempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "wolfrt-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    // --- encode / decode round trip ---------------------------------------

    @Test
    fun aSnapshotSurvivesTheByteRoundTrip() {
        val original = state()
        assertEquals(original, WolfSaveFormat.decode(WolfSaveFormat.encode(original)))
    }

    @Test
    fun theSnapshotNamesTheMapAndTileItRecorded() {
        // The contract's first clause: the decoded slot file names that map and
        // position. This reads the bytes back and asserts the exact strings, so a
        // silent field swap (x/y, or map/title) cannot pass.
        val encoded = WolfSaveFormat.encode(state(mapPath = "Data/MapData/Map119.mps", tileX = 16, tileY = 20))
        val decoded = WolfSaveFormat.decode(encoded)
        assertEquals("Data/MapData/Map119.mps", decoded.mapPath)
        assertEquals(16, decoded.tileX)
        assertEquals(20, decoded.tileY)
        assertEquals("Synthetic WOLF Game", decoded.title)
    }

    @Test
    fun variablesAndStringsSurviveWithTheirSignsAndUnicode() {
        val decoded = WolfSaveFormat.decode(WolfSaveFormat.encode(state()))
        assertEquals(42, decoded.variables[10])
        assertEquals("negative variables must not be clamped", -3, decoded.variables[11])
        assertEquals("モノクロ", decoded.strings[5])
    }

    @Test
    fun aTruncatedSaveIsRejectedRatherThanPartiallyApplied() {
        val encoded = WolfSaveFormat.encode(state())
        val truncated = encoded.copyOf(encoded.size - 8)
        val error = runCatching { WolfSaveFormat.decode(truncated) }.exceptionOrNull()
        assertTrue("a truncated save must fail loudly, got $error", error != null)
    }

    // --- persistence round trip ------------------------------------------

    @Test
    fun savingThenLoadingThroughTheManagerReturnsTheSameState() {
        val dir = tempDir()
        try {
            val manager = WolfGameSaveManager(dir)
            assertFalse(manager.has("slot-1"))
            manager.save("slot-1", state())
            assertTrue(manager.has("slot-1"))
            assertEquals(state(), manager.load("slot-1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aSecondSaveOverwritesTheFirstAtomically() {
        val dir = tempDir()
        try {
            val manager = WolfGameSaveManager(dir)
            manager.save("slot-1", state(tileX = 16, tileY = 20))
            manager.save("slot-1", state(tileX = 30, tileY = 41))
            val loaded = manager.load("slot-1")
            assertEquals(30, loaded.tileX)
            assertEquals(41, loaded.tileY)
            // No temp file left behind, so a crash cannot resurrect the old save.
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aSlotNameCannotEscapeTheSavesDirectory() {
        val dir = tempDir()
        try {
            val manager = WolfGameSaveManager(dir)
            // A hostile slot name is sanitised, not honoured.
            manager.save("../../etc/passwd", state())
            val files = dir.listFiles()!!.map { it.name }
            assertTrue("save must land inside the saves dir: $files", files.all { !it.contains("..") })
            assertTrue(files.any { it.endsWith(".mkpsave") })
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- Continue selection ----------------------------------------------

    @Test
    fun continueSelectsTheFirstResumableSlot() {
        val dir = tempDir()
        try {
            val manager = WolfGameSaveManager(dir)
            manager.save("slot-1", state(mapPath = "Data/MapData/Map114.mps", tileX = 25, tileY = 15))
            manager.save("slot-3", state(mapPath = "Data/MapData/Map119.mps", tileX = 16, tileY = 20))
            val slot = WolfSaveSelection.findResumableSlot { manager.load(it) }
            assertEquals(1, slot)
            // And the state it yields is the one that was written.
            val chosen = manager.load(WolfSaveSelection.slotName(slot!!))
            assertEquals("Data/MapData/Map114.mps", chosen.mapPath)
            assertEquals(25, chosen.tileX)
            assertEquals(15, chosen.tileY)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aTitleScreenSnapshotIsNotResumable() {
        // This is the device bug: the title screen itself writes a snapshot, and
        // resuming into it looks like Continue did nothing.
        assertFalse(WolfSaveSelection.isResumable(state(mapPath = "Data/MapData/title.mps")))
        assertFalse(WolfSaveSelection.isResumable(state(mapPath = "Data/Title.mps")))
        assertFalse(WolfSaveSelection.isResumable(state(mapPath = "")))
        assertTrue(WolfSaveSelection.isResumable(state(mapPath = "Data/MapData/Map119.mps")))
    }

    @Test
    fun aTitleSnapshotIsSkippedInFavourOfARealOne() {
        val snapshots = mapOf(
            "slot-1" to state(mapPath = "Data/MapData/title.mps"),
            "slot-2" to state(mapPath = "Data/MapData/Map119.mps", tileX = 16, tileY = 20),
        )
        val slot = WolfSaveSelection.findResumableSlot { snapshots[it] ?: error("missing $it") }
        assertEquals(2, slot)
    }

    @Test
    fun aCorruptSlotIsSkippedRatherThanAbortingTheProbe() {
        // One unreadable file must not make every other save unreachable.
        val snapshots = mapOf("slot-2" to state(mapPath = "Data/MapData/Map090.mps"))
        val slot = WolfSaveSelection.findResumableSlot { name ->
            if (name == "slot-1") throw IllegalStateException("corrupt")
            snapshots[name] ?: error("missing $name")
        }
        assertEquals(2, slot)
    }

    @Test
    fun noResumableSaveYieldsNull() {
        assertNull(WolfSaveSelection.findResumableSlot { error("no slots") })
        assertFalse(WolfSaveSelection.hasResumableSave { error("no slots") })
    }

    @Test
    fun theProbeStopsAtTheDocumentedSlotLimit() {
        // Probing beyond the limit would be wasted work on every cold start.
        var highestProbed = 0
        WolfSaveSelection.findResumableSlot { name ->
            val n = name.removePrefix("slot-").toInt()
            highestProbed = maxOf(highestProbed, n)
            error("no save")
        }
        assertEquals(WolfSaveSelection.MAX_PROBE_SLOTS, highestProbed)
    }

    @Test
    fun slotZeroIsNamedSeparately() {
        assertEquals("slot-0", WolfSaveSelection.slotName(0))
        assertEquals("slot-1", WolfSaveSelection.slotName(1))
    }
}
