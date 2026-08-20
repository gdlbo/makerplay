package io.github.gdlbo.makerplay.runtime.webview.internal.input

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import io.github.gdlbo.makerplay.input.LogicalInputSnapshot
import io.github.gdlbo.makerplay.input.PhysicalKeyAction

internal class RuntimeInputWebView(
    context: Context,
    onPhysicalInputChanged: (LogicalInputSnapshot) -> Unit = {},
) : WebView(context) {
    private val physicalInput = RuntimePhysicalInputCapture(onChanged = onPhysicalInputChanged)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isEnabled) return true
        when (event.action) {
            KeyEvent.ACTION_DOWN -> physicalInput.onKey(
                sourceId = event.physicalSourceId(),
                keyCode = event.keyCode,
                action = PhysicalKeyAction.DOWN,
                repeatCount = event.repeatCount,
            )

            KeyEvent.ACTION_UP -> physicalInput.onKey(
                sourceId = event.physicalSourceId(),
                keyCode = event.keyCode,
                action = PhysicalKeyAction.UP,
                repeatCount = event.repeatCount,
            )
        }
        // Observation must not replace WebView's DOM keyboard delivery.
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return true
        if (
            event.actionMasked == MotionEvent.ACTION_MOVE &&
            event.isFromSource(InputDevice.SOURCE_CLASS_JOYSTICK)
        ) {
            physicalInput.onAxes(
                sourceId = event.physicalSourceId(),
                x = event.getAxisValue(MotionEvent.AXIS_X),
                y = event.getAxisValue(MotionEvent.AXIS_Y),
                hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X),
                hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y),
            )
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        if (isEnabled) super.onTouchEvent(event) else true

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) physicalInput.clearAll()
    }

    fun clearPhysicalInput() {
        physicalInput.clearAll()
    }

    fun setOnPhysicalInputChanged(listener: (LogicalInputSnapshot) -> Unit) {
        physicalInput.setOnChanged(listener)
    }
}

private fun KeyEvent.physicalSourceId(): String = "device:$deviceId"

private fun MotionEvent.physicalSourceId(): String = "device:$deviceId"