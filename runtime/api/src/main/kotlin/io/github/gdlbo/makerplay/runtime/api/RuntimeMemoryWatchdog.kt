package io.github.gdlbo.makerplay.runtime.api

import io.github.gdlbo.makerplay.diagnostics.RuntimeLogger
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Why a memory sample was taken. */
enum class MemorySampleReason { PERIODIC, TRIM_MEMORY, MANUAL }

/** Heap, native, and process memory figures captured at one point in time. */
data class MemorySnapshot(
    val heapUsedBytes: Long,
    val heapMaxBytes: Long,
    val nativeUsedBytes: Long,
    val graphicsUsedBytes: Long = 0L,
    val pssBytes: Long = 0L,
) {
    val heapFraction: Float
        get() = if (heapMaxBytes <= 0L) 0f else heapUsedBytes.toFloat() / heapMaxBytes.toFloat()
}

/** Produces one memory sample; injectable so unit tests never touch Android or the real heap. */
fun interface MemorySampler {
    fun sample(): MemorySnapshot
}

/** Schedules the periodic sample task; injectable so unit tests do not start threads. */
fun interface WatchdogScheduler {
    fun schedulePeriodically(intervalMillis: Long, task: Runnable): AutoCloseable
}

/**
 * @param enabled process-wide switch; the default is off so unit tests never start a sampler.
 * @param intervalMillis periodic sampling interval; must be at least [MIN_INTERVAL_MILLIS].
 * @param heapTrimFraction trim registered caches when used heap reaches this fraction of max.
 * @param nativeTrimBytes trim when native heap allocated bytes reach this value (0 disables).
 * @param graphicsTrimBytes trim when graphics/EGL+GL bytes reach this value (0 disables).
 * @param minTrimIntervalMillis cooldown between threshold trims, so pressure cannot cause a hot eviction loop.
 * System [MemorySampleReason.TRIM_MEMORY] samples always evict and ignore the cooldown.
 */
data class RuntimeMemoryWatchdogOptions(
    val enabled: Boolean = false,
    val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    val heapTrimFraction: Float = DEFAULT_HEAP_TRIM_FRACTION,
    val nativeTrimBytes: Long = 0L,
    val graphicsTrimBytes: Long = 0L,
    val minTrimIntervalMillis: Long = DEFAULT_MIN_TRIM_INTERVAL_MILLIS,
) {
    init {
        require(intervalMillis >= MIN_INTERVAL_MILLIS) { "Watchdog interval is too short" }
        require(heapTrimFraction > 0f) { "Heap trim fraction must be positive" }
        require(nativeTrimBytes >= 0L) { "Native trim threshold must not be negative" }
        require(graphicsTrimBytes >= 0L) { "Graphics trim threshold must not be negative" }
        require(minTrimIntervalMillis >= 0L) { "Trim cooldown must not be negative" }
    }

    companion object {
        const val MIN_INTERVAL_MILLIS = 5_000L
        const val DEFAULT_INTERVAL_MILLIS = 60_000L
        const val DEFAULT_HEAP_TRIM_FRACTION = 0.80f
        const val DEFAULT_NATIVE_TRIM_BYTES = 128L * 1024L * 1024L
        const val DEFAULT_MIN_TRIM_INTERVAL_MILLIS = 30_000L
    }
}

/**
 * Samples heap/native memory on a timer and on demand, logs each sample through [RuntimeLogger],
 * and evicts registered caches via [RuntimeMemoryCleaner] when configurable thresholds are
 * crossed.
 *
 * The watchdog never calls `Runtime.getRuntime().gc()` on its own; trims run the registered
 * evictions through [RuntimeMemoryCleaner.evictRegisteredCaches] and are bounded by
 * [RuntimeMemoryWatchdogOptions.minTrimIntervalMillis].
 */
