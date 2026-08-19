package io.github.gdlbo.makerplay.runtime.webview

import java.io.File

internal fun runtimeAsset(path: String): String {
    val candidates = listOf(
        File("src/main/assets/runtime/$path"),
        File("runtime/webview/src/main/assets/runtime/$path"),
    )
    return candidates.firstOrNull(File::isFile)?.readText()
        ?: error("Runtime asset not found: $path")
}
