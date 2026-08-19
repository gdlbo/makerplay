package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.runtime.webview.internal.assets.renderRuntimeScript

internal object RuntimeWorkerCompatibility {
    fun recommendedWorkerCount(
        availableProcessors: Int,
        memoryClassMb: Int,
        lowRamDevice: Boolean,
    ): Int {
        if (lowRamDevice) return 1
        val cpuBudget = RuntimeAsyncExecutor.workerCount(availableProcessors.coerceAtLeast(1))
        val memoryBudget = when {
            memoryClassMb < 384 -> 1
            memoryClassMb < 768 -> 2
            memoryClassMb < 1024 -> 3
            else -> 4
        }
        return minOf(cpuBudget, memoryBudget).coerceAtLeast(1)
    }

    fun workerBudgetScript(template: String, workerBudget: Int): String {
        require(workerBudget in 1..4) { "Worker budget must be between 1 and 4" }
        return renderRuntimeScript(
            template,
            "__MAKERPLAY_WORKER_BUDGET__" to workerBudget.toString(),
        )
    }
}