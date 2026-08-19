package io.github.gdlbo.makerplay.runtime.webview

import io.github.gdlbo.makerplay.runtime.webview.internal.lifecycle.RuntimeAudioFocusController
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeAudioFocusControllerTest {
    @Test
    fun requestsAndAbandonsOnlyOnce() {
        var requests = 0
        var abandons = 0
        val controller = RuntimeAudioFocusController(
            requestFocus = { requests++; true },
            abandonFocus = { abandons++ },
            onFocusChanged = {},
        )

        controller.request()
        controller.request()
        controller.abandon()
        controller.abandon()

        assertEquals(1, requests)
        assertEquals(1, abandons)
    }

    @Test
    fun ignoresFocusEventsUntilFocusWasGranted() {
        val events = mutableListOf<Boolean>()
        val controller = RuntimeAudioFocusController(
            requestFocus = { false },
            abandonFocus = {},
            onFocusChanged = events::add,
        )

        controller.dispatchFocusChange(false)
        controller.request()
        controller.dispatchFocusChange(true)

        assertEquals(emptyList<Boolean>(), events)
    }
}
