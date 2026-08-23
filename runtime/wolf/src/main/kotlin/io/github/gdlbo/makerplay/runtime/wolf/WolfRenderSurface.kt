package io.github.gdlbo.makerplay.runtime.wolf

import android.content.Context
import android.opengl.GLSurfaceView
import io.github.gdlbo.makerplay.runtime.api.WolfNativeBridge
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** GLSurfaceView host that drives the native WOLF renderer on the GL thread. */
class WolfRenderSurface(context: Context) : GLSurfaceView(context) {
    private var rendererRef: WolfFrameRenderer? = null

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
