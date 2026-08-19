package io.github.gdlbo.makerplay.runtime.webview.internal.save

import io.github.gdlbo.makerplay.runtime.webview.internal.assets.renderRuntimeScript
import kotlinx.serialization.json.JsonPrimitive

internal object MvSaveBridgeScript {
    const val OBJECT_NAME = "makerplayMvSaveNative"

    fun source(template: String, token: String): String = renderRuntimeScript(
        template,
        "__MAKERPLAY_OBJECT_NAME__" to OBJECT_NAME,
        "__MAKERPLAY_SESSION_TOKEN__" to JsonPrimitive(token).toString(),
    )
}