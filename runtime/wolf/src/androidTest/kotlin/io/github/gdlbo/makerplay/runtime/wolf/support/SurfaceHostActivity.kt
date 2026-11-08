package io.github.gdlbo.makerplay.runtime.wolf.support

import android.app.Activity
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Minimal Activity that owns one [SurfaceView].
 *
 * Needed because a `SurfaceView` only receives a `Surface` once it is attached to a
 * real window. An instrumentation test has no Activity of its own, so without this
 * the "loop adopts the app's surface" check cannot create a surface to adopt.
 */
class SurfaceHostActivity : Activity() {
    lateinit var surfaceView: SurfaceView
        private set

    private val ready = CountDownLatch(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this).apply {
            holder.setFormat(PixelFormat.OPAQUE)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    ready.countDown()
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            })
        }
        setContentView(FrameLayout(this).apply { addView(surfaceView) })
    }

    /** Waits for the surface to exist, returning false on timeout. */
    fun awaitSurface(timeoutMs: Long = 5000): Boolean = ready.await(timeoutMs, TimeUnit.MILLISECONDS)
}
