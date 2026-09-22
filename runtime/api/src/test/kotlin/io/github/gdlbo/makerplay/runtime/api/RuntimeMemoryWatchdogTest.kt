package io.github.gdlbo.makerplay.runtime.api

import io.github.gdlbo.makerplay.diagnostics.RuntimeLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeMemoryWatchdogTest {

    @Test
    fun `disabled watchdog neither schedules nor samples`() {
        val scheduler = FakeScheduler()
        var samples = 0
        val watchdog = RuntimeMemoryWatchdog(
            logger = FakeLogger(),
            sampler = { samples++; snapshot(heapUsed = 10, heapMax = 100) },
            options = RuntimeMemoryWatchdogOptions(enabled = false, intervalMillis = 10_000),
            scheduler = scheduler,
        )

        watchdog.start()
        watchdog.sampleNow(MemorySampleReason.TRIM_MEMORY)

        assertFalse(watchdog.isEnabled)
        assertFalse(watchdog.isRunning)
        assertNull(scheduler.scheduledInterval)
        assertEquals(0, samples)
    }

    @Test
    fun `enabled watchdog schedules the configured interval and samples periodically`() {
        val scheduler = FakeScheduler()
        var samples = 0
        val watchdog = RuntimeMemoryWatchdog(
            logger = FakeLogger(),
            sampler = { samples++; snapshot(heapUsed = 1, heapMax = 100) },
            options = RuntimeMemoryWatchdogOptions(enabled = true, intervalMillis = 15_000),
            scheduler = scheduler,
        )

        watchdog.start()
        watchdog.start()
        assertEquals(15_000L, scheduler.scheduledInterval)
        assertTrue(watchdog.isRunning)

        scheduler.task!!.run()
        scheduler.task!!.run()
        assertEquals(2, samples)

        watchdog.stop()
        watchdog.stop()
        assertFalse(watchdog.isRunning)
        assertEquals(1, scheduler.cancellations)
    }

    @Test
    fun `threshold crossing trims once per cooldown`() {
        var now = 1_000L
        var trims = 0
        val logger = FakeLogger()
        val watchdog = RuntimeMemoryWatchdog(
            logger = logger,
            sampler = { snapshot(heapUsed = 90, heapMax = 100) },
            options = RuntimeMemoryWatchdogOptions(
                enabled = true,
                intervalMillis = 10_000,
                heapTrimFraction = 0.80f,
                minTrimIntervalMillis = 30_000,
            ),
            trim = { trims++ },
            clock = { now },
            scheduler = FakeScheduler(),
        )

        watchdog.sampleNow(MemorySampleReason.PERIODIC)
        watchdog.sampleNow(MemorySampleReason.PERIODIC)
        assertEquals(1, trims)
        val first = logger.infoEvents.first().second
        assertEquals("runtime.memory.sample", logger.infoEvents.first().first)
        assertEquals("true", first["thresholdExceeded"])
        assertEquals("true", first["trimmed"])
        assertEquals("0.900", first["heapFraction"])
        assertEquals("false", logger.infoEvents.last().second["trimmed"])

        now += 30_000
        watchdog.sampleNow(MemorySampleReason.PERIODIC)
        assertEquals(2, trims)
    }

    @Test
    fun `system trim memory evicts even when heap is below threshold`() {
        var trims = 0
        val logger = FakeLogger()
        val watchdog = RuntimeMemoryWatchdog(
            logger = logger,
            sampler = { snapshot(heapUsed = 10, heapMax = 100, nativeUsed = 1, graphicsUsed = 2, pss = 3) },
            options = RuntimeMemoryWatchdogOptions(enabled = true, intervalMillis = 10_000),
            trim = { trims++ },
            scheduler = FakeScheduler(),
        )

        watchdog.sampleNow(MemorySampleReason.TRIM_MEMORY)
        watchdog.sampleNow(MemorySampleReason.PERIODIC)

        assertEquals(1, trims)
        val trimSample = logger.infoEvents.first().second
        assertEquals("true", trimSample["trimmed"])
        assertEquals("false", trimSample["thresholdExceeded"])
        assertEquals("2", trimSample["graphicsUsedBytes"])
        assertEquals("3", trimSample["pssBytes"])
        assertEquals("false", logger.infoEvents.last().second["trimmed"])
    }

    @Test
    fun `graphics threshold triggers trimming independently of heap`() {
        var trims = 0
        val watchdog = RuntimeMemoryWatchdog(
            logger = FakeLogger(),
            sampler = { snapshot(heapUsed = 1, heapMax = 100, graphicsUsed = 2_000) },
            options = RuntimeMemoryWatchdogOptions(
                enabled = true,
                intervalMillis = 10_000,
                graphicsTrimBytes = 1_000,
            ),
            trim = { trims++ },
            scheduler = FakeScheduler(),
        )

        watchdog.sampleNow(MemorySampleReason.PERIODIC)

        assertEquals(1, trims)
    }

    @Test
    fun `below threshold samples are logged without trimming`() {
        var trims = 0
        val logger = FakeLogger()
        val watchdog = RuntimeMemoryWatchdog(
            logger = logger,
            sampler = { snapshot(heapUsed = 10, heapMax = 100) },
            options = RuntimeMemoryWatchdogOptions(enabled = true, intervalMillis = 10_000),
            trim = { trims++ },
            scheduler = FakeScheduler(),
        )

        watchdog.sampleNow(MemorySampleReason.MANUAL)

        assertEquals(0, trims)
        val fields = logger.infoEvents.single().second
        assertEquals("false", fields["thresholdExceeded"])
        assertEquals("false", fields["trimmed"])
        assertEquals("0.100", fields["heapFraction"])
        assertEquals("MANUAL", fields["reason"])
    }

    @Test
    fun `native threshold triggers trimming independently of heap`() {
        var trims = 0
        val watchdog = RuntimeMemoryWatchdog(
            logger = FakeLogger(),
            sampler = { snapshot(heapUsed = 1, heapMax = 100, nativeUsed = 2_000) },
            options = RuntimeMemoryWatchdogOptions(
                enabled = true,
                intervalMillis = 10_000,
                nativeTrimBytes = 1_000,
            ),
            trim = { trims++ },
            scheduler = FakeScheduler(),
        )

        watchdog.sampleNow(MemorySampleReason.PERIODIC)

        assertEquals(1, trims)
    }

    @Test
    fun `sampler failure is logged and does not throw`() {
        val logger = FakeLogger()
        val watchdog = RuntimeMemoryWatchdog(
            logger = logger,
            sampler = { error("sampler unavailable") },
            options = RuntimeMemoryWatchdogOptions(enabled = true, intervalMillis = 10_000),
            scheduler = FakeScheduler(),
        )

        watchdog.sampleNow(MemorySampleReason.TRIM_MEMORY)

        assertEquals("runtime.memory.sample_failed", logger.errorEvents.single().first)
        assertEquals("java.lang.IllegalStateException", logger.errorEvents.single().second["failure"])
    }

    @Test
    fun `trim failure is logged and does not throw`() {
        val logger = FakeLogger()
        val watchdog = RuntimeMemoryWatchdog(
            logger = logger,
            sampler = { snapshot(heapUsed = 90, heapMax = 100) },
            options = RuntimeMemoryWatchdogOptions(enabled = true, intervalMillis = 10_000),
            trim = { error("eviction failed") },
            scheduler = FakeScheduler(),
        )

        watchdog.sampleNow(MemorySampleReason.PERIODIC)

        assertEquals("runtime.memory.trim_failed", logger.errorEvents.single().first)
    }

    @Test
    fun `scheduler failure is logged and leaves the watchdog stopped`() {
        val logger = FakeLogger()
        val watchdog = RuntimeMemoryWatchdog(
            logger = logger,
            sampler = { snapshot(heapUsed = 1, heapMax = 100) },
            options = RuntimeMemoryWatchdogOptions(enabled = true, intervalMillis = 10_000),
            scheduler = { _, _ -> error("scheduler unavailable") },
        )

        watchdog.start()

        assertFalse(watchdog.isRunning)
        assertEquals("runtime.memory.watchdog_start_failed", logger.errorEvents.single().first)
    }

    private fun snapshot(
        heapUsed: Long,
        heapMax: Long,
        nativeUsed: Long = 0L,
        graphicsUsed: Long = 0L,
        pss: Long = 0L,
    ) = MemorySnapshot(
        heapUsedBytes = heapUsed,
        heapMaxBytes = heapMax,
        nativeUsedBytes = nativeUsed,
        graphicsUsedBytes = graphicsUsed,
        pssBytes = pss,
    )

    private class FakeScheduler : WatchdogScheduler {
        var scheduledInterval: Long? = null
        var task: Runnable? = null
        var cancellations = 0

        override fun schedulePeriodically(intervalMillis: Long, task: Runnable): AutoCloseable {
            scheduledInterval = intervalMillis
            this.task = task
            return AutoCloseable { cancellations += 1 }
        }
    }

    private class FakeLogger : RuntimeLogger {
        val infoEvents = mutableListOf<Pair<String, Map<String, String>>>()
        val errorEvents = mutableListOf<Pair<String, Map<String, String>>>()

        override fun info(event: String, fields: Map<String, String>) {
            infoEvents += event to fields
        }

        override fun error(event: String, throwable: Throwable) {
            errorEvents += event to mapOf("failure" to throwable.javaClass.simpleName)
        }

        override fun error(event: String, fields: Map<String, String>) {
            errorEvents += event to fields
        }
    }
}
