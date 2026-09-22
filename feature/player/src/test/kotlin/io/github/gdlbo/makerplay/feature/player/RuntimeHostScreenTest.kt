package io.github.gdlbo.makerplay.feature.player

import androidx.compose.ui.unit.IntSize
import io.github.gdlbo.makerplay.feature.player.runtime.RendererFailure
import io.github.gdlbo.makerplay.feature.player.runtime.rendererFailure
import io.github.gdlbo.makerplay.feature.player.runtime.isFatalJavaScriptBootError
import io.github.gdlbo.makerplay.feature.player.runtime.shouldFailRendererEvent
import io.github.gdlbo.makerplay.feature.player.runtime.shouldHostRuntimeOverlayPopup
import io.github.gdlbo.makerplay.feature.player.runtime.components.RuntimeFailureUi
import io.github.gdlbo.makerplay.feature.player.runtime.components.buildRuntimeFailureReport
import io.github.gdlbo.makerplay.runtime.api.RuntimeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeHostScreenTest {
    @Test
    fun rendererFailureIsAcceptedOnlyForTheActiveSession() {
        assertNull(
            rendererFailure(
                "active",
                RuntimeEvent.RendererProcessGone("stale", didCrash = true),
            ),
        )
        assertEquals(
            RendererFailure.CRASHED,
            rendererFailure(
                "active",
                RuntimeEvent.RendererProcessGone("active", didCrash = true),
            ),
        )
        assertEquals(
            RendererFailure.STOPPED,
            rendererFailure(
                "active",
                RuntimeEvent.RendererProcessGone("active", didCrash = false),
            ),
        )
        assertEquals(
            RendererFailure.UNRESPONSIVE,
            rendererFailure(
                "active",
                RuntimeEvent.RendererUnresponsive("active"),
            ),
        )
        assertNull(
            rendererFailure(
                "active",
                RuntimeEvent.RendererUnresponsive("stale"),
            ),
        )
    }

    @Test
    fun unresponsiveRendererDoesNotFailTheSession() {
        val event = RuntimeEvent.RendererUnresponsive("active")
        assertNull(shouldFailRendererEvent("active", gameReady = false, event = event))
        assertNull(shouldFailRendererEvent("stale", gameReady = false, event = event))
        assertNull(shouldFailRendererEvent("active", gameReady = true, event = event))
        assertEquals(
            RendererFailure.CRASHED,
            shouldFailRendererEvent(
                "active",
                gameReady = true,
                event = RuntimeEvent.RendererProcessGone("active", didCrash = true),
            ),
        )
    }

    @Test
    fun uncaughtJavascriptErrorsFailBootButPluginLogsDoNot() {
        assertTrue(
            isFatalJavaScriptBootError(
                "Uncaught SyntaxError: Unexpected end of JSON input",
            ),
        )
        assertTrue(isFatalJavaScriptBootError("Uncaught TypeError: undefined"))
        assertFalse(isFatalJavaScriptBootError("Failed to load image"))
        assertFalse(isFatalJavaScriptBootError(""))
    }

    @Test
    fun overlayPopupUsedOnlyForNonWebViewBackends() {
        val viewport = IntSize(1920, 1080)
        assertTrue(
            shouldHostRuntimeOverlayPopup(
                backendId = "wolf-native",
                gameReady = true,
                runtimeViewport = viewport,
                imeVisible = false,
            ),
        )
        assertFalse(
            shouldHostRuntimeOverlayPopup(
                backendId = "webview",
                gameReady = true,
                runtimeViewport = viewport,
                imeVisible = false,
            ),
        )
        assertFalse(
            shouldHostRuntimeOverlayPopup(
                backendId = "wolf-native",
                gameReady = true,
                runtimeViewport = viewport,
                imeVisible = true,
            ),
        )
        assertFalse(
            shouldHostRuntimeOverlayPopup(
                backendId = "wolf-native",
                gameReady = false,
                runtimeViewport = viewport,
                imeVisible = false,
            ),
        )
        assertFalse(
            shouldHostRuntimeOverlayPopup(
                backendId = "wolf-native",
                gameReady = true,
                runtimeViewport = IntSize.Zero,
                imeVisible = false,
            ),
        )
    }

    @Test
    fun copiedFailureReportContainsReasonDetailsAndRuntimeLog() {
        val report = buildRuntimeFailureReport(
            failure = RuntimeFailureUi(
                title = "Game stopped",
                reason = "Renderer crashed",
                technicalDetails = "WebView process exited",
            ),
            logs = "runtime.renderer_gone",
            technicalDetailsLabel = "Technical details",
            logsLabel = "Runtime logs",
            logsUnavailable = "No logs",
        )

        assertEquals(
            """
                Game stopped
                Renderer crashed

                Technical details
                WebView process exited

                Runtime logs
                runtime.renderer_gone
            """.trimIndent(),
            report,
        )
    }
}
