package io.github.gdlbo.makerplay.runtime.api

/**
 * Contract between the Kotlin runtime hosting layer and the native WOLF RPG
 * interpreter (C++ core in `:runtime:wolf`). The native side owns the game
 * loop, game state, and rendering; Kotlin owns lifecycle, Compose hosting,
 * virtual controls, and persistence plumbing.
 *
 * The contract is deliberately narrow: plain values and byte arrays cross the
 * JNI boundary. Native code never blocks the Android main thread except inside
 * [WolfNativeBridge.renderFrame], which is invoked from the Compose surface
 * callback while holding the current GL context.
 */
interface WolfNativeBridge {
    /**
     * Loads a deployment rooted at [gameRoot] and returns a session handle used
     * by every other call. Throws [WolfNativeLoadException] with a diagnostic
     * reason when parsing fails; malformed input must be rejected, never
     * partially loaded.
     */
    fun loadGame(gameId: String, gameRoot: String): Long

    /** Releases the native session. Safe to call for unknown handles. */
    fun destroySession(handle: Long)

    // --- Lifecycle -------------------------------------------------------------

    /** Applies pause/resume from the Android lifecycle. Native stops advancing time while paused. */
    fun setPaused(handle: Long, paused: Boolean)

    /** Requests graceful shutdown of the interpreter (equivalent to closing the window). */
    fun requestExit(handle: Long)

    // --- Rendering ---------------------------------------------------------------

    /**
     * Supplies a pre-composed RGBA (8888) frame — e.g. the static boot frame
     * composited from parsed map/tileset data — that [renderFrame] presents.
     * The native side copies the buffer before returning.
     */
    fun setStaticFrame(handle: Long, rgba: ByteArray, width: Int, height: Int)

    /**
     * Renders one frame into the currently bound EGL/GLES context using
     * [width]x[height] surface pixels and the configured scale mode.
     */
    fun renderFrame(handle: Long, width: Int, height: Int)

    /** Reports the logical input state for this frame (see core:input). */
    fun setInputState(handle: Long, actions: IntArray, pressedAxes: FloatArray)

    // --- Save / load ---------------------------------------------------------------

    /** Serializes interpreter state (variables, switches, party, position, pictures, events). */
    fun serializeSave(handle: Long, slot: String): ByteArray?

    /** Restores previously serialized state. Returns false when the slot is absent or corrupt. */
    fun restoreSave(handle: Long, slot: String, payload: ByteArray): Boolean

    // --- Diagnostics ---------------------------------------------------------------

    /** Snapshot of FPS, frame time, parser/interpreter counters for diagnostics surfaces. */
    fun diagnosticsSnapshot(handle: Long): WolfNativeDiagnostics

    /** Last fatal error text, used for failure reports when the renderer dies. */
    fun lastError(handle: Long): String?
}

data class WolfNativeDiagnostics(
    val framesRendered: Long = 0L,
    val averageFrameMillis: Double = 0.0,
    val mapsParsed: Int = 0,
    val eventsExecuted: Long = 0L,
    val audioStreamsActive: Int = 0,
)

class WolfNativeLoadException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
