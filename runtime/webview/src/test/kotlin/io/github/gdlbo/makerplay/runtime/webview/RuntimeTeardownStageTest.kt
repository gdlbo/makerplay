package io.github.gdlbo.makerplay.runtime.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTeardownStageTest {
    @Test
    fun `teardown stages all run when an earlier stage fails`() {
        val order = mutableListOf<String>()
        val reported = mutableListOf<String>()

        runTeardownStage(
            step = "input",
            block = {
                order += "input"
                throw IllegalStateException("input teardown failed")
            },
            onFailure = { step, _ -> reported += step },
        )
        runTeardownStage(
            step = "bridges",
            block = { order += "bridges" },
            onFailure = { step, _ -> reported += step },
        )
        runTeardownStage(
            step = "destroy",
            block = { order += "destroy" },
            onFailure = { step, _ -> reported += step },
        )

        assertEquals(listOf("input", "bridges", "destroy"), order)
        assertEquals(listOf("input"), reported)
    }

    @Test
    fun `teardown failure never escapes to the caller`() {
        var failureReported = false

        runTeardownStage(
            step = "destroy",
            block = { error("destroy failed") },
            onFailure = { _, _ -> failureReported = true },
        )

        assertTrue(failureReported)
    }
}
