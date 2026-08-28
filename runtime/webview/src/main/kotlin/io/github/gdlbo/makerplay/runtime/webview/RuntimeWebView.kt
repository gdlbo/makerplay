package io.github.gdlbo.makerplay.runtime.webview

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewRenderProcessClient
import io.github.gdlbo.makerplay.input.LogicalInputSnapshot
import io.github.gdlbo.makerplay.runtime.api.CheatCatalog
import io.github.gdlbo.makerplay.runtime.api.CheatCommand
import io.github.gdlbo.makerplay.runtime.api.CheatFlags
import io.github.gdlbo.makerplay.runtime.api.CheatOperation
import io.github.gdlbo.makerplay.runtime.api.RuntimeProfile
import io.github.gdlbo.makerplay.runtime.api.RuntimeSettings
import io.github.gdlbo.makerplay.runtime.webview.internal.assets.RuntimeScriptAssets
import io.github.gdlbo.makerplay.runtime.webview.internal.bridge.RuntimeCheatBridge
import io.github.gdlbo.makerplay.runtime.webview.internal.bridge.RuntimeDiagnosticsBridge
import io.github.gdlbo.makerplay.runtime.webview.internal.bridge.WebGlContextEvent
import io.github.gdlbo.makerplay.runtime.webview.internal.input.EMPTY_INPUT
import io.github.gdlbo.makerplay.runtime.webview.internal.input.RuntimeInputFrameBridge
import io.github.gdlbo.makerplay.runtime.webview.internal.input.RuntimeInputMixer
import io.github.gdlbo.makerplay.runtime.webview.internal.input.RuntimeInputWebView
import io.github.gdlbo.makerplay.runtime.webview.internal.lifecycle.RuntimeAudioFocusController
import io.github.gdlbo.makerplay.runtime.webview.internal.lifecycle.RuntimeWebViewLifecycle
import io.github.gdlbo.makerplay.runtime.webview.internal.web.RuntimeWebChromeClient
import io.github.gdlbo.makerplay.runtime.webview.internal.web.RuntimeWebViewClient
import io.github.gdlbo.makerplay.runtime.webview.internal.web.origin
import java.util.IdentityHashMap

@SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
@Composable
fun RuntimeWebView(
    startUrl: String,
    responder: GameOriginResponder?,
    commonJs: CommonJsRuntimeConfiguration? = null,
    saveBridge: RuntimeSaveBridgeConfiguration? = null,
    onRuntimeError: (String, Map<String, String>) -> Unit = { _, _ -> },
    onRendererGone: (Boolean) -> Unit = {},
    onCloseRequested: () -> Unit = {},
    onWebGlContextChanged: (Boolean) -> Unit = {},
    onPhysicalInputChanged: (LogicalInputSnapshot) -> Unit = {},
    onCheatAvailabilityChanged: (Boolean) -> Unit = {},
    inputEnabled: Boolean = true,
    virtualInput: LogicalInputSnapshot = EMPTY_INPUT,
    cheatFlags: CheatFlags = CheatFlags(),
    cheatCommand: CheatCommand? = null,
    onCheatCommandConsumed: (Long) -> Unit = {},
    onCheatCatalogChanged: (CheatCatalog) -> Unit = {},
    onReadyChanged: (Boolean) -> Unit = {},
    runtimeSettings: RuntimeSettings = RuntimeSettings(),
    runtimeProfile: RuntimeProfile? = null,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // The resolved profile is the authoritative pre-WebView settings snapshot.
    val profileSettings = runtimeProfile?.settings ?: runtimeSettings
    val latestRendererGone = rememberUpdatedState(onRendererGone)
    val latestCloseRequested = rememberUpdatedState(onCloseRequested)
    val latestWebGlContextChanged = rememberUpdatedState(onWebGlContextChanged)
    val latestPhysicalInputChanged = rememberUpdatedState(onPhysicalInputChanged)
    val latestVirtualInput = rememberUpdatedState(virtualInput)
    val latestCheatAvailability = rememberUpdatedState(onCheatAvailabilityChanged)
    val latestCheatFlags = rememberUpdatedState(cheatFlags)
    val latestCheatCommand = rememberUpdatedState(cheatCommand)
    val latestCheatCommandConsumed = rememberUpdatedState(onCheatCommandConsumed)
    val latestCheatCatalogChanged = rememberUpdatedState(onCheatCatalogChanged)
    val latestReadyChanged = rememberUpdatedState(onReadyChanged)
    val appliedCheatSequence = remember { mutableStateOf<Long?>(null) }
    val appliedCheatFlags =
        remember(startUrl, profileSettings) { mutableStateOf<CheatFlags?>(null) }
    val pageReady = remember(startUrl, profileSettings) { mutableStateOf(false) }
    val cheatSessions = remember { IdentityHashMap<WebView, RuntimeCheatBridge.Session>() }
    val inputMixers = remember { IdentityHashMap<WebView, RuntimeInputMixer>() }
    val lifecycleControllers = remember { IdentityHashMap<WebView, RuntimeWebViewLifecycle>() }
    val lifecycleObservers = remember { IdentityHashMap<WebView, LifecycleEventObserver>() }
    DisposableEffect(lifecycleOwner, startUrl, profileSettings) {
        onDispose {
            lifecycleObservers.values.toList().forEach(lifecycleOwner.lifecycle::removeObserver)
            lifecycleControllers.values.toList().forEach(RuntimeWebViewLifecycle::onRelease)
            lifecycleObservers.clear()
            lifecycleControllers.clear()
        }
    }
    key(startUrl, profileSettings, runtimeProfile) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                val baseScripts = RuntimeScriptAssets.load(context.assets)
                val runtimeScripts = baseScripts.copy(
                    layout = profileSettings.configScript(baseScripts.disableVibration) + baseScripts.layout,
                    steamCompatibility = baseScripts.steamCompatibility.takeIf {
                        profileSettings.modules.steamCompatibility
                    }.orEmpty(),
                    legacyCompatibility = baseScripts.legacyCompatibility.takeIf {
                        profileSettings.legacyCompatibility
                    }.orEmpty(),
                    performanceOptimization = baseScripts.performanceOptimization.takeIf {
                        profileSettings.modules.performanceOptimization
                    }.orEmpty(),
                    visualBoosts = baseScripts.visualBoosts.takeIf {
                        profileSettings.modules.visualBoosts
                    }.orEmpty(),
                    frameRate = baseScripts.frameRate.takeIf {
                        profileSettings.fpsLimit != null || profileSettings.showFpsCounter
                    }.orEmpty(),
                )
                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                    .build()
                RuntimeInputWebView(context).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setBackgroundColor(Color.BLACK)
                    setLayerType(
                        if (profileSettings.webGlEnabled) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_SOFTWARE,
                        null,
                    )
                    setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
                    settings.javaScriptEnabled = true
                    settings.offscreenPreRaster = false
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE)) {
                        var rendererUnresponsive = false
                        WebViewCompat.setWebViewRenderProcessClient(
                            this,
                            object : WebViewRenderProcessClient() {
                                override fun onRenderProcessUnresponsive(
                                    view: WebView,
                                    renderer: androidx.webkit.WebViewRenderProcess?,
                                ) {
                                    if (rendererUnresponsive) return
                                    rendererUnresponsive = true
                                    onRuntimeError(
                                        "runtime.renderer_unresponsive",
                                        mapOf("rendererAvailable" to (renderer != null).toString()),
                                    )
                                }

                                override fun onRenderProcessResponsive(
                                    view: WebView,
                                    renderer: androidx.webkit.WebViewRenderProcess?,
                                ) {
                                    if (!rendererUnresponsive) return
                                    rendererUnresponsive = false
                                    onRuntimeError(
                                        "runtime.renderer_responsive",
                                        mapOf("rendererAvailable" to (renderer != null).toString()),
                                    )
                                }
                            },
                        )
                    }
                    val allowedOrigin = Uri.parse(startUrl).origin()
                    val commonJsAttachment = commonJs?.let {
                        check(it.allowedOrigin == allowedOrigin) { "CommonJS origin does not match the runtime page" }
                        val attachment =
                            RuntimeCommonJsBridge.install(this, it, runtimeScripts.commonJs)
                        val activityManager = context.getSystemService(ActivityManager::class.java)
                        val workerBudget = RuntimeWorkerCompatibility.recommendedWorkerCount(
                            availableProcessors = Runtime.getRuntime().availableProcessors(),
                            memoryClassMb = activityManager?.memoryClass ?: 512,
                            lowRamDevice = activityManager?.isLowRamDevice == true,
                        )
                        if (profileSettings.modules.limitWorkerCount) {
                            WebViewCompat.addDocumentStartJavaScript(
                                this,
                                RuntimeWorkerCompatibility.workerBudgetScript(
                                    baseScripts.workerBudget,
                                    workerBudget
                                ),
                                setOf(allowedOrigin),
                            )
                        }
                        attachment
                    }
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        WebViewCompat.addDocumentStartJavaScript(
                            this,
                            runtimeScripts.layout,
                            setOf(allowedOrigin),
                        )
                        if (runtimeScripts.steamCompatibility.isNotEmpty()) {
                            WebViewCompat.addDocumentStartJavaScript(
                                this,
                                runtimeScripts.steamCompatibility,
                                setOf(allowedOrigin),
                            )
                        }
                        if (runtimeScripts.legacyCompatibility.isNotEmpty()) {
                            WebViewCompat.addDocumentStartJavaScript(
                                this,
                                runtimeScripts.legacyCompatibility,
                                setOf(allowedOrigin),
                            )
                        }
                        if (runtimeScripts.frameRate.isNotEmpty()) {
                            WebViewCompat.addDocumentStartJavaScript(
                                this,
                                runtimeScripts.frameRate,
                                setOf(allowedOrigin),
                            )
                        }
                        if (runtimeScripts.frameResilience.isNotEmpty()) {
                            WebViewCompat.addDocumentStartJavaScript(
                                this,
                                runtimeScripts.frameResilience,
                                setOf(allowedOrigin),
                            )
                        }
                        WebViewCompat.addDocumentStartJavaScript(
                            this,
                            runtimeScripts.performanceOptimization,
                            setOf(allowedOrigin),
                        )
                        if (runtimeScripts.visualBoosts.isNotEmpty()) {
                            WebViewCompat.addDocumentStartJavaScript(
                                this,
                                runtimeScripts.visualBoosts,
                                setOf(allowedOrigin),
                            )
                        }
                    }
                    val inputBridge = RuntimeInputFrameBridge.install(
                        this,
                        allowedOrigin,
                        runtimeScripts.inputBridge
                    )
                    val inputMixer = inputBridge?.let { RuntimeInputMixer(it::submit) }
                    inputMixer?.let { inputMixers[this] = it }
                    setOnPhysicalInputChanged { snapshot ->
                        latestPhysicalInputChanged.value(snapshot)
                        inputMixer?.setPhysical(snapshot)
                    }
                    if (profileSettings.modules.diagnosticsBridge) {
                        RuntimeDiagnosticsBridge.install(
                            this,
                            allowedOrigin,
                            runtimeScripts.diagnosticsBridge
                        ) { event ->
                            latestWebGlContextChanged.value(event == WebGlContextEvent.RESTORED)
                        }
                    }
                    val cheatSession = if (profileSettings.modules.cheatBridge) {
                        RuntimeCheatBridge.install(
                            this,
                            allowedOrigin,
                            runtimeScripts.cheatBridge
                        ) { catalog ->
                            latestCheatCatalogChanged.value(catalog)
                        }
                    } else null
                    cheatSession?.let {
                        cheatSessions[this] = cheatSession
                        post { latestCheatAvailability.value(true) }
                    } ?: post { latestCheatAvailability.value(false) }
                    val saveBridgeAttachment =
                        saveBridge?.let {
                            RuntimeSaveBridge.install(
                                this,
                                it,
                                runtimeScripts.mvSaveBridge,
                                runtimeScripts.mzSaveBridge,
                            )
                        }
                    lateinit var lifecycle: RuntimeWebViewLifecycle
                    lateinit var audioFocus: RuntimeAudioFocusController
                    lifecycle = RuntimeWebViewLifecycle(
                        pause = {
                            inputMixer?.setPlatformActive(false)
                            clearPhysicalInput()
                            if (profileSettings.pauseOnBackground) onPause()
                            audioFocus.abandon()
                        },
                        resume = {
                            inputMixer?.setPlatformActive(true)
                            audioFocus.request()
                            if (profileSettings.pauseOnBackground) onResume()
                        },
                        release = {
                            clearPhysicalInput()
                            inputBridge?.close()
                            inputMixers.remove(this)
                            audioFocus.abandon()
                            stopLoading()
                            RuntimeDiagnosticsBridge.uninstall(this)
                            RuntimeCheatBridge.uninstall(this)
                            commonJsAttachment?.let { RuntimeCommonJsBridge.uninstall(this, it) }
                            saveBridgeAttachment?.let { RuntimeSaveBridge.uninstall(this, it) }
                            webViewClient = WebViewClient()
                            webChromeClient = WebChromeClient()
                            destroy()
                        },
                    )
                    // Game audio inside the WebView can steal audio focus from the host.
                    // Keep requesting focus for media routing, but do not gate input or
                    // pause rendering on transient focus loss; lifecycle handles backgrounding.
                    audioFocus = RuntimeAudioFocusController.create(context) { _ -> }
                    lifecycleControllers[this] = lifecycle
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> lifecycle.onResume()
                            Lifecycle.Event.ON_PAUSE,
                            Lifecycle.Event.ON_STOP,
                                -> lifecycle.onPause()

                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    lifecycleObservers[this] = observer
                    if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        lifecycle.onPause()
                    } else {
                        audioFocus.request()
                    }
                    webChromeClient = RuntimeWebChromeClient(
                        onRuntimeError = onRuntimeError,
                        onCloseRequested = { latestCloseRequested.value() },
                    )
                    webViewClient = RuntimeWebViewClient(
                        startUrl = startUrl,
                        responder = responder,
                        assetLoader = assetLoader,
                        runtimeScripts = runtimeScripts,
                        networkFallbacks = runtimeScripts.networkFallbacks,
                        onRuntimeError = onRuntimeError,
                        onPageStarted = {
                            pageReady.value = false
                            latestReadyChanged.value(false)
                            appliedCheatFlags.value = null
                        },
                        onPageReady = { view ->
                            pageReady.value = true
                            latestReadyChanged.value(true)
                            cheatSessions[view]?.let { cheatSession ->
                                val flags = latestCheatFlags.value
                                RuntimeCheatBridge.apply(
                                    view,
                                    cheatSession,
                                    CheatCommand(
                                        Long.MIN_VALUE,
                                        CheatOperation.SetFlags(
                                            godMode = flags.godMode,
                                            infiniteHp = flags.infiniteHp,
                                            infiniteMp = flags.infiniteMp,
                                            playerSpeedMultiplier = flags.playerSpeedMultiplier,
                                            noClip = flags.noClip,
                                        ),
                                    ),
                                )
                                appliedCheatFlags.value = flags
                                latestCheatCommand.value?.takeIf { it.sequence != appliedCheatSequence.value }
                                    ?.let {
                                        RuntimeCheatBridge.apply(view, cheatSession, it)
                                        appliedCheatSequence.value = it.sequence
                                        latestCheatCommandConsumed.value(it.sequence)
                                    }
                            }
                        },
                        onRendererGone = { view, didCrash ->
                            latestRendererGone.value(didCrash)
                            lifecycleObservers.remove(view)
                                ?.let(lifecycleOwner.lifecycle::removeObserver)
                            lifecycleControllers.remove(view)?.onRelease()
                            cheatSessions.remove(view)
                        },
                    )
                    loadUrl(startUrl)
                    post { requestFocus() }
                }
            },
            update = { webView ->
                webView.isEnabled = inputEnabled
                inputMixers[webView]?.let {
                    it.setUiEnabled(inputEnabled)
                    it.setVirtual(latestVirtualInput.value)
                }
                if (!inputEnabled) webView.clearPhysicalInput()
                if (!pageReady.value) return@AndroidView
                cheatSessions[webView]?.let { cheatSession ->
                    val flags = latestCheatFlags.value
                    if (flags != appliedCheatFlags.value) {
                        RuntimeCheatBridge.apply(
                            webView,
                            cheatSession,
                            CheatCommand(
                                Long.MIN_VALUE,
                                CheatOperation.SetFlags(
                                    godMode = flags.godMode,
                                    infiniteHp = flags.infiniteHp,
                                    infiniteMp = flags.infiniteMp,
                                    playerSpeedMultiplier = flags.playerSpeedMultiplier,
                                    noClip = flags.noClip,
                                ),
                            ),
                        )
                        appliedCheatFlags.value = flags
                    }
                    latestCheatCommand.value?.takeIf { it.sequence != appliedCheatSequence.value }
                        ?.let {
                            RuntimeCheatBridge.apply(webView, cheatSession, it)
                            appliedCheatSequence.value = it.sequence
                            latestCheatCommandConsumed.value(it.sequence)
                        }
                }
            },
            onRelease = { webView ->
                lifecycleObservers.remove(webView)?.let(lifecycleOwner.lifecycle::removeObserver)
                lifecycleControllers.remove(webView)?.onRelease()
                inputMixers.remove(webView)
                cheatSessions.remove(webView)
            },
        )
    }
}

internal fun RuntimeSettings.configScript(disableVibrationScript: String): String =
    "globalThis.__makerplayRuntimeConfig={" +
            "scaleMode:'${scaleMode.name}'," +
            "pixelSmoothing:${pixelSmoothing}," +
            "fpsLimit:${fpsLimit ?: "null"}," +
            "showFpsCounter:${showFpsCounter}," +
            "vibrationEnabled:${vibrationEnabled}" +
            "};\n" +
            if (vibrationEnabled) "" else disableVibrationScript