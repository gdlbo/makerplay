package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.diagnostics.RuntimeLogger
import io.github.gdlbo.makerplay.fixtures.RpgMakerFixtureGenerator
import io.github.gdlbo.makerplay.runtime.api.FileGameSaveStore
import io.github.gdlbo.makerplay.runtime.api.LaunchRequest
import io.github.gdlbo.makerplay.runtime.api.RuntimeEngineMode
import io.github.gdlbo.makerplay.runtime.api.RuntimeMemoryCleaner
import io.github.gdlbo.makerplay.runtime.api.RuntimeScaleMode
import io.github.gdlbo.makerplay.runtime.api.RuntimeSettings
import io.github.gdlbo.makerplay.vfs.GameFileIndex
import io.github.gdlbo.makerplay.vfs.GameFileSystem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class WebViewRuntimeBackendTest {
    @Test
    fun smokeSessionUsesControlledAssetOrigin() {
        val backend = WebViewRuntimeBackend(NoOpLogger)
        val settings = RuntimeSettings(scaleMode = RuntimeScaleMode.STRETCH, fpsLimit = 30)
        val session = runSuspend {
            backend.prepare(LaunchRequest("m0-smoke", smokeTest = true, settings = settings))
        }
        assertEquals(WebViewRuntimeBackend.ASSET_ORIGIN, session.allowedOrigin)
        assertEquals(WebViewRuntimeBackend.SMOKE_URL, session.startUrl)
        assertEquals(settings, session.settings)
        assertEquals(settings, session.runtimeProfile.settings)
    }

    @Test
    fun forcedEngineRejectsMismatchedGameFiles() {
        val root = fixtureRoot(
            RpgMakerFixtureGenerator.mz() + ("js/rmmz_managers.js" to byteArrayOf(1)),
        )
        try {
            val backend = WebViewRuntimeBackend(NoOpLogger) { root }
            val request = LaunchRequest(
                gameId = "game-1",
                settings = RuntimeSettings(engineMode = RuntimeEngineMode.MV),
            )

            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                runSuspend { backend.prepare(request) }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun runtimeConfigContainsDisplayAndFrameRateChoices() {
        val script = RuntimeSettings(
            scaleMode = RuntimeScaleMode.INTEGER,
            pixelSmoothing = false,
            fpsLimit = 144,
            showFpsCounter = true,
            vibrationEnabled = false,
        ).configScript(runtimeAsset("disable-vibration.js"))

        assertTrue(script.contains("scaleMode:'INTEGER'"))
        assertTrue(script.contains("pixelSmoothing:false"))
        assertTrue(script.contains("fpsLimit:144"))
        assertTrue(script.contains("showFpsCounter:true"))
        assertTrue(script.contains("vibrationEnabled:false"))
        assertTrue(script.contains("Navigator.prototype, 'vibrate'"))
        val defaultScript = RuntimeSettings().configScript(runtimeAsset("disable-vibration.js"))
        assertTrue(defaultScript.contains("fpsLimit:null"))
        assertTrue(defaultScript.contains("vibrationEnabled:true"))
        assertTrue(!defaultScript.contains("Navigator.prototype, 'vibrate'"))
        val defaultModules = RuntimeSettings().modules
        assertTrue(defaultModules.steamCompatibility)
        assertTrue(!defaultModules.limitWorkerCount)
        assertTrue(defaultModules.cheatBridge)
        assertTrue(defaultModules.diagnosticsBridge)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RuntimeSettings(fpsLimit = 75)
        }
    }

    @Test
    fun persistentGameLoggerIsRecreatedForEveryRecordedSession() {
        val root = fixtureRoot(RpgMakerFixtureGenerator.mz())
        try {
            var loggerFactoryCalls = 0
            val backend = WebViewRuntimeBackend(
                logger = NoOpLogger,
                gameLoggerFactory = {
                    loggerFactoryCalls++
                    NoOpLogger
                },
                gameDirectory = { root },
            )

            runSuspend { backend.prepare(LaunchRequest("game-1")) }
            assertEquals(0, loggerFactoryCalls)

            val firstRecorded = runSuspend {
                backend.prepare(
                    LaunchRequest("game-1", settings = RuntimeSettings(recordLogs = true)),
                )
            }
            assertEquals(1, loggerFactoryCalls)
            runSuspend { backend.destroySession(firstRecorded.sessionId) }

            runSuspend {
                backend.prepare(
                    LaunchRequest("game-1", settings = RuntimeSettings(recordLogs = true)),
                )
            }
            assertEquals(2, loggerFactoryCalls)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun importedGamesUseRandomIsolatedLocalOriginSessions() {
        val root = Files.createTempDirectory("makerplay-backend-test").toFile()
        try {
            RpgMakerFixtureGenerator.mz().forEach { (path, bytes) ->
                File(root, path).apply {
                    parentFile?.mkdirs()
                    writeBytes(bytes)
                }
            }
            val backend = WebViewRuntimeBackend(NoOpLogger) { id -> root.takeIf { id == "game-1" } }

            val first = runSuspend { backend.prepare(LaunchRequest("game-1")) }
            val second = runSuspend { backend.prepare(LaunchRequest("game-1")) }

            assertNotEquals(first.sessionId, second.sessionId)
            assertEquals(WebViewRuntimeBackend.gameOrigin("game-1"), first.allowedOrigin)
            assertEquals(first.allowedOrigin, second.allowedOrigin)
            assertTrue(first.startUrl.endsWith("/asset/index.html"))
            runSuspend { backend.destroySession(first.sessionId) }
            runSuspend { backend.destroySession(second.sessionId) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun browserStorageOriginsAreStablePerGameAndIsolatedBetweenGames() {
        val first = WebViewRuntimeBackend.gameOrigin("game-1")
        assertEquals(first, WebViewRuntimeBackend.gameOrigin("game-1"))
        assertNotEquals(first, WebViewRuntimeBackend.gameOrigin("game-2"))
        assertTrue(first.endsWith(WebViewRuntimeBackend.GAME_ORIGIN_SUFFIX))
    }

    @Test
    fun preparesUnnormalizedWwwDeploymentWithExistingMvLaunchContract() {
        val root = fixtureRoot(
            RpgMakerFixtureGenerator.mvInWww() + ("www/js/rpg_managers.js" to byteArrayOf(1)),
        )
        try {
            val backend = WebViewRuntimeBackend(NoOpLogger) { root }
            val session = runSuspend { backend.prepare(LaunchRequest("game-1")) }

            assertTrue(session.startUrl.endsWith("/asset/index.html"))
            assertEquals(io.github.gdlbo.makerplay.runtime.api.DeploymentLayout.WWW, session.runtimeProfile.fingerprint.deploymentLayout)
            assertEquals(RuntimeEngineMode.MV, session.runtimeProfile.selectedEngine)
            assertTrue(session.runtimeProfile.useMvNativeSaves)
            runSuspend { backend.destroySession(session.sessionId) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nativeSaveBridgeDetectsMzAndMvManagers() {
        val mzRoot =
            fixtureRoot(RpgMakerFixtureGenerator.mz() + ("js/rmmz_managers.js" to byteArrayOf(1)))
        val mvContainer = fixtureRoot(
            RpgMakerFixtureGenerator.mvInWww() + ("www/js/rpg_managers.js" to byteArrayOf(1)),
        )
        val mvRoot = File(mvContainer, "www")
        try {
            assertTrue(supportsMzNativeSaves(GameFileSystem(GameFileIndex.build(mzRoot))))
            assertTrue(!supportsMzNativeSaves(GameFileSystem(GameFileIndex.build(mvRoot))))
            assertTrue(!supportsMvNativeSaves(GameFileSystem(GameFileIndex.build(mzRoot))))
            assertTrue(supportsMvNativeSaves(GameFileSystem(GameFileIndex.build(mvRoot))))
        } finally {
            mzRoot.deleteRecursively()
            mvContainer.deleteRecursively()
        }
    }

    @Test
    fun mvSaveFilesBundledInWwwSaveRemainAuthoritativeAcrossSessions() {
        val gameRoot = fixtureRoot(
            RpgMakerFixtureGenerator.mvInWww()
                .mapKeys { (path) -> path.removePrefix("www/") } +
                    mapOf(
                        "js/rpg_managers.js" to byteArrayOf(1),
                        "save/file1.rpgsave" to "bundled".encodeToByteArray(),
                    ),
        )
        val saveRoot = Files.createTempDirectory("makerplay-save-import-test").toFile()
        try {
            val store = FileGameSaveStore(saveRoot)
            val backend = WebViewRuntimeBackend(NoOpLogger, store) { gameRoot }

            val first = runSuspend { backend.prepare(LaunchRequest("game-1")) }
            assertArrayEquals(
                "bundled".encodeToByteArray(),
                File(gameRoot, "save/file1.rpgsave").readBytes(),
            )
            File(gameRoot, "save/file1.rpgsave").writeText("newer")
            runSuspend { backend.destroySession(first.sessionId) }

            val second = runSuspend { backend.prepare(LaunchRequest("game-1")) }
            assertArrayEquals(
                "newer".encodeToByteArray(),
                File(gameRoot, "save/file1.rpgsave").readBytes(),
            )
            runSuspend { backend.destroySession(second.sessionId) }
        } finally {
            gameRoot.deleteRecursively()
            saveRoot.deleteRecursively()
        }
    }

    @Test
    fun mzSaveBinaryTextRemainsInTheGameSaveDirectory() {
        val payload = hex("7801f348cdc9c95728cf2fca495104001d09045e")
        val diskBytes = payload
            .joinToString("") { byte -> (byte.toInt() and 0xff).toChar().toString() }
            .toByteArray(StandardCharsets.UTF_8)
        val gameRoot = fixtureRoot(
            RpgMakerFixtureGenerator.mz() +
                    mapOf(
                        "js/rmmz_managers.js" to byteArrayOf(1),
                        "save/MyState.rmmzsave" to diskBytes,
                    ),
        )
        val saveRoot = Files.createTempDirectory("makerplay-mz-save-import-test").toFile()
        try {
            val store = FileGameSaveStore(saveRoot)
            val backend = WebViewRuntimeBackend(NoOpLogger, store) { gameRoot }

            val session = runSuspend { backend.prepare(LaunchRequest("game-1")) }

            assertArrayEquals(diskBytes, File(gameRoot, "save/MyState.rmmzsave").readBytes())
            assertArrayEquals(
                payload,
                GameFolderSaveStore(gameRoot, ".rmmzsave").read("game-1", "MyState")
            )
            runSuspend { backend.destroySession(session.sessionId) }
        } finally {
            gameRoot.deleteRecursively()
            saveRoot.deleteRecursively()
        }
    }

    @Test
    fun unknownImportedGameIsRejected() {
        val backend = WebViewRuntimeBackend(NoOpLogger)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            runSuspend { backend.prepare(LaunchRequest("missing")) }
        }
    }

    @Test
    fun activeSessionsAreBoundedAndDestroyReleasesCapacity() {
        val root = Files.createTempDirectory("makerplay-session-cap-test").toFile()
        try {
            RpgMakerFixtureGenerator.mz().forEach { (path, bytes) ->
                File(root, path).apply {
                    parentFile?.mkdirs()
                    writeBytes(bytes)
                }
            }
            val backend = WebViewRuntimeBackend(NoOpLogger) { root }
            val sessions = List(8) { runSuspend { backend.prepare(LaunchRequest("game-1")) } }

            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                runSuspend { backend.prepare(LaunchRequest("game-1")) }
            }
            runSuspend { backend.destroySession(sessions.first().sessionId) }
            val replacement = runSuspend { backend.prepare(LaunchRequest("game-1")) }
            assertTrue(replacement.startUrl.startsWith(WebViewRuntimeBackend.gameOrigin("game-1")))
            (sessions.drop(1) + replacement).forEach { session ->
                runSuspend { backend.destroySession(session.sessionId) }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun destroySessionTriggersMemoryCleanup() {
        val root = fixtureRoot(RpgMakerFixtureGenerator.mz())
        val callbackInvoked = AtomicBoolean(false)
        val registration = RuntimeMemoryCleaner.register {
            callbackInvoked.set(true)
        }
        try {
            val backend = WebViewRuntimeBackend(NoOpLogger) { root }
            val session = runSuspend { backend.prepare(LaunchRequest("game-1")) }
            runSuspend { backend.destroySession(session.sessionId) }
            assertTrue(callbackInvoked.get())
        } finally {
            registration.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun failedPrepareDoesNotLeakCachedWriteBehindSaveStore() {
        val container = fixtureRoot(
            RpgMakerFixtureGenerator.mvInWww() + ("www/js/rpg_managers.js" to byteArrayOf(1)),
        )
        val saveRoot = Files.createTempDirectory("makerplay-store-leak-test").toFile()
        try {
            val backend = WebViewRuntimeBackend(
                logger = NoOpLogger,
                saveStore = FileGameSaveStore(saveRoot),
                gameDirectory = { container },
            )
            val sessions = List(8) { index ->
                runSuspend { backend.prepare(LaunchRequest("game-$index")) }
            }
            assertEquals(8, backend.activeSaveStoreCount())

            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                runSuspend { backend.prepare(LaunchRequest("game-overflow")) }
            }
            // The rejected prepare created a store before the active-session cap fired; it must
            // not stay cached without an owning session.
            assertEquals(8, backend.activeSaveStoreCount())

            sessions.forEach { session ->
                runSuspend { backend.destroySession(session.sessionId) }
            }
            assertEquals(0, backend.activeSaveStoreCount())
        } finally {
            container.deleteRecursively()
            saveRoot.deleteRecursively()
        }
    }

    @Test
    fun destroySessionIsIdempotentForSaveStoreOwnership() {
        val container = fixtureRoot(
            RpgMakerFixtureGenerator.mvInWww() + ("www/js/rpg_managers.js" to byteArrayOf(1)),
        )
        val saveRoot = Files.createTempDirectory("makerplay-store-idempotent-test").toFile()
        try {
            val backend = WebViewRuntimeBackend(
                logger = NoOpLogger,
                saveStore = FileGameSaveStore(saveRoot),
                gameDirectory = { container },
            )
            val session = runSuspend { backend.prepare(LaunchRequest("game-1")) }
            assertEquals(1, backend.activeSaveStoreCount())

            runSuspend { backend.destroySession(session.sessionId) }
            runSuspend { backend.destroySession(session.sessionId) }
            assertEquals(0, backend.activeSaveStoreCount())
        } finally {
            container.deleteRecursively()
            saveRoot.deleteRecursively()
        }
    }

    @Test
    fun destroySessionClearsNodeCachesAndKeepsOverlayData() {
        val gameRoot = fixtureRoot(
            RpgMakerFixtureGenerator.mz() + ("js/rmmz_managers.js" to byteArrayOf(1)),
        )
        val dataRoot = Files.createTempDirectory("makerplay-node-cache-test").toFile()
        try {
            val backend = WebViewRuntimeBackend(
                logger = NoOpLogger,
                commonJsDataDirectory = { dataRoot },
                gameDirectory = { gameRoot },
            )
            val session = runSuspend { backend.prepare(LaunchRequest("game-1")) }
            File(dataRoot, "tmp/scratch.bin").writeText("scratch")
            File(dataRoot, "cache/blob.bin").writeText("cached")
            File(dataRoot, "game-overlay/config").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("settings")
            }
            // Pre-fix state: scratch output survives until the destroy path clears it.
            assertTrue(File(dataRoot, "tmp/scratch.bin").isFile)
            assertTrue(File(dataRoot, "cache/blob.bin").isFile)

            runSuspend { backend.destroySession(session.sessionId) }

            listOf("tmp", "cache", "logs").forEach { name ->
                assertTrue(
                    "$name must be empty after close",
                    File(dataRoot, name).listFiles().orEmpty().isEmpty(),
                )
            }
            assertEquals("settings", File(dataRoot, "game-overlay/config").readText())
            assertEquals(0, backend.activeSessionCount())
        } finally {
            gameRoot.deleteRecursively()
            dataRoot.deleteRecursively()
        }
    }

    @Test
    fun repeatedSessionsDoNotAccumulateSessionDirectories() {
        val gameRoot = fixtureRoot(
            RpgMakerFixtureGenerator.mz() + ("js/rmmz_managers.js" to byteArrayOf(1)),
        )
        val dataRoot = Files.createTempDirectory("makerplay-node-cycle-test").toFile()
        val uuidName = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        )
        try {
            val backend = WebViewRuntimeBackend(
                logger = NoOpLogger,
                commonJsDataDirectory = { dataRoot },
                gameDirectory = { gameRoot },
            )

            repeat(3) {
                val session = runSuspend { backend.prepare(LaunchRequest("game-1")) }
                File(dataRoot, "tmp/scratch.bin").writeText("scratch")
                runSuspend { backend.destroySession(session.sessionId) }
                assertEquals(0, backend.activeSessionCount())
                assertTrue(File(dataRoot, "tmp").listFiles().orEmpty().isEmpty())
            }

            val names = dataRoot.listFiles().orEmpty().map(File::getName)
            assertTrue("unexpected UUID-named entries: $names", names.none(uuidName::matches))
            assertTrue(
                "stable per-game directories missing: $names",
                names.containsAll(listOf("tmp", "cache", "logs", "game-overlay", "game-deleted")),
            )
        } finally {
            gameRoot.deleteRecursively()
            dataRoot.deleteRecursively()
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T = runBlocking { block() }

    private fun fixtureRoot(files: Map<String, ByteArray>): File =
        Files.createTempDirectory("makerplay-runtime-kind").toFile().also { root ->
            files.forEach { (path, bytes) ->
                File(root, path).apply {
                    parentFile?.mkdirs()
                    writeBytes(bytes)
                }
            }
        }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private object NoOpLogger : RuntimeLogger {
        override fun info(event: String, fields: Map<String, String>) = Unit
        override fun error(event: String, throwable: Throwable) = Unit
    }
}
