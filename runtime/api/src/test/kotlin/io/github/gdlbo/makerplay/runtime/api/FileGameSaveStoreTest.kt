package io.github.gdlbo.makerplay.runtime.api

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FileGameSaveStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `save survives a new store instance`() {
        val root = temporaryFolder.newFolder("saves")
        FileGameSaveStore(root).write("game-one", "file1", "progress".encodeToByteArray())

        val payload = FileGameSaveStore(root).read("game-one", "file1")

        assertArrayEquals("progress".encodeToByteArray(), payload)
        assertEquals(setOf("file1"), FileGameSaveStore(root).keys("game-one"))
    }

    @Test
    fun `games and keys remain isolated`() {
        val store = FileGameSaveStore(temporaryFolder.newFolder("saves"))
        store.write("game-a", "file1", byteArrayOf(1))
        store.write("game-a", "file2", byteArrayOf(2))
        store.write("game-b", "file1", byteArrayOf(3))

        assertArrayEquals(byteArrayOf(1), store.read("game-a", "file1"))
        assertArrayEquals(byteArrayOf(2), store.read("game-a", "file2"))
        assertArrayEquals(byteArrayOf(3), store.read("game-b", "file1"))
        assertEquals(setOf("file1", "file2"), store.keys("game-a"))
        assertTrue(store.delete("game-a", "file1"))
        assertNull(store.read("game-a", "file1"))
        assertArrayEquals(byteArrayOf(3), store.read("game-b", "file1"))
        assertFalse(store.delete("game-a", "missing"))
    }

    @Test
    fun `deleting a game removes only its saves`() {
        val store = FileGameSaveStore(temporaryFolder.newFolder("saves"))
        store.write("game-a", "file1", byteArrayOf(1))
        store.write("game-b", "file1", byteArrayOf(2))

        assertTrue(store.deleteGame("game-a"))
        assertTrue(store.keys("game-a").isEmpty())
        assertArrayEquals(byteArrayOf(2), store.read("game-b", "file1"))
        assertFalse(store.deleteGame("game-a"))
    }

    @Test
    fun `oversize write leaves existing save unchanged`() {
        val store = FileGameSaveStore(
            root = temporaryFolder.newFolder("saves"),
            maxPayloadBytes = 4,
        )
        store.write("game", "file1", byteArrayOf(1, 2, 3, 4))

        assertThrows(GameSaveLimitException::class.java) {
            store.write("game", "file1", ByteArray(5))
        }

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), store.read("game", "file1"))
    }

    @Test
    fun `entry bound rejects a new key without changing existing entries`() {
        val store = FileGameSaveStore(
            root = temporaryFolder.newFolder("saves"),
            maxEntriesPerGame = 1,
        )
        store.write("game", "file1", byteArrayOf(1))

        assertThrows(GameSaveLimitException::class.java) {
            store.write("game", "file2", byteArrayOf(2))
        }

        assertEquals(setOf("file1"), store.keys("game"))
        assertArrayEquals(byteArrayOf(1), store.read("game", "file1"))
    }

    @Test
    fun `traversal and separators are rejected`() {
        val root = temporaryFolder.newFolder("saves")
        val store = FileGameSaveStore(root)

        listOf("..", "../other", "game/other", "game\\other", "", ".").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                store.write(invalid, "file1", byteArrayOf(1))
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.write("game", invalid, byteArrayOf(1))
            }
        }
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `corrupt primary recovers newest valid backup and repairs primary`() {
        val root = temporaryFolder.newFolder("saves")
        val store = FileGameSaveStore(root)
        store.write("game", "file1", "first".encodeToByteArray())
        store.write("game", "file1", "second".encodeToByteArray())
        store.write("game", "file1", "third".encodeToByteArray())
        val primary = File(root, "game/file1.sav")
        val corrupted = primary.readBytes()
        corrupted[corrupted.lastIndex] = (corrupted.last() + 1).toByte()
        primary.writeBytes(corrupted)

        assertArrayEquals(
            "second".encodeToByteArray(),
            FileGameSaveStore(root).read("game", "file1")
        )
        assertArrayEquals(
            "second".encodeToByteArray(),
            FileGameSaveStore(root).read("game", "file1")
        )
    }

    @Test
    fun `second backup recovers when primary and newest backup are corrupt`() {
        val root = temporaryFolder.newFolder("saves")
        val store = FileGameSaveStore(root)
        store.write("game", "file1", "first".encodeToByteArray())
        store.write("game", "file1", "second".encodeToByteArray())
        store.write("game", "file1", "third".encodeToByteArray())
        File(root, "game/file1.sav").writeBytes(byteArrayOf(0))
        File(root, "game/file1.sav.bak1").writeBytes(byteArrayOf(0))

        assertArrayEquals(
            "first".encodeToByteArray(),
            FileGameSaveStore(root).read("game", "file1")
        )
    }

    @Test
    fun `backup-only entry remains listed and does not consume a second entry`() {
        val root = temporaryFolder.newFolder("saves")
        val store = FileGameSaveStore(root, maxEntriesPerGame = 1)
        store.write("game", "file1", "first".encodeToByteArray())
        File(root, "game/file1.sav").renameTo(File(root, "game/file1.sav.bak1"))

        assertEquals(setOf("file1"), store.keys("game"))
        store.write("game", "file1", "second".encodeToByteArray())
        assertArrayEquals("second".encodeToByteArray(), store.read("game", "file1"))
    }

    @Test
    fun `store instances sharing a root serialize writes`() {
        val root = temporaryFolder.newFolder("saves")
        val stores = listOf(FileGameSaveStore(root), FileGameSaveStore(root))
        val payloads = listOf(ByteArray(1024) { 0x11 }, ByteArray(1024) { 0x22 })
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writes = stores.indices.map { index ->
                executor.submit {
                    start.await()
                    repeat(25) { stores[index].write("game", "file1", payloads[index]) }
                }
            }
            start.countDown()
            writes.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val saved = FileGameSaveStore(root).read("game", "file1")
        assertTrue(payloads.any { it.contentEquals(saved) })
    }
}