package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.runtime.api.RuntimeMemoryCleaner
import io.github.gdlbo.makerplay.runtime.webview.nativebridge.NativeAssetPrefetch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

class NativeAssetPrefetchTest {
    @Test
    fun `cache refuses oversized and over-budget entries and closes permanently`() {
        val prefetch = NativeAssetPrefetch(maxBytes = 5, maxAssetBytes = 4)
        prefetch.stage("a", ByteArray(4))
        prefetch.stage("b", ByteArray(2))
        prefetch.stage("large", ByteArray(6))
        assertEquals(4, prefetch.stagedByteCount())
        assertNull(prefetch.get("b"))
        assertNull(prefetch.get("large"))
        prefetch.stage("a", ByteArray(2))
        prefetch.stage("b", ByteArray(3))
        assertEquals(5, prefetch.stagedByteCount())
        prefetch.close()
        prefetch.stage("late", ByteArray(1))
        assertEquals(0, prefetch.stagedCount())
    }

    @Test
    fun `prefetch budgets before reading and eviction rejects in-flight results`() {
        val prefetch = NativeAssetPrefetch(maxBytes = 8, maxAssetBytes = 4)
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val calls = AtomicInteger()
        try {
            prefetch.start(listOf("a" to 4L, "b" to 4L, "c" to 4L, "huge" to 20L)) {
                calls.incrementAndGet()
                entered.countDown()
                try {
                    check(release.await(5, TimeUnit.SECONDS))
                    ByteArray(4)
                } finally { finished.countDown() }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(2, calls.get())
            prefetch.clear()
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(0, prefetch.stagedCount())
            assertEquals(0, prefetch.stagedByteCount())
            assertEquals(2, calls.get())
        } finally {
            release.countDown()
            prefetch.close()
        }
    }

    @Test
    fun `clear removes every staged prefetch entry`() {
        val prefetch = NativeAssetPrefetch()
        prefetch.stage("img/pictures/a.png", byteArrayOf(1))
        prefetch.stage("audio/bgm/theme.ogg", byteArrayOf(2, 3))

        assertEquals(2, prefetch.stagedCount())
        assertEquals(2, prefetch.get("audio/bgm/theme.ogg")!!.size)
        assertEquals(2, prefetch.completedCount())

        // This is exactly what WebViewRuntimeBackend.destroySession runs on close.
        prefetch.clear()

        assertEquals(0, prefetch.stagedCount())
        assertEquals(0, prefetch.completedCount())
        assertNull(prefetch.get("img/pictures/a.png"))
        assertNull(prefetch.get("audio/bgm/theme.ogg"))
    }

    @Test
    fun `memory cleaner eviction clears staged prefetch`() {
        val prefetch = NativeAssetPrefetch()
        prefetch.stage("data/Map001.json", byteArrayOf(9))
        val registration = RuntimeMemoryCleaner.register(prefetch::clear)
        try {
            RuntimeMemoryCleaner.evictRegisteredCaches()
            assertEquals(0, prefetch.stagedCount())
            assertNull(prefetch.get("data/Map001.json"))
        } finally {
            registration.close()
        }
    }
}
