package io.github.gdlbo.makerplay.runtime.wolf

import io.github.gdlbo.makerplay.runtime.api.WolfNativeBridge
import io.github.gdlbo.makerplay.runtime.api.WolfNativeDiagnostics
import io.github.gdlbo.makerplay.runtime.api.WolfNativeLoadException

/**
 * Loads `libwolf_native.so` (Rust core + vendored SDL3).
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

    /**
     * Parses [dataDir] with the Rust format layer and returns its one-line
     * summary, or null when the library is unavailable.
     *
     * This exercises the Rust parsers that will replace the Kotlin format layer,
     * so their presence in the shipped `.so` is observable rather than assumed.
     */
    fun probeFormats(dataDir: String): String? =
        if (isAvailable) nativeProbeFormats(dataDir) else null

    /**
     * Runs an interpreter corpus through the Rust interpreter and returns its
     * trace, or null when the library is unavailable.
     *
     * The Rust interpreter is only observable through an exported symbol; with
     * `lto` and `strip` enabled, code nothing calls is removed from the `.so`.
     * This entry point is what makes the shipped interpreter testable on device.
     */
    fun runInterpreterCorpus(corpus: String): String? =
        if (isAvailable) nativeRunInterpreterCorpus(corpus) else null

    /**
     * Runs a corpus with the deployment's databases loaded, so the `250/251/252`
     * and `255/256/257` families execute inside the Rust interpreter instead of
     * reaching the host hook.
     */
    fun runInterpreterCorpusWithDatabase(corpus: String, dataDir: String): String? =
        if (isAvailable) nativeRunInterpreterCorpusWithDatabase(corpus, dataDir) else null

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

    private external fun nativeProbeFormats(dataDir: String): String?

    private external fun nativeRunInterpreterCorpus(corpus: String): String?

    private external fun nativeRunInterpreterCorpusWithDatabase(
        corpus: String,
        dataDir: String,
    ): String?

    // --- Rust-owned loop -----------------------------------------------------

    /**
     * Starts the Rust-owned SDL loop. Returns "" on success or the failure reason.
     *
     * One loop owns both the frame buffer and the event queue, which is the
     * structural fix for touch never reaching the game: previously the Android
     * view hierarchy owned input and a `GLSurfaceView` owned rendering, and the
     * two never met.
     */
    fun loopStart(
        title: String,
        gameWidth: Int,
        gameHeight: Int,
        videoDriver: String = "",
        gameRoot: String = "",
        mapName: String = "",
    ): String =
        if (isAvailable) {
            nativeLoopStart(title, gameWidth, gameHeight, videoDriver, gameRoot, mapName)
        } else {
            "native library unavailable"
        }

    /**
     * Starts the Rust loop on the app's own [surface], so the app keeps the surface
     * (and SDL's input path) while Rust renders into it.
     */
    fun loopStartOnSurface(
        surface: android.view.Surface,
        title: String,
        gameWidth: Int,
        gameHeight: Int,
        gameRoot: String,
        mapName: String,
    ): String =
        if (isAvailable) {
            nativeLoopStartOnSurface(surface, title, gameWidth, gameHeight, gameRoot, mapName)
        } else {
            "native library unavailable"
        }

    /**
     * Presents an embedder-composed frame on the surface the loop owns.
     *
     * This is how the app's gameplay engine (which lives in Kotlin) reaches the
     * screen without a second render or input path.
     */
    fun loopSetExternalFrame(rgba: ByteArray, width: Int, height: Int) {
        if (isAvailable) nativeLoopSetExternalFrame(rgba, width, height)
    }

    /**
     * Composes the loop's current frame with the **Rust** compositor and returns
     * it as `[width, height, RGBA...]` little-endian ints, or null.
     *
     * This is what proves the Rust renderer draws the game rather than presenting
     * black: the bytes come from `scene::compose` inside `libwolf_native.so`.
     */
    fun loopFrame(width: Int, height: Int): ByteArray? =
        if (isAvailable) nativeLoopFrame(width, height) else null

    /** Loads a map into the running loop's render session; returns "" on success. */
    fun loopLoadMap(mapName: String): String =
        if (isAvailable) nativeLoopLoadMap(mapName) else "native library unavailable"

    /** Places the hero so the camera follows. */
    fun loopSetHero(tileX: Int, tileY: Int) {
        if (isAvailable) nativeLoopSetHero(tileX, tileY)
    }

    /**
     * Replaces the loop's pictures. One per line: `slot|fileName|x|y|optionWord`.
     * Returns "" on success or the failure reason.
     */
    fun loopSetPictures(spec: String): String =
        if (isAvailable) nativeLoopSetPictures(spec) else "native library unavailable"

    /** Shows or hides the hero sprite (the camera still follows its tile). */
    fun loopSetHeroVisible(visible: Boolean) {
        if (isAvailable) nativeLoopSetHeroVisible(visible)
    }

    /** Sets the in-frame message text; empty clears it. Returns "" on success. */
    fun loopSetMessage(text: String): String =
        if (isAvailable) nativeLoopSetMessage(text) else "native library unavailable"

    /** True when the render session loaded a font. */
    fun loopHasFont(): Boolean = isAvailable && nativeLoopHasFont()

    /**
     * Sets the loop's map event sprites. One per line:
     * `graphicFile|tileX|tileY|row|col|opacity`. Returns "" on success.
     */
    fun loopSetEvents(spec: String): String =
        if (isAvailable) nativeLoopSetEvents(spec) else "native library unavailable"

    /**
     * Runs a command list through the loop's own interpreter and returns the
     * composed frame. Proves the loop presents what a script asks for.
     */
    fun loopRunScriptCommands(script: String, width: Int, height: Int): ByteArray? =
        if (isAvailable) nativeLoopRunScriptCommands(script, width, height) else null

    /** Sets the choice window: `selected|opt1|opt2|...`; empty clears it. */
    fun loopSetChoices(spec: String): String =
        if (isAvailable) nativeLoopSetChoices(spec) else "native library unavailable"

    /** Number of choice options currently shown. */
    fun loopChoiceCount(): Int = if (isAvailable) nativeLoopChoiceCount() else 0

    /**
     * Picture bounds as a flat `[slot, left, top, right, bottom, ...]` array.
     * Used for the engine's "picture under the cursor" hit test.
     */
    fun loopPictureBounds(): IntArray? = if (isAvailable) nativeLoopPictureBounds() else null

    /**
     * Composes a scene with the **Rust renderer** and returns
     * `[width, height, RGBA...]`, or null when unavailable.
     *
     * This is the app-side entry point: the Kotlin engine keeps owning map
     * loading, triggers, the interpreter and the database, and hands over only
     * what to draw, so the tile/picture/text compositing is Rust.
     */
    override fun composeScene(
        gameRoot: String,
        mapName: String,
        spec: String,
        width: Int,
        height: Int,
    ): ByteArray? =
        if (isAvailable) nativeComposeScene(gameRoot, mapName, spec, width, height) else null

    /**
     * Where the last composed scene drew each picture, as
     * `[slot, left, top, right, bottom, ...]`.
     *
     * The picture-menu hit test runs in Kotlin, so it needs the rectangles the Rust
     * compositor actually used. Empty when nothing has been composed.
     */
    fun scenePictureBounds(): IntArray? =
        if (isAvailable) nativeScenePictureBounds() else null

    /** Releases the Rust compositor's decoded images. */
    fun resetScene() {
        if (isAvailable) nativeResetScene()
    }

    /** Sets an extra camera offset (engine shake/scroll), in pixels. */
    fun loopSetCameraExtra(dx: Int, dy: Int) {
        if (isAvailable) nativeLoopSetCameraExtra(dx, dy)
    }

    /** Event sprites the loop's render session currently holds. */
    fun loopEventCount(): Int = if (isAvailable) nativeLoopEventCount() else 0

    /** Pictures the loop's render session currently holds. */
    fun loopPictureCount(): Int = if (isAvailable) nativeLoopPictureCount() else 0

    /** Runs one loop iteration. Returns true while the loop should continue. */
    fun loopStep(): Boolean = isAvailable && nativeLoopStep() == 0

    /** Injects a pointer position in window pixels. */
    fun loopInjectPointer(x: Float, y: Float, down: Boolean) =
        if (isAvailable) nativeLoopInjectPointer(x, y, down) else Unit

    /** Injects a pointer position in game pixels. */
    fun loopInjectGamePointer(x: Int, y: Int, down: Boolean) =
        if (isAvailable) nativeLoopInjectGamePointer(x, y, down) else Unit

    /** The WOLF key code the loop currently reports; 0 when nothing is pressed. */
    fun loopPollKey(): Int = if (isAvailable) nativeLoopPollKey() else 0

    /** The cursor position in game pixels, or null. */
    fun loopCursor(): Pair<Int, Int>? {
        if (!isAvailable) return null
        val values = nativeLoopCursor() ?: return null
        if (values.size < 2) return null
        return values[0] to values[1]
    }

    /** Loop diagnostics: frames, events applied, quit flag, running flag. */
    fun loopStats(): DoubleArray? = if (isAvailable) nativeLoopStats() else null

    fun loopStop() {
        if (isAvailable) nativeLoopStop()
    }

    /** Runs a scripted input sequence through the loop; see the native doc. */
    fun loopRunScript(script: String): String? =
        if (isAvailable) nativeLoopRunScript(script) else null

    private external fun nativeLoopStart(
        title: String,
        gameWidth: Int,
        gameHeight: Int,
        videoDriver: String,
        gameRoot: String,
        mapName: String,
    ): String

    private external fun nativeLoopSetExternalFrame(rgba: ByteArray, width: Int, height: Int)

    private external fun nativeLoopStartOnSurface(
        surface: android.view.Surface,
        title: String,
        gameWidth: Int,
        gameHeight: Int,
        gameRoot: String,
        mapName: String,
    ): String

    private external fun nativeLoopFrame(width: Int, height: Int): ByteArray?

    private external fun nativeLoopLoadMap(mapName: String): String

    private external fun nativeLoopSetHero(tileX: Int, tileY: Int)

    private external fun nativeLoopSetPictures(spec: String): String

    private external fun nativeLoopSetHeroVisible(visible: Boolean)

    private external fun nativeLoopSetMessage(text: String): String

    private external fun nativeLoopSetEvents(spec: String): String

    private external fun nativeLoopEventCount(): Int

    private external fun nativeScenePictureBounds(): IntArray?

    private external fun nativeComposeScene(
        gameRoot: String,
        mapName: String,
        spec: String,
        width: Int,
        height: Int,
    ): ByteArray?

    private external fun nativeResetScene()

    private external fun nativeLoopSetChoices(spec: String): String

    private external fun nativeLoopChoiceCount(): Int

    private external fun nativeLoopPictureBounds(): IntArray?

    private external fun nativeLoopSetCameraExtra(dx: Int, dy: Int)

    private external fun nativeLoopRunScriptCommands(
        script: String,
        width: Int,
        height: Int,
    ): ByteArray?

    private external fun nativeLoopHasFont(): Boolean

    private external fun nativeLoopPictureCount(): Int

    private external fun nativeLoopStep(): Int

    private external fun nativeLoopInjectPointer(x: Float, y: Float, down: Boolean)

    private external fun nativeLoopInjectGamePointer(x: Int, y: Int, down: Boolean)

    private external fun nativeLoopPollKey(): Int

    private external fun nativeLoopCursor(): IntArray?

    private external fun nativeLoopStats(): DoubleArray?

    private external fun nativeLoopStop()

    private external fun nativeLoopRunScript(script: String): String?

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
