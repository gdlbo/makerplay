package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.runtime.api.WolfNativeBridge
import io.github.gdlbo.makerplay.runtime.api.WolfNativeDiagnostics
import io.github.gdlbo.makerplay.runtime.api.WolfNativeLoadException

/**
 * Loads `libwolf_native.so` and exposes the JNI surface declared by
 * `runtime/wolf/src/main/cpp/wolf_jni.cpp`.
 *
 * [isAvailable] is checked once at construction so the backend descriptor can
 * report NOT_INSTALLED on builds where the native library is absent instead of
 * crashing at first use.
 */
class WolfNativeJni : WolfNativeBridge {

    val isAvailable: Boolean
        get() {
            ensureLoaded()
            return loadSucceeded
        }

    /** SDL3 runtime version string from the loaded native library, or null. */
    fun sdlRuntimeVersion(): String? = if (isAvailable) sdlVersion() else null

    /** Native-side smoke check: platform layer init plus session registry round-trip. */
    fun runSmokeTest(): Boolean = isAvailable && nativeSmokeTest()

    private external fun sdlVersion(): String?

    private external fun nativeSmokeTest(): Boolean

    override fun loadGame(gameId: String, gameRoot: String): Long {
        checkAvailable()
        return nativeLoadGame(gameId, gameRoot)
    }

    override fun destroySession(handle: Long) {
        if (isAvailable) nativeDestroySession(handle)
    }

    override fun setPaused(handle: Long, paused: Boolean) {
        if (isAvailable) nativeSetPaused(handle, paused)
    }

    override fun requestExit(handle: Long) {
        if (isAvailable) nativeRequestExit(handle)
    }

    override fun setStaticFrame(handle: Long, rgba: ByteArray, width: Int, height: Int) {
        if (isAvailable) nativeSetStaticFrame(handle, rgba, width, height)
    }

    override fun renderFrame(handle: Long, width: Int, height: Int) {
        if (isAvailable) nativeRenderFrame(handle, width, height)
    }

    override fun setInputState(handle: Long, actions: IntArray, pressedAxes: FloatArray) {
        if (isAvailable) nativeSetInputState(handle, actions, pressedAxes)
    }

    override fun serializeSave(handle: Long, slot: String): ByteArray? {
        if (!isAvailable) return null
        return nativeSerializeSave(handle, slot)
    }

    override fun restoreSave(handle: Long, slot: String, payload: ByteArray): Boolean {
        if (!isAvailable) return false
        return nativeRestoreSave(handle, slot, payload)
    }

    override fun diagnosticsSnapshot(handle: Long): WolfNativeDiagnostics {
        if (!isAvailable) return WolfNativeDiagnostics()
        val values = nativeDiagnosticsSnapshot(handle)
        if (values == null || values.size < 5) return WolfNativeDiagnostics()
        return WolfNativeDiagnostics(
            framesRendered = values[0].toLong(),
            averageFrameMillis = values[1],
            mapsParsed = values[2].toInt(),
            eventsExecuted = values[3].toLong(),
            audioStreamsActive = values[4].toInt(),
        )
    }

    override fun lastError(handle: Long): String? {
        if (!isAvailable) return null
        return nativeLastError(handle)
    }

    private fun checkAvailable() {
        if (!isAvailable) {
            throw WolfNativeLoadException("The WOLF native runtime library is not installed.")
        }
    }

    private external fun nativeLoadGame(gameId: String, gameRoot: String): Long

    private external fun nativeDestroySession(handle: Long)

    private external fun nativeSetPaused(handle: Long, paused: Boolean)

    private external fun nativeRequestExit(handle: Long)

    private external fun nativeSetStaticFrame(handle: Long, rgba: ByteArray, width: Int, height: Int)

    private external fun nativeRenderFrame(handle: Long, width: Int, height: Int)

    private external fun nativeSetInputState(handle: Long, actions: IntArray, axes: FloatArray)

    private external fun nativeSerializeSave(handle: Long, slot: String): ByteArray?

    private external fun nativeRestoreSave(handle: Long, slot: String, payload: ByteArray): Boolean

    private external fun nativeDiagnosticsSnapshot(handle: Long): DoubleArray?

    private external fun nativeLastError(handle: Long): String?

    companion object {
        const val LIBRARY_NAME = "wolf_native"

        /** Runs once; a failed load keeps [WolfRuntimeBackend] usable without playback. */
        @Volatile
        private var loadAttempted = false

        @Volatile
        private var loadSucceeded = false

        @Volatile
        private var cachedInstance: WolfNativeJni? = null

        /** Returns the shared instance once the library has loaded; null otherwise. */
        fun tryCreate(): WolfNativeJni? {
            ensureLoaded()
            if (!loadSucceeded) return null
            return cachedInstance ?: synchronized(this) {
                cachedInstance ?: WolfNativeJni().also { cachedInstance = it }
            }
        }

        @Synchronized
        private fun ensureLoaded() {
            if (loadAttempted) return
            loadAttempted = true
            loadSucceeded = runCatching { System.loadLibrary(LIBRARY_NAME) }.isSuccess
        }
    }
}
