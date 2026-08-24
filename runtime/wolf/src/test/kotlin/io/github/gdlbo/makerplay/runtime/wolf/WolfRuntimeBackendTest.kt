package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.diagnostics.RuntimeLogger
import io.github.gdlbo.makerplay.runtime.api.LaunchRequest
import io.github.gdlbo.makerplay.runtime.api.RuntimeBackendCapability
import io.github.gdlbo.makerplay.runtime.api.RuntimeSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class WolfRuntimeBackendTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val logger = object : RuntimeLogger {
        override fun info(event: String, fields: Map<String, String>) = Unit
        override fun error(event: String, fields: Map<String, String>) = Unit
        override fun error(event: String, throwable: Throwable) = Unit
    }

    @Test
    fun descriptorReportsNativeBackendAndAvailability() {
        val unavailable = backendWithoutBridge()
        assertEquals(WolfRuntimeBackend.BACKEND_ID, unavailable.descriptor.id)
        assertEquals(
            RuntimeBackendCapability.NOT_INSTALLED,
            unavailable.descriptor.capability,
        )
        val available = WolfRuntimeBackend(
            logger = logger,
            bridge = { NoopWolfNativeBridge },
        )
        // Bridge present means the native library is wired in.
        assertEquals(
            RuntimeBackendCapability.AVAILABLE,
            available.descriptor.capability,
        )
    }

    private fun wolfDeployment(): java.io.File {
        val root = temporaryFolder.newFolder("wolf-game")
        root.resolve("Game.exe").writeBytes(ByteArray(0))
        root.resolve("Game.dat").writeBytes(minimalGameDat())
        return root
    }

    /** Minimal valid v3 Game.dat matching the format library's expectations. */
    private fun minimalGameDat(): ByteArray {
        val u8Settings = ByteArray(24)
        u8Settings[0] = 40 // tile size
        u8Settings[4] = 60 // fps
        val strings = listOf("Test", "S", "K") + List(10) { "" }
        val stringBlock = ByteArrayOutputStream().apply {
            strings.forEach { s ->
                val bytes = s.toByteArray(Charsets.UTF_8) + 0
                repeat(4) { write((bytes.size ushr (it * 8)) and 0xFF) }
                write(bytes)
            }
        }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 'W'.code.toByte(), 0, 0, 'O'.code.toByte(), 'L'.code.toByte()))
        out.write(byteArrayOf(0, 'F'.code.toByte(), 'M'.code.toByte()))
        out.write(0x55)
        fun u4(v: Int) { repeat(4) { out.write((v ushr (it * 8)) and 0xFF) } }
        fun u2(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
        u4(u8Settings.size)
        out.write(u8Settings)
        u4(strings.size) // string count field
        out.write(stringBlock)
        u4(100) // static randoms size marker
        u4(0) // unknown
        u4(19) // u16 settings count
        repeat(16) { u2(0) }
        u2(1280); u2(720); u2(0x0300)
        repeat(100) { out.write(0) }
        out.write(0xC3)
        return out.toByteArray()
    }

    private fun backendWithoutBridge() =
        WolfRuntimeBackend(logger = logger, gameDirectory = { null })

    @Test
    fun smokeTestPreparesSessionWithoutGameDirectory() = runBlocking {
        val session = backendWithoutBridge().prepare(
            LaunchRequest("m0-smoke", smokeTest = true, settings = RuntimeSettings()),
        )
        assertEquals("m0-smoke", session.sessionId)
        assertEquals(WolfRuntimeBackend.NATIVE_ORIGIN, session.allowedOrigin)
    }

    @Test
    fun prepareValidatesWolfDeploymentAndReturnsSession() = runBlocking {
        val root = wolfDeployment()
        val backend = WolfRuntimeBackend(logger = logger, gameDirectory = { root })
        val session = backend.prepare(LaunchRequest("wolf-1", settings = RuntimeSettings()))
        assertTrue(session.sessionId.isNotBlank())
        assertEquals(WolfRuntimeBackend.NATIVE_ORIGIN, session.allowedOrigin)
    }

    @Test
    fun prepareFailsWhenGameIsUnavailable() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { backendWithoutBridge().prepare(LaunchRequest("missing")) }
        }
        assertEquals("The imported game is unavailable.", exception.message)
    }

    @Test
    fun prepareRejectsNonWolfDeployments() {
        val notWolf = temporaryFolder.newFolder("mv-game")
        notWolf.resolve("index.html").writeText("<html></html>")
        val backend = WolfRuntimeBackend(logger = logger, gameDirectory = { notWolf })
        val exception = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { backend.prepare(LaunchRequest("mv-1")) }
        }
        assertTrue(exception.message!!.contains("not a WOLF RPG deployment"))
    }

    @Test
    fun wolfDataSignatureIsSufficientWithoutGameExe() {
        // Data-only distribution: Data/BasicData/Game.dat, no exe.
        val root = temporaryFolder.newFolder("no-exe-wolf")
        root.resolve("Data/BasicData").mkdirs()
        root.resolve("Data/BasicData/Game.dat").writeBytes(minimalGameDat())
        val backend = WolfRuntimeBackend(logger = logger, gameDirectory = { root })
        runBlocking {
            val session = backend.prepare(LaunchRequest("dragon", settings = RuntimeSettings()))
            assertTrue(session.sessionId.isNotBlank())
        }
    }

    @Test
    fun validateAcceptsNestedBasicDataLayout() {
        val nested = temporaryFolder.newFolder("wolf-nested")
        nested.resolve("Game.exe").writeBytes(ByteArray(0))
        nested.resolve("Data/BasicData").mkdirs()
        nested.resolve("Data/BasicData/Game.dat").writeBytes(ByteArray(0))
        backendWithoutBridge().validateWolfDeployment(nested)
    }

    @Test
    fun destroyUnknownSessionIsNoop() = runBlocking {
        backendWithoutBridge().destroySession("unknown")
    }

    private object NoopWolfNativeBridge : io.github.gdlbo.makerplay.runtime.api.WolfNativeBridge {
        override fun loadGame(gameId: String, gameRoot: String): Long = 1L
        override fun destroySession(handle: Long) = Unit
        override fun setPaused(handle: Long, paused: Boolean) = Unit
        override fun requestExit(handle: Long) = Unit
        override fun renderFrame(handle: Long, width: Int, height: Int) = Unit
        override fun setStaticFrame(handle: Long, rgba: ByteArray, width: Int, height: Int) = Unit
        override fun setInputState(handle: Long, actions: IntArray, pressedAxes: FloatArray) = Unit
        override fun serializeSave(handle: Long, slot: String): ByteArray? = null
        override fun restoreSave(handle: Long, slot: String, payload: ByteArray): Boolean = false
        override fun diagnosticsSnapshot(
            handle: Long,
        ): io.github.gdlbo.makerplay.runtime.api.WolfNativeDiagnostics =
            io.github.gdlbo.makerplay.runtime.api.WolfNativeDiagnostics()

        override fun lastError(handle: Long): String? = null
    }
}
