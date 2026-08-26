package io.github.gdlbo.makerplay.runtime.wolf

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.KeyEvent
import io.github.gdlbo.makerplay.runtime.api.WolfNativeBridge
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** GLSurfaceView host that drives the native WOLF renderer on the GL thread. */
class WolfRenderSurface(context: Context) : GLSurfaceView(context) {
    private var rendererRef: WolfFrameRenderer? = null
    var onKeyAction: ((keyCode: Int, down: Boolean) -> Boolean)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { requestFocus() }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) requestFocus()
    }

    fun setRenderer(bridge: WolfNativeBridge, handle: Long) {
        // Render above the Compose window; the default punched-through layer
        // can end up hidden behind the host view hierarchy on some stacks.
        setZOrderOnTop(true)
        holder.setFormat(android.graphics.PixelFormat.OPAQUE)
        val renderer = WolfFrameRenderer(bridge, handle)
        rendererRef = renderer
        super.setRenderer(renderer)
    }

    fun setHandle(handle: Long) {
        rendererRef?.handle = handle
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (onKeyAction?.invoke(keyCode, true) == true) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (onKeyAction?.invoke(keyCode, false) == true) return true
        return super.onKeyUp(keyCode, event)
    }
}

/** Presents the native frame each tick; native owns scaling and presentation. */
class WolfFrameRenderer(
    private val bridge: WolfNativeBridge,
    @Volatile var handle: Long,
) : GLSurfaceView.Renderer {

    private var width = 1
    private var height = 1

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = Unit

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        bridge.renderFrame(handle, this.width, this.height)
    }

    override fun onDrawFrame(gl: GL10?) {
        bridge.renderFrame(handle, width, height)
    }
}
