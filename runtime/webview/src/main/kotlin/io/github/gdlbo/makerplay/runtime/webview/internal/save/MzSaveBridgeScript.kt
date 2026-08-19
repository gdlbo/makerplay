package io.github.gdlbo.makerplay.runtime.webview.internal.save

import io.github.gdlbo.makerplay.runtime.webview.internal.assets.renderRuntimeScript

internal object MzSaveBridgeScript {
    const val OBJECT_NAME = "makerplaySaveNative"

    fun source(template: String): String = renderRuntimeScript(
        template,
        "__MAKERPLAY_OBJECT_NAME__" to OBJECT_NAME,
    )
}