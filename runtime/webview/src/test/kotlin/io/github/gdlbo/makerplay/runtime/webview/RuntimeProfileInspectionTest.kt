package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.fixtures.RpgMakerFixtureGenerator
import io.github.gdlbo.makerplay.runtime.api.DeploymentLayout
import io.github.gdlbo.makerplay.runtime.api.EngineFingerprint
import io.github.gdlbo.makerplay.runtime.api.FingerprintEngine
import io.github.gdlbo.makerplay.runtime.api.FingerprintValue
import io.github.gdlbo.makerplay.runtime.api.RequirementStatus
import io.github.gdlbo.makerplay.runtime.api.RuntimeEngineMode
import io.github.gdlbo.makerplay.runtime.api.RuntimeSettings
import io.github.gdlbo.makerplay.vfs.GameFileIndex
import io.github.gdlbo.makerplay.vfs.GameFileSystem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RuntimeProfileInspectionTest {
    private val inspector = DeploymentInspector()

    @Test
    fun `inspection scheduler has one worker and bounded queue`() {
        assertEquals(1 to 8, DeploymentInspector.inspectionSchedulerBounds())
    }

    @Test
    fun `inspect detects MZ root layout versions plugins and package requirements`() = runBlocking {
        val root = fixtureRoot(
            RpgMakerFixtureGenerator.mz() + mapOf(
                "js/rmmz_core.js" to "Utils.RPGMAKER_VERSION = '1.8.0'; PIXI.VERSION = '5.3.0';".encodeToByteArray(),
                "package.json" to """{"main":"index.html","node-main":"main.js","nwjs":"0.72","chromium":"110"}""".encodeToByteArray(),
                "js/plugins/FixturePlugin.js" to "require('x'); nw.Window.get(); require('bindings')".encodeToByteArray(),
            ),
        )
        try {
            val fingerprint = inspector.inspect(fileSystem(root))
            assertEquals(FingerprintEngine.MZ, fingerprint.engine)
            assertEquals(DeploymentLayout.ROOT, fingerprint.deploymentLayout)
            assertEquals(FingerprintValue.Known("1.8.0"), fingerprint.coreVersion)
            assertEquals(FingerprintValue.Known(5), fingerprint.pixiMajor)
            assertEquals(listOf("FixturePlugin"), fingerprint.plugins)
            assertEquals(FingerprintValue.Known("index.html"), fingerprint.packageMetadata!!.main)
            assertEquals(RequirementStatus.REQUIRED, fingerprint.commonJs)
            assertEquals(RequirementStatus.REQUIRED, fingerprint.nwJs)
            assertEquals(RequirementStatus.REQUIRED, fingerprint.nativeAddons)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `inspect detects MV www layout and unsupported process APIs`() = runBlocking {
        val root = fixtureRoot(
            RpgMakerFixtureGenerator.mvInWww() + mapOf(
                "www/js/rpg_core.js" to "Utils.RPGMAKER_VERSION = '1.6.2';".encodeToByteArray(),
                "www/js/plugins.js" to "var ${'$'}plugins = [{\"name\":\"Desktop\",\"status\":true}];".encodeToByteArray(),
                "www/js/plugins/Desktop.js" to "require('child_process'); process.spawn('x')".encodeToByteArray(),
            ),
        )
        try {
            val fingerprint = inspector.inspect(fileSystem(root))
            assertEquals(FingerprintEngine.MV, fingerprint.engine)
            assertEquals(DeploymentLayout.WWW, fingerprint.deploymentLayout)
            assertEquals(RequirementStatus.REQUIRED, fingerprint.unsupportedProcessApis)
            assertEquals(FingerprintValue.UNKNOWN, fingerprint.pixiMajor)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `malformed and oversized metadata remains unknown`() = runBlocking {
        val root = fixtureRoot(
            RpgMakerFixtureGenerator.mz() + mapOf(
                "package.json" to "{".encodeToByteArray(),
                "js/plugins.js" to ByteArray(256 * 1024 + 1),
            ),
        )
        try {
            val fingerprint = inspector.inspect(fileSystem(root))
            assertEquals(FingerprintValue.Known("1.8.0"), fingerprint.coreVersion)
            assertEquals(RequirementStatus.UNKNOWN, fingerprint.commonJs)
            assertTrue(fingerprint.packageMetadata != null)
            assertEquals(FingerprintValue.UNKNOWN, fingerprint.packageMetadata!!.main)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `malformed plugin manifest leaves plugin-derived requirements unknown`() = runBlocking {
        val root = fixtureRoot(
            RpgMakerFixtureGenerator.mz() + ("js/plugins.js" to "var ${'$'}plugins = [".encodeToByteArray()),
        )
        try {
            val fingerprint = inspector.inspect(fileSystem(root))
            assertEquals(RequirementStatus.UNKNOWN, fingerprint.commonJs)
            assertEquals(RequirementStatus.UNKNOWN, fingerprint.nativeAddons)
            assertEquals(RequirementStatus.UNKNOWN, fingerprint.unsupportedProcessApis)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `profile resolution is deterministic and retains existing save selection`() {
        val fingerprint = EngineFingerprint(
            engine = FingerprintEngine.MZ,
            mzNativeSaves = RequirementStatus.REQUIRED,
        )
        val settings = RuntimeSettings(engineMode = RuntimeEngineMode.AUTO)
        val first = RuntimeProfileResolver.resolve(fingerprint, settings)
        val second = RuntimeProfileResolver.resolve(fingerprint, settings)

        assertEquals(first, second)
        assertEquals(RuntimeEngineMode.MZ, first.selectedEngine)
        assertTrue(first.useMzNativeSaves)
    }

    @Test
    fun `profile resolution records forced modes unknown capability facts and module reasons`() {
        val fingerprint = EngineFingerprint(
            engine = FingerprintEngine.UNKNOWN,
            deploymentLayout = DeploymentLayout.WWW,
            commonJs = RequirementStatus.REQUIRED,
            nwJs = RequirementStatus.NOT_DETECTED,
            nativeAddons = RequirementStatus.REQUIRED,
            unsupportedProcessApis = RequirementStatus.REQUIRED,
            mvNativeSaves = RequirementStatus.REQUIRED,
            plugins = io.github.gdlbo.makerplay.runtime.api.ImmutableList.copyOf(listOf("DesktopPlugin")),
        )
        val profile = RuntimeProfileResolver.resolve(
            fingerprint,
            RuntimeSettings(engineMode = RuntimeEngineMode.MV, legacyCompatibility = false)
        )

        assertEquals(RuntimeEngineMode.MV, profile.selectedEngine)
        assertTrue(profile.useMvNativeSaves)
        assertEquals("enabled:deployment=WWW", profile.moduleDecisions["layout"])
        assertEquals("required:detected-commonjs", profile.moduleDecisions["common-js"])
        assertEquals("required:detected-native-addon", profile.moduleDecisions["native-addons"])
        assertEquals("required:detected-process-api", profile.moduleDecisions["unsupported-process"])
        assertEquals("disabled:user-setting", profile.moduleDecisions["legacy"])
    }

    private fun fileSystem(root: File) = GameFileSystem(GameFileIndex.build(root))

    private fun fixtureRoot(files: Map<String, ByteArray>): File =
        Files.createTempDirectory("makerplay-profile").toFile().also { root ->
            files.forEach { (path, bytes) ->
                File(root, path).apply {
                    parentFile?.mkdirs()
                    writeBytes(bytes)
                }
            }
        }
}
