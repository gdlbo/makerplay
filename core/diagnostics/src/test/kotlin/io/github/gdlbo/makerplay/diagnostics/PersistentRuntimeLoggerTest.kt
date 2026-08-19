package io.github.gdlbo.makerplay.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PersistentRuntimeLoggerTest {
    @Test
    fun startsEachRunWithFreshRedactedStructuredLog() {
        val root = Files.createTempDirectory("makerplay-diagnostics").toFile()
        try {
            PersistentRuntimeLogger(root, NoOpLogger, clockMillis = { 42L }).use { logger ->
                logger.info(
                    "runtime.renderer_gone",
                    mapOf("sessionId" to "session-1", "savePath" to "/private/save"),
                )
                logger.error("runtime.failure", IllegalStateException("private payload"))
            }
            PersistentRuntimeLogger(root, NoOpLogger, clockMillis = { 43L }).use { logger ->
                logger.info("runtime.webgl_context_restored", emptyMap())
            }

            val lines =
                Files.readAllLines(root.resolve(PersistentRuntimeLogger.CURRENT_FILE).toPath())
            assertEquals(1, lines.size)
            val first = Json.parseToJsonElement(lines.first()).jsonObject
            assertEquals(
                "runtime.webgl_context_restored",
                first.getValue("event").jsonPrimitive.content
            )
            val persisted = lines.joinToString("\n")
            assertFalse(persisted.contains("/private/save"))
            assertFalse(persisted.contains("private payload"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun errorIncludesStackWithoutExceptionMessage() {
        val root = Files.createTempDirectory("makerplay-diagnostics-crash").toFile()
        try {
            PersistentRuntimeLogger(root, NoOpLogger, clockMillis = { 45L }).use { logger ->
                logger.error("application.crash", IllegalStateException("private payload"))
            }

            val persisted =
                Files.readAllLines(root.resolve(PersistentRuntimeLogger.CURRENT_FILE).toPath())
                    .joinToString("\n")
            assertTrue(persisted.contains("application.crash"))
            assertTrue(persisted.contains("PersistentRuntimeLoggerTest.errorIncludesStackWithoutExceptionMessage"))
            assertFalse(persisted.contains("private payload"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun redactsSensitiveSuffixesAfterKeyTruncation() {
        val root = Files.createTempDirectory("makerplay-diagnostics-redaction").toFile()
        try {
            PersistentRuntimeLogger(root, NoOpLogger, clockMillis = { 44L }).use { logger ->
                logger.info("runtime.event", mapOf("x".repeat(60) + "token" to "secret-value"))
            }
            val persisted =
                Files.readAllLines(root.resolve(PersistentRuntimeLogger.CURRENT_FILE).toPath())
                    .joinToString("\n")
            assertFalse(persisted.contains("secret-value"))
            assertTrue(persisted.contains("[REDACTED]"))
        } finally {
            root.deleteRecursively()
        }
    }

    private object NoOpLogger : RuntimeLogger {
        override fun info(event: String, fields: Map<String, String>) = Unit
        override fun error(event: String, throwable: Throwable) = Unit
    }
}