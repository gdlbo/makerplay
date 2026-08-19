package io.github.gdlbo.makerplay.runtime.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RuntimeAsyncExecutorTest {
    @Test
    fun scalesWorkersConservativelyWithAvailableProcessors() {
        assertEquals(1, RuntimeAsyncExecutor.workerCount(1))
        assertEquals(1, RuntimeAsyncExecutor.workerCount(2))
        assertEquals(2, RuntimeAsyncExecutor.workerCount(3))
        assertEquals(2, RuntimeAsyncExecutor.workerCount(4))
        assertEquals(3, RuntimeAsyncExecutor.workerCount(6))
        assertEquals(4, RuntimeAsyncExecutor.workerCount(8))
        assertEquals(4, RuntimeAsyncExecutor.workerCount(32))
    }

    @Test
    fun serialQueuePreservesOrderAndDropsPendingWorkOnClose() {
        val executor = Executors.newFixedThreadPool(2)
        val queue = RuntimeSerialQueue(executor, capacity = 3, maxWeight = 2)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstFinished = CountDownLatch(1)
        val secondRejected = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        try {
            assertTrue(
                queue.submit(
                    weight = 1,
                    task = {
                        firstStarted.countDown()
                        releaseFirst.await()
                        events += "first"
                        firstFinished.countDown()
                    },
                    rejected = {},
                )
            )
            assertTrue(
                queue.submit(
                    weight = 1,
                    task = { events += "second" },
                    rejected = { secondRejected.countDown() },
                )
            )
            assertFalse(queue.submit(weight = 1, task = { events += "overflow" }, rejected = {}))
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))

            queue.close()
            assertTrue(secondRejected.await(2, TimeUnit.SECONDS))
            releaseFirst.countDown()
            assertTrue(firstFinished.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("first"), events)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }
}
