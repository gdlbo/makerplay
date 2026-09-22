package io.github.gdlbo.makerplay.runtime.api

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Coordinates memory cleanup across the app runtime when games finish or close.
 * Modules register domain-specific evictions (e.g. decoded bitmap caches,
 * native asset prefetch maps), and [cleanUpMemory] runs them before requesting
 * a garbage collection and finalization pass from the VM.
 */
object RuntimeMemoryCleaner {
    private val callbacks = CopyOnWriteArrayList<() -> Unit>()

    fun register(callback: () -> Unit): AutoCloseable {
        callbacks.add(callback)
        return AutoCloseable { callbacks.remove(callback) }
    }

    /**
     * Evicts registered domain caches without asking the VM for a garbage collection pass.
     * Safe to call from background samplers such as [RuntimeMemoryWatchdog].
     */
    fun evictRegisteredCaches() {
        for (callback in callbacks) {
            runCatching { callback() }
        }
    }

    fun cleanUpMemory() {
        evictRegisteredCaches()
        runCatching {
            System.runFinalization()
            Runtime.getRuntime().gc()
        }
    }
}
