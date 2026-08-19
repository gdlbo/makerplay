package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.runtime.api.GameSaveStore
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Keeps the synchronous MV storage API off durable disk I/O after session preparation. */
internal class WriteBehindGameSaveStore(
    private val gameId: String,
    private val delegate: GameSaveStore,
    private val maxCachedBytes: Int = DEFAULT_MAX_CACHED_BYTES,
    private val onPersistenceFailure: (Throwable) -> Unit = {},
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "makerplay-mv-save-$gameId").apply { isDaemon = true }
    },
) : GameSaveStore, Closeable {
    private val lock = Any()
    private val knownKeys = linkedSetOf<String>()
    private val cache = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private val pending = linkedMapOf<String, Mutation>()
    private val inFlight = mutableMapOf<String, Mutation>()
    private var cachedBytes = 0
    private var drainScheduled = false
    private var closed = false

    init {
        require(maxCachedBytes > 0) { "maxCachedBytes must be positive" }
    }

    fun preload() {
        val keys = runCatching { delegate.keys(gameId) }
            .onFailure(onPersistenceFailure)
            .getOrElse { return }
            .sortedWith(compareBy(::preloadPriority, String::lowercase))
        synchronized(lock) { knownKeys += keys }
        for (key in keys) {
            val payload = runCatching { delegate.read(gameId, key) }
                .onFailure(onPersistenceFailure)
                .getOrNull() ?: continue
            synchronized(lock) {
                if (cachedBytes + payload.size > maxCachedBytes) return
                putCached(key, payload)
            }
        }
    }

    override fun read(gameId: String, key: String): ByteArray? {
        requireGame(gameId)
        synchronized(lock) {
            when (val mutation = pending[key]) {
                is Mutation.Write -> return mutation.payload.copyOf()
                Mutation.Delete -> return null
                null -> when (val active = inFlight[key]) {
                    is Mutation.Write -> return active.payload.copyOf()
                    Mutation.Delete -> return null
                    null -> cache[key]?.let { return it.copyOf() }
                }
            }
            if (key !in knownKeys) return null
        }

        val loaded = delegate.read(gameId, key) ?: return null
        synchronized(lock) {
            when (val mutation = pending[key]) {
                is Mutation.Write -> return mutation.payload.copyOf()
                Mutation.Delete -> return null
                null -> when (val active = inFlight[key]) {
                    is Mutation.Write -> return active.payload.copyOf()
                    Mutation.Delete -> return null
                    null -> {
                        putCached(key, loaded)
                        return loaded.copyOf()
                    }
                }
            }
        }
    }

    override fun write(gameId: String, key: String, payload: ByteArray) {
        requireGame(gameId)
        val copy = payload.copyOf()
        synchronized(lock) {
            check(!closed) { "Save store is closed" }
            knownKeys += key
            putCached(key, copy)
            pending[key] = Mutation.Write(copy)
            scheduleDrain()
        }
    }

    override fun delete(gameId: String, key: String): Boolean {
        requireGame(gameId)
        synchronized(lock) {
            check(!closed) { "Save store is closed" }
            val existed = key in knownKeys
            knownKeys -= key
            removeCached(key)
            pending[key] = Mutation.Delete
            scheduleDrain()
            return existed
        }
    }

    override fun keys(gameId: String): Set<String> {
        requireGame(gameId)
        return synchronized(lock) { knownKeys.toSet() }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        executor.submit { }.get()
        executor.shutdown()
    }

    private fun scheduleDrain() {
        if (drainScheduled) return
        drainScheduled = true
        executor.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val entry = synchronized(lock) {
                val next = pending.entries.firstOrNull()
                if (next == null) {
                    drainScheduled = false
                    return
                }
                pending.remove(next.key)
                inFlight[next.key] = next.value
                next.key to next.value
            }
            runCatching {
                when (val mutation = entry.second) {
                    is Mutation.Write -> delegate.write(gameId, entry.first, mutation.payload)
                    Mutation.Delete -> delegate.delete(gameId, entry.first)
                }
            }.onFailure(onPersistenceFailure)
            synchronized(lock) {
                inFlight.remove(entry.first)
                trimCache()
            }
        }
    }

    private fun putCached(key: String, payload: ByteArray) {
        cachedBytes -= cache.remove(key)?.size ?: 0
        cache[key] = payload
        cachedBytes += payload.size
        trimCache()
    }

    private fun removeCached(key: String) {
        cachedBytes -= cache.remove(key)?.size ?: 0
    }

    private fun trimCache() {
        val iterator = cache.entries.iterator()
        while (cachedBytes > maxCachedBytes && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in pending || entry.key in inFlight) continue
            cachedBytes -= entry.value.size
            iterator.remove()
        }
    }

    private fun requireGame(candidate: String) {
        require(candidate == gameId) { "Save store is scoped to a different game" }
    }

    private sealed interface Mutation {
        data class Write(val payload: ByteArray) : Mutation {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as Write

                return payload.contentEquals(other.payload)
            }

            override fun hashCode(): Int {
                return payload.contentHashCode()
            }
        }

        data object Delete : Mutation
    }

    companion object {
        private const val DEFAULT_MAX_CACHED_BYTES = 16 * 1024 * 1024

        private fun preloadPriority(key: String): Int = when (key.lowercase()) {
            "config" -> 0
            "global" -> 1
            else -> 2
        }
    }
}