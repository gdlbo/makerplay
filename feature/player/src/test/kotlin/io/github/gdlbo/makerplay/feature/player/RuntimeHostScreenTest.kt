package io.github.gdlbo.makerplay.feature.player

import io.github.gdlbo.makerplay.feature.player.runtime.RendererFailure
import io.github.gdlbo.makerplay.feature.player.runtime.rendererFailure
import io.github.gdlbo.makerplay.feature.player.runtime.components.RuntimeFailureUi
import io.github.gdlbo.makerplay.feature.player.runtime.components.buildRuntimeFailureReport
import io.github.gdlbo.makerplay.runtime.api.RuntimeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
