package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.runtime.webview.internal.bridge.RuntimeDiagnosticsBridge
import io.github.gdlbo.makerplay.runtime.webview.internal.bridge.WebGlContextEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeDiagnosticsBridgeTest {
    @Test
    fun parsesOnlyBoundedVersionedContextEvents() {
        assertEquals(
            WebGlContextEvent.LOST,
            RuntimeDiagnosticsBridge.parse("""{"v":1,"type":"lost"}"""),
        )
        assertEquals(
            WebGlContextEvent.RESTORED,
            RuntimeDiagnosticsBridge.parse("""{"v":1,"type":"restored"}"""),
        )
        listOf(
            "not-json",
            """{"v":2,"type":"lost"}""",
            """{"v":1,"type":"other"}""",
            "x".repeat(129),
        ).forEach { assertNull(RuntimeDiagnosticsBridge.parse(it)) }
    }
}
