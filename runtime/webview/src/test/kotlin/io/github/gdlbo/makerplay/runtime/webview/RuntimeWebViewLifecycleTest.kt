package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.runtime.webview.internal.lifecycle.RuntimeWebViewLifecycle
import org.junit.Assert.assertEquals
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
}
