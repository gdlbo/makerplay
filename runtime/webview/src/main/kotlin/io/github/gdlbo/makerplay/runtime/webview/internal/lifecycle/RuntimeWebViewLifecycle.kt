package io.github.gdlbo.makerplay.runtime.webview.internal.lifecycle

internal class RuntimeWebViewLifecycle(
    private val pause: () -> Unit,
    private val resume: () -> Unit,
    private val release: () -> Unit,
) {
    // WebView is live immediately after creation; lifecycle events only transition it.
    private var active = true
    private var released = false

    fun onResume() {
        if (!released && !active) {
            active = true
            resume()
        }
    }

    fun onPause() {
        if (active) {
            active = false
            pause()
        }
    }

    fun onRelease() {
        if (!released) {
            onPause()
            released = true
            release()
        }
    }
}