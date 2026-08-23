package io.github.gdlbo.makerplay.runtime.wolf

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/**
 * Minimal BGM/SE playback for the WOLF runtime (milestone 7): loops BGM from
 * the deployment's media folders via MediaPlayer. Failures degrade to no-op —
 * a missing or unsupported file must never crash playback.
 */
class WolfAudioPlayer {

    private var bgm: MediaPlayer? = null
    private var currentBgmPath: String? = null

    fun playBgm(file: File, volume: Float = 1.0f) {
        if (!file.isFile) return
        if (currentBgmPath == file.canonicalPath) return // already playing
        stopBgm()
        runCatching {
            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            player.setDataSource(file.canonicalPath)
            player.isLooping = true
            player.setVolume(volume, volume)
            player.prepareAsync()
            player.setOnPreparedListener { it.start() }
            bgm = player
            currentBgmPath = file.canonicalPath
        }
    }

    fun stopBgm() {
        runCatching {
            bgm?.stop()
            bgm?.release()
        }
        bgm = null
        currentBgmPath = null
    }

    /** Fire-and-forget sound effect; each call uses an isolated player. */
    fun playSe(file: File, volume: Float = 1.0f) {
        if (!file.isFile) return
        runCatching {
            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            player.setDataSource(file.canonicalPath)
            player.setVolume(volume, volume)
            player.setOnCompletionListener { it.release() }
            player.prepareAsync()
            player.start()
        }
    }

    fun release() = stopBgm()
}
