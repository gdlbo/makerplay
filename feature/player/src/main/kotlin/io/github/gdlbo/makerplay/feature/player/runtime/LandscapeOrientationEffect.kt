package io.github.gdlbo.makerplay.feature.player.runtime

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.gdlbo.makerplay.runtime.api.RuntimeOrientation
import io.github.gdlbo.makerplay.runtime.api.RuntimeSettings

@Composable
internal fun RuntimeDisplayEffect(settings: RuntimeSettings, enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, enabled, settings) {
        if (activity == null || !enabled) return@DisposableEffect onDispose { }
        val previousOrientation = activity.requestedOrientation
        val wasKeepingScreenOn =
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        val insetsController =
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        val previousSystemBarsBehavior = insetsController.systemBarsBehavior
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity.requestedOrientation = when (settings.orientation) {
            RuntimeOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            RuntimeOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            RuntimeOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        fun hideSystemBars() {
            if (settings.immersiveMode) insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
        if (settings.immersiveMode) {
            hideSystemBars()
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
        // System bars can reappear after a focus change (for example after the IME or a picker).
        // Re-apply immersive mode when the game window becomes active again.
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) hideSystemBars()
        }
        val viewTreeObserver = activity.window.decorView.viewTreeObserver
        viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        onDispose {
            if (viewTreeObserver.isAlive) {
                viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            }
            if (settings.immersiveMode) insetsController.show(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior = previousSystemBarsBehavior
            activity.requestedOrientation = previousOrientation
            if (!wasKeepingScreenOn) {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}