package io.github.gdlbo.makerplay.runtime.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeWorkerCompatibilityTest {
    @Test
    fun workerBudgetBalancesCpuAndMemoryLimits() {
        assertEquals(1, RuntimeWorkerCompatibility.recommendedWorkerCount(8, 2048, true))
        assertEquals(1, RuntimeWorkerCompatibility.recommendedWorkerCount(8, 256, false))
        assertEquals(2, RuntimeWorkerCompatibility.recommendedWorkerCount(8, 512, false))
        assertEquals(3, RuntimeWorkerCompatibility.recommendedWorkerCount(8, 768, false))
        assertEquals(4, RuntimeWorkerCompatibility.recommendedWorkerCount(16, 1024, false))
        assertEquals(1, RuntimeWorkerCompatibility.recommendedWorkerCount(2, 2048, false))
    }

    @Test
    fun workerBudgetIsPublishedThroughTheStandardBrowserHint() {
        val script =
            RuntimeWorkerCompatibility.workerBudgetScript(runtimeAsset("worker-budget.js"), 2)

        assertTrue(script.contains("hardwareConcurrency"))
        assertTrue(script.contains("__makerplayRuntimeWorkerBudget"))
        assertTrue(script.endsWith("})(2);"))
    }
}
