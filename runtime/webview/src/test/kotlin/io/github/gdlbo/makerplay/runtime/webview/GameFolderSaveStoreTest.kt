package io.github.gdlbo.makerplay.runtime.webview

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

class GameFolderSaveStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun mvReadsWritesAndListsThePhysicalSaveDirectory() {
        val root = temporaryFolder.newFolder("mv")
        val save = File(root, "save").apply { mkdirs() }
        File(save, "file3.rpgsave").writeText("three")
        File(save, "file7.rpgsave").writeText("seven")
        val store = GameFolderSaveStore(root, ".rpgsave")

        assertEquals(setOf("file3", "file7"), store.keys("game"))
        assertArrayEquals("three".encodeToByteArray(), store.read("game", "file3"))

        store.write("game", "file9", "nine".encodeToByteArray())
        assertEquals("nine", File(save, "file9.rpgsave").readText())
        assertFalse(save.listFiles().orEmpty().any { it.name.endsWith(".makerplay.tmp") })

        assertTrue(store.delete("game", "file3"))
        assertFalse(File(save, "file3.rpgsave").exists())
        assertEquals(setOf("file7", "file9"), store.keys("game"))
    }

    @Test
    fun mzPreservesBinaryTextEncodingOnDisk() {
        val root = temporaryFolder.newFolder("mz")
        val store = GameFolderSaveStore(root, ".rmmzsave")
        val payload = byteArrayOf(0x00, 0x7f, 0x80.toByte(), 0xff.toByte())

        store.write("game", "MyState", payload)

        val diskText = File(root, "save/MyState.rmmzsave").readText(StandardCharsets.UTF_8)
        assertEquals(listOf(0x00, 0x7f, 0x80, 0xff), diskText.map(Char::code))
        assertArrayEquals(payload, store.read("game", "MyState"))
    }
}
