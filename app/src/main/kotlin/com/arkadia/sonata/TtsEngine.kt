package com.arkadia.sonata

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val TAG = "SonataTts"

/**
 * Two-tier TTS engine.
 *
 *  Tier 1 (online, neural) — `POST {backendUrl}/api/tts`; the returned audio is
 *    written to a cache file and played by [AudioPlayer].
 *  Tier 2 (offline) — the platform `TextToSpeech` engine via [OfflineVoice].
 *
 * Guarantees this class is responsible for:
 *
 *  - **Single owner.** Every [speak] supersedes any in-flight request: the old
 *    request is cancelled and its file deleted, and a superseded request can
 *    never start playback or clobber the new one.
 *  - **Bounded input.** Text is validated and truncated to the backend's own
 *    ceiling before it is sent ([TextLimits]).
 *  - **Cleanup on every exit path.** Completion, error, supersession, [stop] and
 *    [shutdown] all delete the temp file.
 *  - **Honest fallback.** Any online failure — transport error, non-success HTTP,
 *    non-audio content type, empty body, undecodable audio — falls back to offline
 *    speech and reports why.
 *  - **Honest resume.** Online pause/resume continues the same decoded stream.
 *    Offline pause stops the utterance and records the character reached; resume
 *    re-speaks the remainder. That is a content resume with a re-synthesis seam,
 *    not sample-accurate resumption, and it is reported as such.
 */
