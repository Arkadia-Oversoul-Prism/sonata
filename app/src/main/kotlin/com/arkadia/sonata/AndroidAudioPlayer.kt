package com.arkadia.sonata

import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * [AudioPlayer] backed by `MediaPlayer`.
 *
 * Pause/resume operate on the same prepared player, so resuming continues the
 * decoded stream from the pause point rather than restarting the file.
 *
 * The player relinquishes ownership of the file on stop so exactly one component
 * is responsible for deleting it (the engine owns cleanup).
 */
class AndroidAudioPlayer : AudioPlayer {

    private var player: MediaPlayer? = null
    private var ownedFile: File? = null
    private var paused = false

    override fun play(file: File, onCompletion: () -> Unit, onError: (String) -> Unit): Boolean {
        stop()

        val mp = MediaPlayer()
        player = mp
        ownedFile = file

        return try {
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                paused = false
                onCompletion()
            }
            mp.setOnErrorListener { _, what, extra ->
                paused = false
                onError("Playback failed ($what/$extra)")
                true
            }
            mp.prepare()
            mp.start()
            true
        } catch (e: Exception) {
            // Covers a corrupt or truncated download reaching the player.
            Log.w(TAG, "MediaPlayer could not play ${file.name}: ${e.message}")
            releaseQuietly(mp)
            if (player === mp) {
                player = null
                ownedFile = null
            }
            false
        }
    }

    override fun pause(): Boolean {
        val mp = player ?: return false
        if (!mp.isPlaying) return false
        return try {
            mp.pause()
            paused = true
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "pause failed: ${e.message}")
            false
        }
    }

    override fun resume(): Boolean {
        val mp = player ?: return false
        if (!paused) return false
        return try {
            mp.start()
            paused = false
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "resume failed: ${e.message}")
            false
        }
    }

    override fun currentPositionMs(): Int = try {
        player?.currentPosition ?: 0
    } catch (e: IllegalStateException) {
        0
    }

    override fun isPlaying(): Boolean = try {
        player?.isPlaying == true
    } catch (e: IllegalStateException) {
        false
    }

    override fun stop() {
        player?.let { releaseQuietly(it) }
        player = null
        ownedFile = null
        paused = false
    }

    private fun releaseQuietly(mp: MediaPlayer) {
        try {
            if (mp.isPlaying) mp.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stop failed: ${e.message}")
        }
        try {
            mp.reset()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "reset failed: ${e.message}")
        }
        mp.release()
    }

    private companion object {
        const val TAG = "SonataTts"
    }
}
