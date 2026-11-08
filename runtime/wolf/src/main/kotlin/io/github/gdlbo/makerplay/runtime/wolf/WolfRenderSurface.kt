package io.github.gdlbo.makerplay.runtime.wolf

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import org.libsdl.app.SDLActivity

/**
 * Hosts the **Rust** SDL loop on the app's own surface.
 *
 * This class used to be a `GLSurfaceView` whose renderer asked Kotlin to draw each
 * frame (`bridge.renderFrame`), with Compose separately decoding input. That split
 * meant every rendering or input defect existed twice and had to be found and
 * fixed twice. The objective asks for one SDL3 loop owning render *and* input, and
 * this is it: the view only supplies a surface and forwards raw events, and all
 * engine behaviour lives in Rust.
 *
 * Two platform facts make this work, both established by measurement rather than
 * assumption:
 *
 *  - SDL's Android backend only became able to adopt a foreign `ANativeWindow`
 *    after the vendored patch in `SDL_androidwindow.c` (see
 *    `nativeLoopStartOnSurface`), because `Android_CreateWindow` otherwise took
 *    its window from `Android_JNI_GetNativeWindow()` and required an SDLActivity.
 *  - A view only receives the full touch stream if it is clickable, and the
 *    surface must not use `setZOrderOnTop` (which hides it from activity touch
 *    dispatch).
 */
class WolfRenderSurface(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    /** Loop configuration; set before the surface is created. */
    var loopTitle: String = "MakerPlay"
    var loopGameWidth: Int = 1280
    var loopGameHeight: Int = 960
    var loopGameRoot: String = ""
    var loopMapName: String = ""

    /**
     * Invoked once the Rust loop owns the surface, so the host can start whatever
     * timing it wants. The loop renders on its own thread from here on.
     */
    var onLoopReady: (() -> Unit)? = null

    /**
     * Called after each loop step with the input the loop's SDL queue holds.
     *
     * The app's gameplay engine stays in Kotlin, so the loop publishes what it
     * decoded and the engine consumes it. This is the whole point of routing input
     * through SDL: there is one decode, in Rust, and the engine reads its result
     * rather than a parallel Compose path that could disagree.
     */
    var onLoopInput: ((key: Int, cursorX: Int, cursorY: Int, down: Boolean) -> Unit)? = null

    /** Reported loop-start error, empty when the loop is running. */
    @Volatile
    var loopError: String = "loop not started"
        private set

    private var renderThread: Thread? = null

    @Volatile
    private var running = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isEnabled = true
        setZOrderMediaOverlay(true)
        holder.setFormat(PixelFormat.OPAQUE)
        holder.addCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { requestFocus() }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) requestFocus()
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (running) return
        startLoop(holder, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopLoop()
    }

    /**
     * Starts the loop **on the render thread**.
     *
     * The GL context is created together with the window, and EGL contexts are
     * thread-local, so creating the loop on the UI thread and presenting from
     * another thread would fail at the first swap. Creating both on the render
     * thread is what `GLSurfaceView` does for the same reason.
     */
    private fun startLoop(holder: SurfaceHolder, width: Int, height: Int) {
        if (renderThread != null) return
        val surface = holder.surface
        renderThread = Thread({
            val jni = WolfNativeJni()
            if (!jni.isAvailable) {
                loopError = "native library unavailable"
                Log.w(TAG, loopError)
                return@Thread
            }
            val error = jni.loopStartOnSurface(
                surface = surface,
                title = loopTitle,
                gameWidth = loopGameWidth,
                gameHeight = loopGameHeight,
                gameRoot = "",
                mapName = "",
            )
            loopError = error
            if (error.isNotEmpty()) {
                Log.e(TAG, "loop start failed: $error")
                return@Thread
            }
            running = true
            Log.i(TAG, "loop owns surface ${width}x${height}")
            onLoopReady?.invoke()
            while (running) {
                val alive = runCatching { jni.loopStep() }.getOrElse { failure ->
                    Log.e(TAG, "loop step failed", failure)
                    false
                }
                if (!alive) {
                    running = false
                    break
                }
                val key = jni.loopPollKey()
                val cursor = jni.loopCursor()
                onLoopInput?.invoke(
                    key,
                    cursor?.first ?: -1,
                    cursor?.second ?: -1,
                    key == SDL_KEY_LEFT_DOWN,
                )
            }
        }, "wolf-render").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun stopLoop() {
        running = false
        renderThread?.join(1000)
        renderThread = null
        runCatching { WolfNativeJni().loopStop() }
    }

    // ---- Input ----------------------------------------------------------------
    //
    // Touches reach the app through Compose, not this view: the media-overlay
    // surface sits behind the window, so `onTouchEvent` here is never called
    // (measured: zero calls for a tap). `forwardPointerToSdl` is what the Compose
    // layer calls instead, and it hands the event to SDL untouched.

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // WOLF reads keyboard codes 8..11 and 10/11 for OK/Cancel; SDL translates
        // Android keycodes itself, so the loop applies WOLF's own mapping in Rust.
        SDLActivity.onNativeKeyDown(keyCode)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        SDLActivity.onNativeKeyUp(keyCode)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
            SDLActivity.onNativeMouse(
                0,
                SDL_MOUSE_MOVE,
                event.x,
                event.y,
                false,
            )
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    companion object {
        private const val TAG = "WolfRenderSurface"

        /**
         * Forwards a raw `MotionEvent` to SDL without interpreting it.
         *
         * Compose owns the window, so the SurfaceView's media-overlay surface is
         * behind it and never sees `onTouchEvent`. Forwarding the untouched event
         * keeps a single decode: Rust decides what a press means.
         */
        /**
         * Forwards a press to SDL in **normalised** coordinates.
         *
         * `SDL_SendTouch` multiplies the incoming position by the window size, so the
         * JNI entry point expects 0..1 -- `SDLSurface.getNormalizedX` divides by the
         * view width before calling it. Passing raw pixels (the first version did)
         * sends every press beyond the far edge, where it clamps, so the cursor
         * landed in the bottom-right corner no matter where the user tapped and no
         * picture-menu row could ever be hit.
         */
        fun forwardPointerToSdl(
            x: Float,
            y: Float,
            down: Boolean,
            viewWidth: Int,
            viewHeight: Int,
        ) {
            val nx = if (viewWidth > 1) (x / (viewWidth - 1)).coerceIn(0f, 1f) else 0.5f
            val ny = if (viewHeight > 1) (y / (viewHeight - 1)).coerceIn(0f, 1f) else 0.5f
            SDLActivity.onNativeTouch(
                0,
                0,
                if (down) ACTION_DOWN else ACTION_UP,
                nx,
                ny,
                1.0f,
            )
        }

        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_POINTER_DOWN = 5
        const val ACTION_POINTER_UP = 6
        const val SDL_MOUSE_MOVE = 2

        /** WOLF mouse-left code, as produced by the loop for a left button press. */
        const val SDL_KEY_LEFT_DOWN = 20
    }
}