class RuntimeMemoryWatchdog(
    private val logger: RuntimeLogger,
    private val sampler: MemorySampler,
    private var options: RuntimeMemoryWatchdogOptions = RuntimeMemoryWatchdogOptions(),
    private val trim: () -> Unit = RuntimeMemoryCleaner::evictRegisteredCaches,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scheduler: WatchdogScheduler = SystemWatchdogScheduler,
) {
    private val lock = Any()
    private var running = false
    private var scheduled: AutoCloseable? = null
    private var lastTrimAtMillis: Long? = null

    val isEnabled: Boolean
        get() = options.enabled

    /** Replaces thresholds, including a per-game heap fraction, without restarting the sampler. */
    fun updateOptions(next: RuntimeMemoryWatchdogOptions) {
        options = next
    }

    val isRunning: Boolean
        get() = synchronized(lock) { running }

    /** Starts periodic sampling; no-op while [RuntimeMemoryWatchdogOptions.enabled] is false. */
    fun start() {
        if (!options.enabled) return
        synchronized(lock) {
            if (running) return
            val result = runCatching {
                scheduler.schedulePeriodically(
                    options.intervalMillis,
                    Runnable { sample(MemorySampleReason.PERIODIC) },
                )
            }
            val handle = result.getOrNull()
            if (handle == null) {
                logger.error(
                    "runtime.memory.watchdog_start_failed",
                    mapOf("failure" to (result.exceptionOrNull()?.javaClass?.name ?: "unknown")),
                )
                return
            }
            scheduled = handle
            running = true
        }
    }

    /** Stops periodic sampling; safe to call repeatedly. */
    fun stop() {
        val handle = synchronized(lock) {
            if (!running) return
            running = false
            scheduled.also { scheduled = null }
        }
        runCatching { handle?.close() }
    }

    /**
     * Takes an immediate sample (for example from `Application.onTrimMemory`) and applies the
     * threshold/cooldown trim decision. No-op while the watchdog is disabled.
     */
    fun sampleNow(reason: MemorySampleReason, fields: Map<String, String> = emptyMap()) {
        if (!options.enabled) return
        sample(reason, fields)
    }

    private fun sample(reason: MemorySampleReason, fields: Map<String, String> = emptyMap()) {
        val snapshot = runCatching { sampler.sample() }.getOrElse { error ->
            logger.error(
                "runtime.memory.sample_failed",
                fields + mapOf("reason" to reason.name, "failure" to error.javaClass.name),
            )
            return
        }

        val now = clock()
        val heapExceeded = snapshot.heapFraction >= options.heapTrimFraction
        val nativeExceeded = options.nativeTrimBytes > 0L &&
            snapshot.nativeUsedBytes >= options.nativeTrimBytes
        val graphicsExceeded = options.graphicsTrimBytes > 0L &&
            snapshot.graphicsUsedBytes >= options.graphicsTrimBytes
        val thresholdExceeded = heapExceeded || nativeExceeded || graphicsExceeded
        val systemRequested = reason == MemorySampleReason.TRIM_MEMORY
        val trimmed = synchronized(lock) {
            val lastTrimAt = lastTrimAtMillis
            val cooldownElapsed = lastTrimAt == null ||
                now - lastTrimAt >= options.minTrimIntervalMillis
            val allowed = systemRequested || (thresholdExceeded && cooldownElapsed)
            if (allowed) lastTrimAtMillis = now
            allowed
        }

        logger.info(
            "runtime.memory.sample",
            fields + mapOf(
                "reason" to reason.name,
                "heapUsedBytes" to snapshot.heapUsedBytes.toString(),
                "heapMaxBytes" to snapshot.heapMaxBytes.toString(),
                "heapFraction" to String.format(Locale.ROOT, "%.3f", snapshot.heapFraction),
                "nativeUsedBytes" to snapshot.nativeUsedBytes.toString(),
                "graphicsUsedBytes" to snapshot.graphicsUsedBytes.toString(),
                "pssBytes" to snapshot.pssBytes.toString(),
                "thresholdExceeded" to thresholdExceeded.toString(),
                "trimmed" to trimmed.toString(),
            ),
        )

        if (trimmed) {
            runCatching(trim).onFailure { error ->
                logger.error(
                    "runtime.memory.trim_failed",
                    fields + mapOf("reason" to reason.name, "failure" to error.javaClass.name),
                )
            }
        }
    }

    companion object {
        /** Production factory with the Android heap/native sampler and the system scheduler. */
        fun create(
            logger: RuntimeLogger,
            options: RuntimeMemoryWatchdogOptions = RuntimeMemoryWatchdogOptions(),
            trim: () -> Unit = RuntimeMemoryCleaner::evictRegisteredCaches,
        ): RuntimeMemoryWatchdog = RuntimeMemoryWatchdog(
            logger = logger,
            sampler = AndroidMemorySampler,
            options = options,
            trim = trim,
        )

        private val AndroidMemorySampler = MemorySampler {
            val runtime = Runtime.getRuntime()
            val memInfo = android.os.Debug.MemoryInfo()
            android.os.Debug.getMemoryInfo(memInfo)
            MemorySnapshot(
                heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
                heapMaxBytes = runtime.maxMemory(),
                nativeUsedBytes = android.os.Debug.getNativeHeapAllocatedSize(),
                graphicsUsedBytes = memoryStatBytes(memInfo, "summary.graphics"),
                pssBytes = memInfo.totalPss.toLong() * 1024L,
            )
        }

        private fun memoryStatBytes(info: android.os.Debug.MemoryInfo, key: String): Long {
            val kb = info.getMemoryStat(key)?.toLongOrNull() ?: return 0L
            return kb * 1024L
        }
    }
}

private object SystemWatchdogScheduler : WatchdogScheduler {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "makerplay-memory-watchdog").apply { isDaemon = true }
        }

    override fun schedulePeriodically(intervalMillis: Long, task: Runnable): AutoCloseable {
        val future = executor.scheduleWithFixedDelay(
            task,
            intervalMillis,
            intervalMillis,
            TimeUnit.MILLISECONDS,
        )
        return AutoCloseable { future.cancel(false) }
    }
}