class TtsEngine(
    private val settings: TtsSettings,
    private val cacheDir: File,
    private val transport: TtsTransport,
    private val player: AudioPlayer,
    private val offline: OfflineVoice,
    scope: CoroutineScope? = null,
    /**
     * The dispatcher for state mutations and listener callbacks. Production uses
     * the main thread so state changes are serialised; tests supply the test
     * scheduler, because `Dispatchers.Main` has no JVM implementation.
     */
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {

    /**
     * Whether this engine created its own scope. Only a scope the engine owns is
     * cancelled on [shutdown]: cancelling an injected scope would tear down the
     * caller's, which is exactly what happened to the test scheduler.
     */
    private val ownsScope = scope == null

    private val scope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    interface Listener {
        /** Playback of the current utterance started, on [engine]. */
        fun onStart(engine: TtsEngineKind)

        /** Playback paused; the utterance is resumable. */
        fun onPaused(engine: TtsEngineKind)

        /** Playback continued from the pause point. */
        fun onResumed(engine: TtsEngineKind)

        /** The utterance finished. */
        fun onComplete()

        /**
         * Something went wrong. [msg] is user-presentable; [fellBackTo] is
         * non-null when offline speech took over as a result.
         */
        fun onError(msg: String, fellBackTo: TtsEngineKind?)
    }

    var listener: Listener? = null

    /** The authoritative playback state. */
    private var state: PlaybackState = PlaybackState.Idle

    val playbackState: PlaybackState get() = state

    /** The in-flight request, if any; used to supersede it. */
    private var inFlight: Job? = null

    /**
     * Monotonic request token. Incremented by every [speak]/[stop], so a result
     * arriving after its request was superseded is dropped instead of playing.
     */
    private var generation: Int = 0

    /** The temp file owned by the current request, if any. */
    private var currentFile: File? = null

    /**
     * Set while [speakOffline] is dispatching, so the progress callback is only
     * accepted for the utterance actually being started.
     *
     * Without this, a *late* `onRange` from an utterance that [pause] already
     * stopped can overwrite the resume point — the platform engine is not required
     * to have stopped reporting synchronously — and [resume] would then re-speak
     * from the wrong character. Progress is accepted only when this is true, which
     * is the window during which the current utterance is genuinely speaking.
     */
    private var acceptingProgress = false

    val isPlaying: Boolean get() = state.isPlaying
    val isPaused: Boolean get() = state.isPaused
    val currentEngine: TtsEngineKind? get() = state.engine

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Speak [raw].
     *
     * Empty or whitespace-only input is refused with a reported error rather than
     * being sent to the backend or silently ignored.
     */
    fun speak(raw: CharSequence?) {
        supersede()

        when (val validation = TextLimits.validate(raw)) {
            is TextValidation.Empty -> {
                state = PlaybackState.Idle
                listener?.onError("Nothing to read", null)
            }

            is TextValidation.Ready -> start(validation.text, truncatedFrom = null)

            is TextValidation.Truncated -> start(
                validation.text,
                truncatedFrom = validation.originalLength,
            )
        }
    }

    /**
     * Pause the current utterance.
     *
     * No-op when nothing is playing, so a stray notification or overlay tap
     * cannot invent a paused state.
     */
    fun pause() {
        when (val current = state) {
            is PlaybackState.Online -> {
                if (player.pause()) {
                    state = current.copy(positionMs = player.currentPositionMs(), paused = true)
                    listener?.onPaused(TtsEngineKind.ONLINE)
                }
            }

            is PlaybackState.Offline -> {
                offline.stop()
                acceptingProgress = false
                state = current.copy(paused = true)
                listener?.onPaused(TtsEngineKind.OFFLINE)
            }

            PlaybackState.Idle -> Unit
        }
    }

    /**
     * Resume a paused utterance.
     *
     * Reports an error when there is nothing resumable rather than claiming a
     * resume that did not happen.
     */
    fun resume() {
        when (val current = state) {
            is PlaybackState.Online -> {
                if (current.paused && player.resume()) {
                    state = current.copy(paused = false)
                    listener?.onResumed(TtsEngineKind.ONLINE)
                } else if (current.paused) {
                    listener?.onError("Could not resume audio", null)
                }
            }

            is PlaybackState.Offline -> {
                if (!current.paused) return
                if (current.remainingText() == null) {
                    // Paused at the very end — nothing left to speak.
                    finish()
                    return
                }
                val from = current.charOffset
                state = current.copy(paused = false)
                listener?.onResumed(TtsEngineKind.OFFLINE)
                speakOffline(current.text, fromChar = from, reportedFallback = null)
            }

            PlaybackState.Idle -> listener?.onError("Nothing to resume", null)
        }
    }

    /** Stop and release everything. Safe to call in any state. */
    fun stop() {
        supersede()
        state = PlaybackState.Idle
    }

    /** Release all resources. */
    fun shutdown() {
        supersede()
        state = PlaybackState.Idle
        (offline as? AndroidOfflineVoice)?.shutdown()
        transport.close()
        if (ownsScope) scope.cancel()
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Cancel any in-flight request and release current playback, deleting the temp
     * file. Called before every new request and by [stop] / [shutdown].
     */
    private fun supersede() {
        generation++
        inFlight?.cancel()
        inFlight = null
        player.stop()
        offline.stop()
        acceptingProgress = false
        currentFile?.delete()
        currentFile = null
    }

    private fun start(text: String, truncatedFrom: Int?) {
        if (truncatedFrom != null) {
            // Say what will actually be read, before reading it.
            listener?.onError(
                "Text truncated to $MAX_TTS_CHARS characters ($truncatedFrom offered)",
                null,
            )
        }

        val url = settings.backendUrl.trim()
        if (settings.preferOnline && url.isNotEmpty()) {
            speakOnline(text, url)
        } else {
            speakOffline(text, fromChar = 0, reportedFallback = null)
        }
    }

    /**
     * The effective speech rate. The backend clamps speed to `[0.5, 2.0]` itself,
     * so the engine clamps at the source: a value the UI offers but the backend
     * silently rewrites would make the two disagree about what is being spoken.
     */
    private fun effectiveSpeed(): Float = SpeedSteps.clamp(settings.speed)

    private fun speakOnline(text: String, url: String) {
        val myGeneration = generation
        val job = scope.launch {
            val result = transport.fetch(
                text = text,
                voice = settings.voice,
                speed = effectiveSpeed(),
                baseUrl = url,
            )

            // Superseded while the request was in flight: drop the result.
            if (myGeneration != generation || !isActive) return@launch

            when (result) {
                is SynthesisResult.Success -> {
                    val file = try {
                        writeTempFile(result.bytes)
                    } catch (e: Exception) {
                        Log.w(TAG, "could not stage audio: ${e.message}")
                        withContext(mainDispatcher) {
                            if (myGeneration == generation) {
                                reportFallback(text, "Could not store audio")
                            }
                        }
                        return@launch
                    }

                    if (myGeneration != generation) {
                        file.delete()
                        return@launch
                    }

                    withContext(mainDispatcher) {
                        // Re-check on the main thread: a newer speak() may have run
                        // between the check above and this hop.
                        if (myGeneration != generation) {
                            file.delete()
                        } else {
                            playStagedFile(file, text)
                        }
                    }
                }

                is SynthesisResult.Failure -> {
                    withContext(mainDispatcher) {
                        if (myGeneration == generation) reportFallback(text, result.reason)
                    }
                }
            }
        }
        inFlight = job
    }

    private fun playStagedFile(file: File, text: String) {
        currentFile = file

        val started = player.play(
            file = file,
            onCompletion = { finish() },
            onError = { msg -> onPlaybackError(msg) },
        )

        if (started) {
            state = PlaybackState.Online(text = text, positionMs = 0, paused = false)
            listener?.onStart(TtsEngineKind.ONLINE)
        } else {
            // A 200 carrying bytes that are not decodable audio lands here.
            Log.w(TAG, "staged audio could not be played; falling back")
            file.delete()
            currentFile = null
            reportFallback(text, "Audio could not be played")
        }
    }

    /**
     * Speak [text] offline from [fromChar].
     *
     * The state is committed before [OfflineVoice.speak] so the progress callback
     * has a state to update, and is rolled back if the utterance fails to start.
     */
    private fun speakOffline(text: String, fromChar: Int, reportedFallback: String?) {
        if (!offline.isReady()) {
            state = PlaybackState.Idle
            listener?.onError(
                reportedFallback?.let { "$it. Offline voice is unavailable" }
                    ?: "Offline voice is unavailable",
                null,
            )
            return
        }

        state = PlaybackState.Offline(text = text, charOffset = fromChar, paused = false)
        acceptingProgress = true
        var started = false

        offline.speak(
            text = text,
            fromChar = fromChar,
            speed = effectiveSpeed(),
            pitch = settings.pitch,
            onStart = {
                if (!started) {
                    started = true
                    listener?.onStart(TtsEngineKind.OFFLINE)
                }
            },
            onRange = { reached ->
                // Reject progress from a superseded or paused utterance: accepting
                // it would move the resume point the user is relying on.
                val current = state
                if (acceptingProgress && current is PlaybackState.Offline && !current.paused) {
                    state = current.copy(charOffset = reached)
                }
            },
            onDone = {
                if (acceptingProgress && state is PlaybackState.Offline) {
                    acceptingProgress = false
                    finish()
                }
            },
            onError = { msg ->
                if (acceptingProgress && state is PlaybackState.Offline) {
                    acceptingProgress = false
                    state = PlaybackState.Idle
                    listener?.onError(
                        reportedFallback?.let { "$it. $msg" } ?: msg,
                        null,
                    )
                }
            },
        )

        // Some platform engines never invoke onStart; report the start here if so.
        if (!started && state is PlaybackState.Offline) {
            listener?.onStart(TtsEngineKind.OFFLINE)
        }
    }

    private fun reportFallback(text: String, reason: String) {
        Log.w(TAG, "online synthesis failed ($reason); falling back to offline voice")

        if (!offline.isReady()) {
            // There is nothing to fall back to, so do not report one: claiming a
            // fallback that cannot happen would misstate what the user is hearing.
            state = PlaybackState.Idle
            listener?.onError("$reason. Offline voice is unavailable", null)
            return
        }

        // Report the degradation even though speech continues: the user asked for
        // the neural voice and is about to hear the platform one, so a silent
        // substitution would misrepresent what is being spoken.
        listener?.onError(reason, TtsEngineKind.OFFLINE)
        speakOffline(text, fromChar = 0, reportedFallback = null)
    }

    private fun onPlaybackError(msg: String) {
        currentFile?.delete()
        currentFile = null
        state = PlaybackState.Idle
        listener?.onError(msg, null)
    }

    private fun finish() {
        currentFile?.delete()
        currentFile = null
        state = PlaybackState.Idle
        listener?.onComplete()
    }

    private fun writeTempFile(bytes: ByteArray): File {
        val file = File(cacheDir, "sonata_${UUID.randomUUID()}.mp3")
        file.writeBytes(bytes)
        return file
    }
}
