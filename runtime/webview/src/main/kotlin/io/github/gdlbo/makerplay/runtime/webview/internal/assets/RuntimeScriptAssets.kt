package io.github.gdlbo.makerplay.runtime.webview.internal.assets

import android.content.res.AssetManager

internal data class RuntimeScripts(
    val layout: String,
    val steamCompatibility: String,
    val legacyCompatibility: String,
    val frameResilience: String,
    val performanceOptimization: String,
    val visualBoosts: String,
    val networkFallbacks: List<RuntimeNetworkFallback>,
    val frameRate: String,
    val commonJs: String,
    val workerBudget: String,
    val disableVibration: String,
    val diagnosticsBridge: String,
    val cheatBridge: String,
    val inputBridge: String,
    val mvSaveBridge: String,
    val mzSaveBridge: String,
)

internal data class RuntimeNetworkFallback(
    val scheme: String,
    val host: String,
    val path: String,
    val mimeType: String,
    val body: ByteArray,
)

internal object RuntimeScriptAssets {
    @Volatile
    private var cached: RuntimeScripts? = null

    fun load(assets: AssetManager): RuntimeScripts = cached ?: synchronized(this) {
        cached ?: RuntimeScripts(
            layout = assets.readUtf8("runtime/runtime-layout.js"),
            steamCompatibility = assets.readUtf8("runtime/steam-compatibility.js"),
            legacyCompatibility = assets.readUtf8("runtime/legacy-compatibility.js"),
            frameResilience = assets.readUtf8("runtime/frame-resilience.js"),
            performanceOptimization = assets.readUtf8("runtime/performance-optimization.js"),
            visualBoosts = assets.readUtf8("runtime/visual-boosts.js"),
            networkFallbacks = NETWORK_FALLBACK_ASSETS.map { asset ->
                RuntimeNetworkFallback(
                    scheme = asset.scheme,
                    host = asset.host,
                    path = asset.path,
                    mimeType = asset.mimeType,
                    body = assets.readUtf8(asset.assetPath).toByteArray(Charsets.UTF_8),
                )
            },
            frameRate = assets.readUtf8("runtime/frame-rate.js"),
            commonJs = COMMON_JS_PARTS.joinToString(separator = "") { assets.readUtf8(it) },
            workerBudget = assets.readUtf8("runtime/worker-budget.js"),
            disableVibration = assets.readUtf8("runtime/disable-vibration.js"),
            diagnosticsBridge = assets.readUtf8("runtime/bridges/diagnostics-bridge.js"),
            cheatBridge = assets.readUtf8("runtime/bridges/cheat-bridge.js"),
            inputBridge = assets.readUtf8("runtime/bridges/input-bridge.js"),
            mvSaveBridge = assets.readUtf8("runtime/bridges/mv-save-bridge.js"),
            mzSaveBridge = assets.readUtf8("runtime/bridges/mz-save-bridge.js"),
        ).also { cached = it }
    }

    private fun AssetManager.readUtf8(path: String): String =
        open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private val COMMON_JS_PARTS = listOf(
        "runtime/commonjs/00-bridge-buffer-path.js",
        "runtime/commonjs/10-events-process.js",
        "runtime/commonjs/20-fs.js",
        "runtime/commonjs/30-builtins-nw.js",
        "runtime/commonjs/40-loader.js",
    )

    private val NETWORK_FALLBACK_ASSETS = listOf(
        NetworkFallbackAsset(
            scheme = "https",
            host = "cdn.rangetouch.com",
            path = "/2.0.1/rangetouch.js",
            mimeType = "application/javascript",
            assetPath = "runtime/rangetouch-2.0.1.js",
        ),
    )

    private data class NetworkFallbackAsset(
        val scheme: String,
        val host: String,
        val path: String,
        val mimeType: String,
        val assetPath: String,
    )
}

internal fun renderRuntimeScript(
    template: String,
    vararg replacements: Pair<String, String>,
): String {
    var rendered = template
    replacements.forEach { (marker, value) ->
        require(rendered.contains(marker)) { "Missing runtime script marker: $marker" }
        rendered = rendered.replace(marker, value)
    }
    require(!RUNTIME_SCRIPT_MARKER.containsMatchIn(rendered)) {
        "Unresolved runtime script marker"
    }
    return rendered.trimEnd()
}

private val RUNTIME_SCRIPT_MARKER = Regex("__MAKERPLAY_[A-Z_]+__")