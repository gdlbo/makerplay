package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.runtime.webview.internal.web.reportRuntimeHttpFailure
import io.github.gdlbo.makerplay.runtime.webview.internal.web.runtimeResourceFailureFields
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeResourceDiagnosticsTest {
    @Test
    fun `fallback HTTP callback emits actionable entry and resource events`() {
        for (mainFrame in listOf(true, false)) {
            val emitted = mutableListOf<Pair<String, Map<String, String>>>()
            reportRuntimeHttpFailure(
                { name, fields -> emitted += name to fields },
                mapOf("url" to "https://game.test/missing/index.html"),
                mainFrame, 404, "Not Found",
            )
            assertEquals(1, emitted.size)
            val (event, fields) = emitted.single()
            assertEquals("runtime.resource.http_error", event)
            assertEquals("https://game.test/missing/index.html", fields["url"])
            assertEquals("404", fields["statusCode"])
            assertEquals("Not Found", fields["reason"])
            assertEquals("resource-loading", fields["stage"])
            assertEquals(if (mainFrame) "entry-load-failed" else "resource-load-failed", fields["action"])
        }
    }

    @Test
    fun `missing entry path reports stage cause and blocked action`() {
        val fields = mapOf("url" to "https://game.test/missing/index.html") +
            runtimeResourceFailureFields(true, mapOf("statusCode" to "404", "reason" to "Not Found"))
        assertEquals("https://game.test/missing/index.html", fields["url"])
        assertEquals("404", fields["statusCode"])
        assertEquals("Not Found", fields["reason"])
        assertEquals("resource-loading", fields["stage"])
        assertEquals("entry-load-failed", fields["action"])
    }

    @Test
    fun `opening failure reports class and resource action without exception values`() {
        val fields = runtimeResourceFailureFields(false, mapOf("failureClass" to "java.io.IOException"))
        assertEquals("java.io.IOException", fields["failureClass"])
        assertEquals("resource-load-failed", fields["action"])
        assertEquals("resource-loading", fields["stage"])
        assertEquals(3, fields.size)
    }
}
