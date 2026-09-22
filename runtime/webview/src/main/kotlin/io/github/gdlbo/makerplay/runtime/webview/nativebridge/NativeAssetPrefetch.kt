package io.github.gdlbo.makerplay.runtime.webview.nativebridge

import io.github.gdlbo.makerplay.vfs.GameFileSystem
import io.github.gdlbo.makerplay.vfs.VfsOpenResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

/** Bounded boot staging. Demand reads never wait for speculative background work. */
internal class NativeAssetPrefetch(
    private val maxBytes: Int = 16 * 1024 * 1024,
    private val maxAssetBytes: Int = 4 * 1024 * 1024,
) : AutoCloseable {
    private val lock = Any()
    private val plaintext = LinkedHashMap<String, ByteArray>()
    private var stagedBytes = 0
    private var completed = 0
    private var generation = 0L
    private var closed = false
    private var workers: CoroutineScope? = null

    init {
        require(maxBytes > 0 && maxAssetBytes > 0)
    }

    fun get(path: String): ByteArray? = synchronized(lock) { plaintext[path] }

    internal fun stage(path: String, bytes: ByteArray) = synchronized(lock) {
        stageLocked(path, bytes)
    }

    private fun stageLocked(path: String, bytes: ByteArray) {
        if (closed || bytes.size > maxAssetBytes) return
        val previousSize = plaintext[path]?.size ?: 0
        if (bytes.size.toLong() + stagedBytes - previousSize > maxBytes) return
        plaintext[path] = bytes
        stagedBytes += bytes.size - previousSize
        completed++
    }

    internal fun stagedCount(): Int = synchronized(lock) { plaintext.size }
    internal fun stagedByteCount(): Int = synchronized(lock) { stagedBytes }
    fun completedCount(): Int = synchronized(lock) { completed }

    fun clear() = synchronized(lock) { clearLocked() }

    private fun clearLocked() {
        generation++
        workers?.cancel()
        workers = null
        plaintext.clear()
        stagedBytes = 0
        completed = 0
    }

    override fun close() = synchronized(lock) {
        closed = true
        clearLocked()
    }

    fun prefetch(fileSystem: GameFileSystem, logicalPaths: List<String>) {
        val candidates = logicalPaths.distinct().mapNotNull { path ->
            fileSystem.resolve(path)?.let { path to it.storedSize }
        }
        start(candidates) { path ->
            val opened = fileSystem.open(path) as? VfsOpenResult.Found ?: return@start null
            opened.stream.use { stream ->
                if (opened.contentLength > maxAssetBytes) null else stream.readBytes()
            }
        }
    }

    /** Candidate sizes reserve the budget before any I/O or allocation starts. */
    internal fun start(candidates: List<Pair<String, Long>>, read: (String) -> ByteArray?) {
        synchronized(lock) {
            if (closed) return
            clearLocked()
            var reserved = 0L
            val seen = HashSet<String>()
            val accepted = candidates.asSequence().filter { (path, size) ->
                seen.add(path) && size in 0L..maxAssetBytes.toLong() &&
                    size <= maxBytes - reserved
            }.take(64).map { (path, size) ->
                reserved += size
                path
            }.toList()
            val queue = ConcurrentLinkedQueue(accepted)
            val token = generation
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            workers = scope
            repeat(minOf(2, accepted.size)) {
                scope.launch {
                    while (true) {
                        ensureActive()
                        val path = queue.poll() ?: break
                        val bytes = runCatching { read(path) }.getOrNull()
                        ensureActive()
                        if (bytes != null) synchronized(lock) {
                            if (!closed && token == generation) stageLocked(path, bytes)
                        }
                    }
                }
            }
        }
    }
}
