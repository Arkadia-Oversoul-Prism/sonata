package com.arkadia.sonata

/**
 * Plays a local audio file. Extracted so playback state transitions, supersession
 * and file cleanup can be tested without `MediaPlayer` or a device.
 *
 * Position semantics: [currentPositionMs] is an audio offset into the file, and
 * pause/resume on the same prepared player continues the same decoded stream.
 */
interface AudioPlayer {

    /**
     * Prepare [file] and begin playback. Must be called on the main thread.
     *
     * Returns false when playback could not *start* (bad or unreadable audio);
     * [onError] reports failures that occur *after* a successful start. The
     * distinction lets the engine fall back without double-signalling.
     */
    fun play(file: java.io.File, onCompletion: () -> Unit, onError: (String) -> Unit): Boolean

    /** Suspend playback at the current position. Returns true if it was playing. */
    fun pause(): Boolean

    /** Continue from the suspended position. Returns true if it was paused. */
    fun resume(): Boolean

    /** Current playback offset in milliseconds. */
    fun currentPositionMs(): Int

    /** Whether audio is actively playing. */
    fun isPlaying(): Boolean

    /** Stop, release, and relinquish the current file. */
    fun stop()
}
