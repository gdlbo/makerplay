package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.runtime.api.DeploymentLayout
import io.github.gdlbo.makerplay.runtime.api.EngineFingerprint
import io.github.gdlbo.makerplay.runtime.api.FingerprintEngine
import io.github.gdlbo.makerplay.runtime.api.FingerprintValue
import io.github.gdlbo.makerplay.runtime.api.PackageMetadata
import io.github.gdlbo.makerplay.runtime.api.RequirementStatus
import io.github.gdlbo.makerplay.runtime.api.RuntimeEngineMode
import io.github.gdlbo.makerplay.runtime.api.RuntimeProfile
import io.github.gdlbo.makerplay.runtime.api.RuntimeSettings
import io.github.gdlbo.makerplay.vfs.GameFileSystem
import io.github.gdlbo.makerplay.vfs.VfsOpenResult
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class DeploymentInspector(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun inspect(fileSystem: GameFileSystem): EngineFingerprint =
        withTimeout(INSPECTION_TIMEOUT_MILLIS) {
            withContext(INSPECTION_DISPATCHER) { inspectBlocking(fileSystem) }
        }

    private suspend fun inspectBlocking(fileSystem: GameFileSystem): EngineFingerprint {
        val root = probe(fileSystem, "index.html")
        val www = probe(fileSystem, "www/index.html")
        val prefix = when {
            root.found -> ""
            www.found -> "www/"
            else -> ""
        }
        val layout = when {
            root.found -> DeploymentLayout.ROOT
            www.found -> DeploymentLayout.WWW
            else -> DeploymentLayout.UNKNOWN
        }
        val mzCore = read(fileSystem, "${prefix}js/rmmz_core.js")
        val mvCore = read(fileSystem, "${prefix}js/rpg_core.js")
        val engine = when {
            mzCore.text != null -> FingerprintEngine.MZ
            mvCore.text != null -> FingerprintEngine.MV
            else -> FingerprintEngine.UNKNOWN
        }
        val core = mzCore.text ?: mvCore.text
        val pluginManifest = read(fileSystem, "${prefix}js/plugins.js")
        val plugins = parsePlugins(pluginManifest.text)
        val pluginManifestUnknown = pluginManifest.found && !isReliablePluginManifest(pluginManifest.text)
        val pluginSources = plugins.take(MAX_PLUGIN_FILES).mapNotNull { name ->
            coroutineContext.ensureActive()
            read(fileSystem, "${prefix}js/plugins/$name.js").text
        }
        val packageResult = read(fileSystem, "${prefix}package.json")
        val inspectedSources = listOfNotNull(core, packageResult.text) + pluginSources
        return EngineFingerprint(
            engine = engine,
            deploymentLayout = layout,
            coreVersion = core.versionValue(VERSION_PATTERN),
            pixiMajor = core.pixiMajorValue(),
            plugins = io.github.gdlbo.makerplay.runtime.api.ImmutableList.copyOf(plugins),
            requiredGlobals = io.github.gdlbo.makerplay.runtime.api.ImmutableSet.copyOf(inspectedSources.flatMap { REQUIRED_GLOBAL_PATTERN.findAll(it).map { match -> match.groupValues[1] }.toList() }.toSet()),
            storageStyle = inspectedSources.requirement(STORAGE_PATTERN, pluginManifestUnknown),
            mzNativeSaves = if (supportsMzNativeSaves(fileSystem)) RequirementStatus.REQUIRED else RequirementStatus.NOT_DETECTED,
            mvNativeSaves = if (supportsMvNativeSaves(fileSystem)) RequirementStatus.REQUIRED else RequirementStatus.NOT_DETECTED,
            packageMetadata = packageResult.text?.let(::parsePackageMetadata),
            commonJs = inspectedSources.requirement(COMMON_JS_PATTERN, pluginManifestUnknown),
            nwJs = inspectedSources.requirement(NW_JS_PATTERN, pluginManifestUnknown),
            nativeAddons = inspectedSources.requirement(NATIVE_ADDON_PATTERN, pluginManifestUnknown),
            unsupportedProcessApis = inspectedSources.requirement(UNSUPPORTED_PROCESS_PATTERN, pluginManifestUnknown),
        )
    }

    private fun probe(fileSystem: GameFileSystem, path: String): ReadResult {
        if (fileSystem.resolve(path) != null) return ReadResult(found = true)
        return ReadResult(found = false)
    }

    private suspend fun read(fileSystem: GameFileSystem, path: String): ReadResult {
        coroutineContext.ensureActive()
        nativeRead(fileSystem, path)?.let { bytes ->
            return if (bytes.size > MAX_METADATA_BYTES) {
                ReadResult(found = true)
            } else {
                ReadResult(found = true, text = String(bytes, StandardCharsets.UTF_8))
            }
        }
        val result = fileSystem.open(path)
        if (result !is VfsOpenResult.Found) return ReadResult(found = false)
        result.stream.use { stream ->
            coroutineContext.ensureActive()
            if (result.contentLength > MAX_METADATA_BYTES) return ReadResult(found = true)
            val bytes = stream.readNBytes(MAX_METADATA_BYTES + 1)
            return if (bytes.size > MAX_METADATA_BYTES) ReadResult(found = true) else ReadResult(
                found = true,
                text = String(bytes, StandardCharsets.UTF_8),
            )
        }
    }

    private fun nativeRead(fileSystem: GameFileSystem, path: String): ByteArray? {
        if (!io.github.gdlbo.makerplay.runtime.webview.nativebridge.RpgmNative.isAvailable()) return null
        val file = fileSystem.absoluteFile(path) ?: return null
        return runCatching {
            io.github.gdlbo.makerplay.runtime.webview.nativebridge.RpgmNative.nativeReadFile(file.absolutePath)
        }.getOrNull()
    }

    private fun isReliablePluginManifest(script: String?): Boolean {
        if (script == null) return false
        val assignment = PLUGINS_ASSIGNMENT.find(script) ?: return false
        val start = script.indexOf('[', assignment.range.last + 1)
        val end = script.indexOf("];", start).takeIf { it >= start } ?: return false
        return runCatching {
            json.parseToJsonElement(script.substring(start, end + 1)).jsonArray.all { it is JsonObject }
        }.getOrDefault(false)
    }

    private fun parsePlugins(script: String?): List<String> {
        if (script == null) return emptyList()
        val assignment = PLUGINS_ASSIGNMENT.find(script) ?: return emptyList()
        val start = script.indexOf('[', assignment.range.last + 1)
        val end = script.indexOf("];", start).takeIf { it >= start } ?: return emptyList()
        return runCatching {
            json.parseToJsonElement(script.substring(start, end + 1)).jsonArray.mapNotNull { element ->
                val plugin = element.jsonObject
                val enabled = plugin["status"]?.jsonPrimitive?.booleanOrNull ?: true
                plugin["name"]?.jsonPrimitive?.contentOrNull?.takeIf { enabled && it.isNotBlank() }
            }.distinct().take(MAX_PLUGINS)
        }.getOrDefault(emptyList())
    }

    private fun parsePackageMetadata(text: String): PackageMetadata = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        PackageMetadata(
            main = root.value("main") ?: FingerprintValue.UNKNOWN,
            nodeMain = root.value("node-main") ?: FingerprintValue.UNKNOWN,
            requestedNwVersion = root.value("nwjs") ?: root.value("nwVersion") ?: FingerprintValue.UNKNOWN,
            requestedChromiumVersion = root.value("chromium") ?: FingerprintValue.UNKNOWN,
        )
    }.getOrDefault(PackageMetadata())

    private fun kotlinx.serialization.json.JsonObject.value(name: String): FingerprintValue<String>? =
        this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { FingerprintValue.Known(it) }

    private fun String?.versionValue(pattern: Regex): FingerprintValue<String> =
        this?.let { pattern.find(it)?.groupValues?.getOrNull(1)?.let { version -> FingerprintValue.Known(version) } }
            ?: FingerprintValue.UNKNOWN

    private fun String?.pixiMajorValue(): FingerprintValue<Int> =
        this?.let { PIXI_PATTERN.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { major -> FingerprintValue.Known(major) } }
            ?: FingerprintValue.UNKNOWN

    private fun List<String>.requirement(pattern: Regex, pluginManifestUnknown: Boolean): RequirementStatus = when {
        pluginManifestUnknown -> RequirementStatus.UNKNOWN
        isEmpty() -> RequirementStatus.UNKNOWN
        any(pattern::containsMatchIn) -> RequirementStatus.REQUIRED
        else -> RequirementStatus.NOT_DETECTED
    }

    private data class ReadResult(val found: Boolean, val text: String? = null)

    internal companion object {
        private val INSPECTION_EXECUTOR = ThreadPoolExecutor(
            1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
        val INSPECTION_DISPATCHER = INSPECTION_EXECUTOR.asCoroutineDispatcher()

        internal fun inspectionSchedulerBounds(): Pair<Int, Int> =
            INSPECTION_EXECUTOR.maximumPoolSize to INSPECTION_EXECUTOR.queue.remainingCapacity()
        const val INSPECTION_TIMEOUT_MILLIS = 5_000L
        const val MAX_METADATA_BYTES = 256 * 1024
        const val MAX_PLUGINS = 512
        const val MAX_PLUGIN_FILES = 128
        val VERSION_PATTERN = Regex("RPGMAKER_VERSION\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']")
        val PIXI_PATTERN = Regex("PIXI\\.VERSION\\s*=\\s*[\\\"'](\\d+)")
        val PLUGINS_ASSIGNMENT = Regex("(?:var|let|const)?\\s*\\${'$'}plugins\\s*=")
        val REQUIRED_GLOBAL_PATTERN = Regex("\\b(window|document|localStorage|indexedDB|nw|process)\\b")
        val STORAGE_PATTERN = Regex("\\b(localStorage|indexedDB|StorageManager|DataManager)\\b")
        val COMMON_JS_PATTERN = Regex("\\brequire\\s*\\(|\\bmodule\\.exports\\b")
        val NW_JS_PATTERN = Regex("\\bnw\\.")
        val NATIVE_ADDON_PATTERN = Regex("\\.node[\\\"']|[\\\"']bindings[\\\"']|\\bbindings\\s*\\(|node-gyp-build|prebuild-install")
        val UNSUPPORTED_PROCESS_PATTERN = Regex("\\b(child_process|worker_threads|cluster)\\b|\\bprocess\\.(spawn|kill|binding)")
    }
}

