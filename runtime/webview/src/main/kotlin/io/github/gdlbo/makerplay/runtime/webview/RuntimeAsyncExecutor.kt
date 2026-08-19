package io.github.gdlbo.makerplay.runtime.webview

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal object RuntimeAsyncExecutor {
    private const val MAX_WORKERS = 4
    private const val QUEUE_PER_WORKER = 32
    private const val REQUESTS_PER_WORKER = 16
    private const val MAX_ATTACHMENT_REQUESTS = 64
    private const val MAX_ATTACHMENT_CHARS = 32L * 1024L * 1024L

    internal val workerCount: Int = workerCount(Runtime.getRuntime().availableProcessors())
    internal val attachmentLimit: Int =
        (workerCount * REQUESTS_PER_WORKER).coerceIn(8, MAX_ATTACHMENT_REQUESTS)
    internal val maxAttachmentChars: Long = MAX_ATTACHMENT_CHARS

    internal fun workerCount(availableProcessors: Int): Int = when {
        availableProcessors <= 2 -> 1
        availableProcessors <= 4 -> 2
        else -> minOf(MAX_WORKERS, maxOf(2, availableProcessors / 2))
    }

    internal fun create(name: String): ThreadPoolExecutor =
        ThreadPoolExecutor(
            workerCount,
            workerCount,
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(workerCount * QUEUE_PER_WORKER),
            NamedThreadFactory(name),
        ).apply {
            allowCoreThreadTimeOut(true)
        }

    private class NamedThreadFactory(private val name: String) : ThreadFactory {
        private val sequence = AtomicInteger(0)

        override fun newThread(task: Runnable): Thread =
            Thread(task, "$name-${sequence.incrementAndGet()}").apply { isDaemon = true }
    }
}

internal class RuntimeSerialQueue(
    private val executor: Executor,
    private val capacity: Int = RuntimeAsyncExecutor.attachmentLimit,
    private val maxWeight: Long = RuntimeAsyncExecutor.maxAttachmentChars,
) {
    private data class Entry(
        val weight: Int,
        val task: () -> Unit,
        val rejected: () -> Unit,
    )

    private val lock = Any()
    private val pending = ArrayDeque<Entry>()
    private var outstanding = 0
    private var outstandingWeight = 0L
    private var running = false
    private var closed = false

    fun submit(weight: Int, task: () -> Unit, rejected: () -> Unit): Boolean {
        require(weight >= 0) { "weight must not be negative" }
        val schedule = synchronized(lock) {
            if (closed || outstanding >= capacity || outstandingWeight + weight > maxWeight) return false
            pending.addLast(Entry(weight, task, rejected))
            outstanding += 1
            outstandingWeight += weight
            if (running) false else {
                running = true
                true
            }
        }
        if (schedule) dispatch()
        return true
    }

    fun close() {
        val dropped = synchronized(lock) {
            if (closed) return
            closed = true
            drainPendingLocked()
        }
        dropped.forEach { it.rejected() }
    }

    private fun dispatch() {
        try {
            executor.execute(::runNext)
        } catch (_: RejectedExecutionException) {
            val dropped = synchronized(lock) {
                running = false
                drainPendingLocked()
            }
            dropped.forEach { it.rejected() }
        }
    }

    private fun runNext() {
        val entry = synchronized(lock) { pending.removeFirstOrNull() }
        if (entry == null) {
            synchronized(lock) { running = false }
            return
        }
        try {
            entry.task()
        } finally {
            val scheduleNext = synchronized(lock) {
                outstanding -= 1
                outstandingWeight -= entry.weight
                if (closed) {
                    running = false
                    false
                } else if (pending.isEmpty()) {
                    running = false
                    false
                } else {
                    true
                }
            }
            if (scheduleNext) dispatch()
        }
    }

    private fun drainPendingLocked(): List<Entry> {
        val dropped = buildList {
            while (pending.isNotEmpty()) add(pending.removeFirst())
        }
        outstanding -= dropped.size
        outstandingWeight -= dropped.sumOf { it.weight.toLong() }
        return dropped
    }
}