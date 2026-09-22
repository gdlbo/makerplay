package io.github.gdlbo.makerplay.feature.player.runtime

import io.github.gdlbo.makerplay.runtime.api.RuntimeEvent

internal enum class RendererFailure { CRASHED, STOPPED, UNRESPONSIVE }

internal fun rendererFailure(activeSessionId: String?, event: RuntimeEvent): RendererFailure? {
    return when (event) {
        is RuntimeEvent.RendererProcessGone -> {
            if (event.sessionId != activeSessionId) return null
            if (event.didCrash) RendererFailure.CRASHED else RendererFailure.STOPPED
        }
        is RuntimeEvent.RendererUnresponsive -> {
            if (event.sessionId != activeSessionId) return null
            RendererFailure.UNRESPONSIVE
        }
        else -> null
    }
}

internal fun shouldFailRendererEvent(
    activeSessionId: String?,
    gameReady: Boolean,
    event: RuntimeEvent,
): RendererFailure? {
    val failure = rendererFailure(activeSessionId, event) ?: return null
    if (failure == RendererFailure.UNRESPONSIVE) return null
    return failure
}

internal fun isFatalJavaScriptBootError(message: String): Boolean {
    val text = message.trim()
    if (text.isEmpty()) return false
    return text.contains("Uncaught", ignoreCase = true) ||
        text.contains("SyntaxError", ignoreCase = true)
}