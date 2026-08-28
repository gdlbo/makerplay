package io.github.gdlbo.makerplay.runtime.webview.internal.lifecycle

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

internal class RuntimeAudioFocusController(
    private val requestFocus: () -> Boolean,
    private val abandonFocus: () -> Unit,
    private val onFocusChanged: (Boolean) -> Unit,
) {
    private var requested = false

    fun request() {
        if (!requested && requestFocus()) requested = true
    }

    fun abandon() {
        if (requested) {
            requested = false
            abandonFocus()
        }
    }

    fun dispatchFocusChange(hasFocus: Boolean) {
        if (requested) onFocusChanged(hasFocus)
    }

    companion object {
        fun create(
            context: Context,
            onFocusChanged: (Boolean) -> Unit
        ): RuntimeAudioFocusController {
            val audioManager = context.getSystemService(AudioManager::class.java)
            lateinit var controller: RuntimeAudioFocusController
            val listener = AudioManager.OnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> controller.dispatchFocusChange(true)
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                        -> controller.dispatchFocusChange(false)
                }
            }
            // USAGE_MEDIA matches Chromium WebView HTML5/WebAudio routing so side
            // volume controls adjust the same stream the game actually plays on.
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener(listener)
                .setAcceptsDelayedFocusGain(false)
                .build()
            controller = RuntimeAudioFocusController(
                requestFocus = {
                    audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                },
                abandonFocus = { audioManager.abandonAudioFocusRequest(request) },
                onFocusChanged = onFocusChanged,
            )
            return controller
        }
    }
}