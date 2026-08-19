package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.runtime.webview.internal.web.RuntimeConsoleDeduplicator
import io.github.gdlbo.makerplay.runtime.webview.internal.web.runtimeConsoleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeWebChromeClientTest {
    @Test
    fun routesAllConsoleLevelsWithoutDroppingNonErrors() {
        val log = runtimeConsoleEntry("LOG", false, "loaded", "main.js", 7)
        val warning = runtimeConsoleEntry("WARNING", false, "slow", "main.js", 8)
        val error = runtimeConsoleEntry("ERROR", true, "failed", "main.js", 9)

        assertEquals("runtime.javascript.console", log.event)
        assertEquals("LOG", log.fields["level"])
        assertEquals("runtime.javascript.console", warning.event)
        assertEquals("WARNING", warning.fields["level"])
        assertEquals("runtime.javascript.error", error.event)
        assertEquals("ERROR", error.fields["level"])
    }

    @Test
    fun suppressesOnlyImmediateExactDuplicates() {
        var now = 1_000L
        val deduplicator = RuntimeConsoleDeduplicator(nowMillis = { now })
        val error = runtimeConsoleEntry("ERROR", true, "failed", "main.js", 9)

        assertTrue(deduplicator.shouldReport(error))
        now += 1
        assertFalse(deduplicator.shouldReport(error))
        assertTrue(deduplicator.shouldReport(error.copy(fields = error.fields + ("line" to "10"))))
        assertTrue(deduplicator.shouldReport(error))
        now += 101
        assertTrue(deduplicator.shouldReport(error))
    }
}
