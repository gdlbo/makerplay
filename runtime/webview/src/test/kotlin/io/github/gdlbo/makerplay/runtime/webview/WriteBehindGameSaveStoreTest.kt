package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.runtime.api.GameSaveStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WriteBehindGameSaveStoreTest {
    @Test
    fun `write returns before durable storage and reads the latest value`() {
        val delegate = BlockingStore()
        val store = WriteBehindGameSaveStore("game", delegate, maxCachedBytes = 1)
        store.preload()

        store.write("game", "file1", "latest".encodeToByteArray())
        delegate.writeStarted.await(2, TimeUnit.SECONDS)

        assertArrayEquals("latest".encodeToByteArray(), store.read("game", "file1"))
        delegate.allowWrites.countDown()
        store.close()
        assertArrayEquals("latest".encodeToByteArray(), delegate.read("game", "file1"))
    }

    @Test
    fun `preloaded value is read without returning to durable storage`() {
        val delegate = BlockingStore().apply {
            seed("game", "file1", "loaded".encodeToByteArray())
        }
        val store = WriteBehindGameSaveStore("game", delegate)
        store.preload()
        delegate.failReads = true

        assertArrayEquals("loaded".encodeToByteArray(), store.read("game", "file1"))
        store.close()
    }

    @Test
    fun `repeated pending writes persist only the latest queued value`() {
        val delegate = BlockingStore()
        val store = WriteBehindGameSaveStore("game", delegate)
        store.write("game", "file1", "first".encodeToByteArray())
        delegate.writeStarted.await(2, TimeUnit.SECONDS)

        store.write("game", "file1", "second".encodeToByteArray())
        store.write("game", "file1", "third".encodeToByteArray())
        delegate.allowWrites.countDown()
        store.close()

        assertEquals(listOf("first", "third"), delegate.writes.map(ByteArray::decodeToString))
        assertArrayEquals("third".encodeToByteArray(), delegate.read("game", "file1"))
    }

    private class BlockingStore : GameSaveStore {
        private val values = linkedMapOf<Pair<String, String>, ByteArray>()
        val writes = mutableListOf<ByteArray>()
        val writeStarted = CountDownLatch(1)
        val allowWrites = CountDownLatch(1)
        var failReads = false

        override fun read(gameId: String, key: String): ByteArray? = synchronized(values) {
            check(!failReads)
            values[gameId to key]?.copyOf()
        }

        fun seed(gameId: String, key: String, payload: ByteArray) = synchronized(values) {
            values[gameId to key] = payload.copyOf()
        }

        override fun write(gameId: String, key: String, payload: ByteArray) {
            writeStarted.countDown()
            check(allowWrites.await(2, TimeUnit.SECONDS))
            synchronized(values) {
                writes += payload.copyOf()
                values[gameId to key] = payload.copyOf()
            }
        }

        override fun delete(gameId: String, key: String): Boolean = synchronized(values) {
            values.remove(gameId to key) != null
        }

        override fun keys(gameId: String): Set<String> = synchronized(values) {
            values.keys.filter { it.first == gameId }.mapTo(mutableSetOf()) { it.second }
        }
    }
}
