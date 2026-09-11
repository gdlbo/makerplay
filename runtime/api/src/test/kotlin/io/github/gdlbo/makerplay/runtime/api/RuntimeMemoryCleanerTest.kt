package io.github.gdlbo.makerplay.runtime.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RuntimeMemoryCleanerTest {

    @Test
    fun runsRegisteredCallbacksAndToleratesFailures() {
        val count = AtomicInteger(0)
        val secondRan = AtomicBoolean(false)

        val reg1 = RuntimeMemoryCleaner.register {
            count.incrementAndGet()
            throw RuntimeException("boom")
        }
        val reg2 = RuntimeMemoryCleaner.register {
            secondRan.set(true)
            count.incrementAndGet()
        }

        try {
            RuntimeMemoryCleaner.cleanUpMemory()
            assertEquals(2, count.get())
            assertTrue(secondRan.get())
        } finally {
            reg1.close()
            reg2.close()
        }
    }

    @Test
    fun unregisterStopsInvocation() {
        val count = AtomicInteger(0)
        val registration = RuntimeMemoryCleaner.register { count.incrementAndGet() }

        RuntimeMemoryCleaner.cleanUpMemory()
        assertEquals(1, count.get())

        registration.close()
        RuntimeMemoryCleaner.cleanUpMemory()
        assertEquals(1, count.get())
    }
}
