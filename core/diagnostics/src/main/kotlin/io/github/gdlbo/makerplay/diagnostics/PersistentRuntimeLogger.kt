package io.github.gdlbo.makerplay.diagnostics

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class PersistentRuntimeLogger(
    root: File,
    private val delegate: RuntimeLogger,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : RuntimeLogger, AutoCloseable {
    private val root = root.absoluteFile
    private val current = File(this.root, CURRENT_FILE)
    private val writeLock = Any()
    private val writer = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_PENDING_ENTRIES),
        { task -> Thread(task, "makerplay-diagnostics").apply { isDaemon = true } },
        { _, _ ->
            delegate.error(
                "diagnostics.file_queue_full",
                mapOf("capacity" to MAX_PENDING_ENTRIES.toString()),
            )
        },
    )

    init {
        Files.createDirectories(this.root.toPath())
        require(this.root.isDirectory && !Files.isSymbolicLink(this.root.toPath())) {
            "Diagnostic root is unavailable"
        }
        Files.write(
            current.toPath(),
            byteArrayOf(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    override fun info(event: String, fields: Map<String, String>) {
        delegate.info(event, fields)
        enqueue("info", event, sanitizeFields(fields))
    }

    override fun error(event: String, throwable: Throwable) {
        delegate.error(event, throwable)
        enqueue(
            "error",
            event,
            mapOf(
                "failureClass" to throwable.javaClass.name.take(MAX_VALUE_CHARS),
                "stackTrace" to throwable.stackTraceForLog(),
            ),
        )
    }

    override fun error(event: String, fields: Map<String, String>) {
        delegate.error(event, fields)
        enqueue("error", event, sanitizeFields(fields))
    }

    override fun close() {
        writer.shutdown()
        if (!writer.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            writer.shutdownNow()
            delegate.error("diagnostics.file_close_timeout", emptyMap())
        }
    }

    private fun enqueue(level: String, event: String, fields: Map<String, String>) {
        val safeEvent = event.take(MAX_EVENT_CHARS).replace(INVALID_EVENT_CHAR, "_")
        val line = buildJsonObject {
            put("v", JsonPrimitive(1))
            put("timeMs", JsonPrimitive(clockMillis()))
            put("level", JsonPrimitive(level))
            put("event", JsonPrimitive(safeEvent))
            put("fields", buildJsonObject {
                fields.toSortedMap().forEach { (key, value) -> put(key, JsonPrimitive(value)) }
            })
        }.toString() + "\n"
        writer.execute { append(line.toByteArray(StandardCharsets.UTF_8)) }
    }

    private fun append(bytes: ByteArray) = synchronized(writeLock) {
        runCatching {
            appendTo(current, bytes)
        }.getOrElse { failure ->
            delegate.error("diagnostics.file_write_failed", failure)
        }
    }

    private fun appendTo(file: File, bytes: ByteArray) {
        FileOutputStream(file, true).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun sanitizeFields(fields: Map<String, String>): Map<String, String> = fields.entries
        .asSequence()
        .take(MAX_FIELDS)
        .associate { (rawKey, rawValue) ->
            val key = rawKey.take(MAX_KEY_CHARS).replace(INVALID_KEY_CHAR, "_")
            val value = if (REDACTED_KEYS.any { rawKey.contains(it, ignoreCase = true) }) {
                "[REDACTED]"
            } else {
                rawValue.take(MAX_VALUE_CHARS)
            }
            key to value
        }

    companion object {
        const val CURRENT_FILE = "logs.txt"
        private const val MAX_FIELDS = 16
        private const val MAX_EVENT_CHARS = 64
        private const val MAX_KEY_CHARS = 48
        private const val MAX_VALUE_CHARS = 256
        private const val MAX_STACK_TRACE_CHARS = 16 * 1024
        private const val MAX_PENDING_ENTRIES = 1_024
        private const val CLOSE_TIMEOUT_SECONDS = 5L
        private val REDACTED_KEYS = setOf("key", "save", "path", "secret", "token")
        private val INVALID_EVENT_CHAR = Regex("[^A-Za-z0-9_.-]")
        private val INVALID_KEY_CHAR = Regex("[^A-Za-z0-9_.-]")

        private fun Throwable.stackTraceForLog(): String = generateSequence(this) { it.cause }
            .joinToString("\nCaused by: ") { throwable ->
                buildString {
                    append(throwable.javaClass.name)
                    throwable.stackTrace.forEach { frame -> append("\n\tat ").append(frame) }
                }
            }
            .take(MAX_STACK_TRACE_CHARS)
    }
}