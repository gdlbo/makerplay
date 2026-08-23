package io.github.gdlbo.makerplay.runtime.wolf

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gdlbo.makerplay.runtime.api.WolfNativeLoadException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device/instrumented JNI smoke test: loads libwolf_native.so, verifies the
 * SDL platform layer initializes, the session registry round-trips, input
 * marshaling does not fault, and diagnostics counters update.
 */
@RunWith(AndroidJUnit4::class)
class WolfNativeSmokeTest {

    @Test
    fun libraryLoadsAndReportsSdlVersion() {
        val jni = WolfNativeJni.tryCreate()
        assertNotNull("libwolf_native.so must be packaged with the APK", jni)
        jni!!.runSmokeTest()
        val version = jni.sdlRuntimeVersion()
        assertNotNull(version)
        assertTrue(version!!.isNotBlank())
    }

    @Test
    fun sessionRegistryRoundTrip() {
        val jni = WolfNativeJni.tryCreate()!!
        val handle = jni.loadGame("smoke-game", "/data/local/tmp")
        assertNotEquals(0L, handle)
        try {
            jni.setPaused(handle, true)
            jni.setInputState(handle, IntArray(17), FloatArray(17))
            jni.renderFrame(handle, 320, 240)
            val diagnostics = jni.diagnosticsSnapshot(handle)
            assertEquals(0L, diagnostics.framesRendered) // paused: no frames advance
        } finally {
            jni.destroySession(handle)
        }
        // Counters for a destroyed handle read as zeros instead of crashing.
        assertEquals(0L, jni.diagnosticsSnapshot(handle).framesRendered)
    }

    @Test
    fun unknownHandleOperationsAreSafe() {
        val jni = WolfNativeJni.tryCreate()!!
        jni.destroySession(0L)
        assertTrue(!jni.restoreSave(0L, "slot", ByteArray(0)))
        org.junit.Assert.assertNull(jni.serializeSave(0L, "slot"))
        org.junit.Assert.assertNull(jni.lastError(0L))
    }
}
