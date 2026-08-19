package io.github.gdlbo.makerplay.feature.player.runtime

import io.github.gdlbo.makerplay.runtime.api.RuntimeEvent

internal enum class RendererFailure { CRASHED, STOPPED }

internal fun rendererFailure(activeSessionId: String?, event: RuntimeEvent): RendererFailure? {
    val rendererGone = event as? RuntimeEvent.RendererProcessGone ?: return null
    if (rendererGone.sessionId != activeSessionId) return null
    return if (rendererGone.didCrash) RendererFailure.CRASHED else RendererFailure.STOPPED
}