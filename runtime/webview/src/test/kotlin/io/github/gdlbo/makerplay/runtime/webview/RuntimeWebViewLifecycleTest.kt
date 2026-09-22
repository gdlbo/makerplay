package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.runtime.webview.internal.lifecycle.RuntimeWebViewLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeWebViewLifecycleTest {
    @Test
    fun pauseAndResumeTransitionsAreIdempotent() {
        val calls = mutableListOf<String>()
        val lifecycle = RuntimeWebViewLifecycle(
            pause = { calls += "pause" },
            resume = { calls += "resume" },
            release = { calls += "release" },
        )

        lifecycle.onResume()
        lifecycle.onPause()
        lifecycle.onPause()
        lifecycle.onResume()
        lifecycle.onResume()

        assertEquals(listOf("pause", "resume"), calls)
    }

    @Test
    fun releasePausesOnceAndRejectsLaterResume() {
        val calls = mutableListOf<String>()
        val lifecycle = RuntimeWebViewLifecycle(
            pause = { calls += "pause" },
            resume = { calls += "resume" },
            release = { calls += "release" },
        )

        lifecycle.onRelease()
        lifecycle.onRelease()
        lifecycle.onResume()

        assertEquals(listOf("pause", "release"), calls)
    }

    @Test
    fun releaseStillRunsWhenPauseThrows() {
        val calls = mutableListOf<String>()
        val lifecycle = RuntimeWebViewLifecycle(
            pause = {
                calls += "pause"
                throw IllegalStateException("pause failed")
            },
            resume = { calls += "resume" },
            release = { calls += "release" },
        )

        assertThrows(IllegalStateException::class.java) { lifecycle.onRelease() }
        lifecycle.onRelease()
        lifecycle.onResume()

        assertEquals(listOf("pause", "release"), calls)
    }
}
