package io.github.gdlbo.makerplay.runtime.wolf

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TmpNativeStartupProbeTest {
    @Test fun traceNativeStartup() {
        val jni = WolfNativeJni.tryCreate()!!
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val intent=android.content.Intent(instrumentation.targetContext,
            io.github.gdlbo.makerplay.runtime.wolf.support.SurfaceHostActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity=instrumentation.startActivitySync(intent) as io.github.gdlbo.makerplay.runtime.wolf.support.SurfaceHostActivity
        assertTrue(activity.awaitSurface())
        assertEquals("", jni.loopStartOnSurface(activity.surfaceView.holder.surface,"Native startup probe",1280,960,
            "/data/local/tmp/wolffix/The Clumsy Witch Seris", "000title"))
        try {
            assertEquals("", jni.loopInitializeGameplay())
            for (frame in 0 until 1600) {
                if (frame==900) org.libsdl.app.SDLActivity.onNativeKeyDown(20)
                if (frame==910) org.libsdl.app.SDLActivity.onNativeKeyUp(20)
                if (frame==300 || frame==1000 || frame==1250) {
                    org.libsdl.app.SDLActivity.onNativeKeyDown(66)
                }
                if (frame==330 || frame==1030 || frame==1280) {
                    org.libsdl.app.SDLActivity.onNativeKeyUp(66)
                }
                try { assertTrue("native frame $frame", jni.loopStep()) }
                catch (error: Throwable) { throw AssertionError("native startup frame=$frame: ${error.message}", error) }
                Thread.sleep(16)
            }
            println("NATIVE_STATE " + jni.loopGameplayDiagnostics())
            val payload = jni.loopFrame(1280,960)!!
            val bitmap=android.graphics.Bitmap.createBitmap(1280,960,android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(payload,8,payload.size-8))
            val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            java.io.File(context.filesDir,"native-startup.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
        } finally { jni.loopStop(); instrumentation.runOnMainSync {activity.finish()} }
    }
}