internal object RuntimeProfileResolver {
    fun resolve(
        fingerprint: EngineFingerprint,
        settings: RuntimeSettings,
    ): RuntimeProfile {
        val immutableFingerprint = fingerprint.copy(
            plugins = io.github.gdlbo.makerplay.runtime.api.ImmutableList.copyOf(fingerprint.plugins),
            requiredGlobals = io.github.gdlbo.makerplay.runtime.api.ImmutableSet.copyOf(fingerprint.requiredGlobals),
        )
        val selected = when (settings.engineMode) {
            RuntimeEngineMode.AUTO -> when (fingerprint.engine) {
                FingerprintEngine.MZ -> RuntimeEngineMode.MZ
                FingerprintEngine.MV -> RuntimeEngineMode.MV
                FingerprintEngine.UNKNOWN -> RuntimeEngineMode.AUTO
            }
            else -> settings.engineMode
        }
        return RuntimeProfile(
            fingerprint = immutableFingerprint,
            settings = settings,
            selectedEngine = selected,
            useMzNativeSaves = (settings.engineMode == RuntimeEngineMode.MZ ||
                settings.engineMode == RuntimeEngineMode.AUTO && fingerprint.mzNativeSaves == RequirementStatus.REQUIRED) &&
                fingerprint.mzNativeSaves == RequirementStatus.REQUIRED,
            useMvNativeSaves = (settings.engineMode == RuntimeEngineMode.MV ||
                settings.engineMode == RuntimeEngineMode.AUTO && fingerprint.mvNativeSaves == RequirementStatus.REQUIRED) &&
                fingerprint.mvNativeSaves == RequirementStatus.REQUIRED,
            moduleDecisions = io.github.gdlbo.makerplay.runtime.api.ImmutableMap.copyOf(buildMap {
                put("layout", "enabled:deployment=${fingerprint.deploymentLayout}")
                put("frame-resilience", "enabled:engine=${fingerprint.engine}")
                put("frame-rate", if (settings.fpsLimit != null || settings.showFpsCounter) "enabled:user-frame-setting" else "disabled:no-frame-setting")
                put("steam", if (settings.modules.steamCompatibility) "enabled:setting+engine=${fingerprint.engine}" else "disabled:user-setting")
                put("legacy", if (settings.legacyCompatibility) "enabled:setting+pixi=${fingerprint.pixiMajor}" else "disabled:user-setting")
                put("performance", if (settings.modules.performanceOptimization) "enabled:setting+plugins=${fingerprint.plugins.size}" else "disabled:user-setting")
                put("visual-boosts", if (settings.modules.visualBoosts) "enabled:user-setting" else "disabled:user-setting")
                put("common-js", "${fingerprint.commonJs.name.lowercase()}:detected-commonjs")
                put("nw-js", "${fingerprint.nwJs.name.lowercase()}:detected-nwjs")
                put("native-addons", "${fingerprint.nativeAddons.name.lowercase()}:detected-native-addon")
                put("unsupported-process", "${fingerprint.unsupportedProcessApis.name.lowercase()}:detected-process-api")
                put("worker-budget", if (settings.modules.limitWorkerCount) "enabled:user-setting" else "disabled:user-setting")
                put("diagnostics", if (settings.modules.diagnosticsBridge) "enabled:user-setting" else "disabled:user-setting")
                put("cheats", if (settings.modules.cheatBridge) "enabled:user-setting" else "disabled:user-setting")
                put("save-mz", "${fingerprint.mzNativeSaves.name.lowercase()}:detected-mz-save-manager")
                put("save-mv", "${fingerprint.mvNativeSaves.name.lowercase()}:detected-mv-save-manager")
            }),
        )
    }
}
