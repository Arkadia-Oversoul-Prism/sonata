package com.arkadia.sonata

import android.util.Log
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SonataTts"

/**
 * Two-tier TTS engine, reading a passage progressively.
 *
 *  Tier 1 (online, neural) — `POST {backendUrl}/api/tts`; the returned audio is
 *    written to a cache file and played by [AudioPlayer].
 *  Tier 2 (offline) — the platform `TextToSpeech` engine via [OfflineVoice].
 *
 * **Why the passage is split.** Synthesis latency scales with the length of the
 * request. Measured against the live gateway, a 340-character request returned in
 * ~1.8 s while a single 5000-character request took ~29 s. The previous
 * implementation asked for the whole passage as one request and waited for all of
 * it before a single sample played, which is why speech did not begin immediately
 * and why a long passage appeared to have a length limit. This engine splits the
 * passage ([SpeechChunker.splitForStreaming]), fetches a short first piece so
 * speech starts in about a second, and fetches the following pieces *while the
 * current one is playing*, so the read continues without a gap.
 *
 * Guarantees this class is responsible for:
 *
 *  - **Single owner.** Every [speak] supersedes any in-flight work: outstanding
 *    requests are cancelled, their files deleted, and a superseded request can
 *    never start playback or clobber the new one.
 *  - **Nothing lost, nothing repeated.** The pieces partition the input exactly
 *    ([SpeechChunker] pins `joinToString("") == text`), and they are played in
 *    order, so the whole passage is read once.
 *  - **Bounded input.** Text is validated and bounded before anything is sent
 *    ([TextLimits]); a piece is never longer than the backend's own ceiling.
 *  - **Cleanup on every exit path.** Completion, error, supersession, [stop] and
 *    [shutdown] delete every staged file.
 *  - **Honest fallback.** Any online failure falls back to offline speech from the
 *    piece that failed, so the rest of the passage is still heard, and the reason
 *    is reported. When the offline engine is also unavailable, the failure is
 *    reported without a fallback rather than claiming one that cannot happen.
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
    /**
     * How long the offline path waits for the platform engine to finish
     * initialising before reporting it unavailable.
     *
     * Injectable because initialisation cost is device-dependent: sub-second on
     * hardware, but inflated by orders of magnitude on a software-emulated
     * (no-KVM) Android runtime. Device tests raise it so they measure behaviour
     * rather than emulation speed; production uses [DEFAULT_OFFLINE_READY_TIMEOUT_MS].
     */
    private val offlineReadyTimeoutMs: Long = DEFAULT_OFFLINE_READY_TIMEOUT_MS,
    /**
     * How many pieces ahead of the one needed next to fetch.
     *
     * Zero is a valid value and means a strictly sequential read: each piece is
     * requested only once the previous one has finished playing. One is enough to
     * hide synthesis latency without holding a whole long passage in the cache,
     * because the next piece is synthesised during the current piece's playback.
     */
    private val prefetchDepth: Int = 1,
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

    /** The in-flight offline readiness wait, if any; used to supersede it. */
    private var inFlight: Job? = null

    /**
     * Monotonic request token. Incremented by every [speak]/[stop], so a result
     * arriving after its request was superseded is dropped instead of playing.
     */
    private var generation: Int = 0

    /** The file owned by the piece currently playing, if any. */
    private var currentFile: File? = null

    // ── The segmented read ────────────────────────────────────────────────────

    /** One request-sized piece of the passage. [startChar] is where it begins. */
    private data class Segment(val text: String, val startChar: Int)

    /** The passage as offered, so progress can be reported against the whole. */
    private var fullText = ""

    private var segments: List<Segment> = emptyList()

    /** Base URL of the in-progress online read. */
    private var onlineUrl = ""

    /** Index of the piece currently playing, or -1 before the first one starts. */
    private var playIndex = -1

    /** Staged but not yet playing, keyed by piece index. */
    private val staged = ConcurrentHashMap<Int, File>()

    /** In-flight fetches, keyed by piece index. */
    private val fetchJobs = ConcurrentHashMap<Int, Job>()

    /** Pieces whose fetch failed, with the reason, so the read can fall back. */
    private val failed = ConcurrentHashMap<Int, String>()

    /** Set while playback has run past the end of a piece with the next one unfetched. */
    private var awaitingNext = false

    /**
     * True while a piece is actually in the player.
     *
     * [advanceIfPossible] must never start a piece while one is playing. With
     * prefetching enabled the next piece is synthesised *during* the current one, so
     * "the next piece is available" is the normal condition rather than a signal that
     * the current one has ended. Advancing on availability alone cut every piece off
     * mid-sentence and hopped forward through the passage — the read "jumped the
     * text" and made no sense.
     */
    private var playingSegment = false

    /**
     * The tier already announced through [Listener.onStart]. Segmentation means
     * several pieces play in one logical utterance, so the start is reported once
     * for the read rather than once per piece.
     */
    private var reportedEngine: TtsEngineKind? = null

    /**
     * Set while [startOfflineUtterance] is dispatching, so the progress callback is
     * only accepted for the utterance actually being started.
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

    /** True while the read has run past a piece and is waiting for the next one. */
    val isBufferingNext: Boolean get() = awaitingNext

    /** Characters spoken so far, for an approximate progress read. */
    val spokenChars: Int
        get() = when (val current = state) {
            is PlaybackState.Offline -> current.charOffset
            is PlaybackState.Online -> segments.getOrNull(playIndex)?.startChar ?: 0
            PlaybackState.Idle -> 0
        }

    /** Characters in the passage being read. */
    val totalChars: Int get() = fullText.length

    /** How many pieces the current read is split into; 0 when offline or idle. */
    val segmentCount: Int get() = segments.size

    /** 1-based index of the piece playing; 0 when nothing is segmented. */
    val segmentIndex: Int get() = if (playIndex < 0) 0 else playIndex + 1

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Speak [raw].
     *
     * Empty or whitespace-only input is refused with a reported error rather than
     * being sent to the backend or silently ignored.
     */
    fun speak(raw: CharSequence?) = speakFrom(raw, fromChar = 0)

    /**
     * Speak [raw], starting at [fromChar].
     *
     * This is what makes a passage survivable across app launches: a read-aloud app
     * is used in bursts, and coming back to a long passage and hearing it restart
     * from the top is the difference between usable and not. The engine is the right
     * place for the offset because it already knows where each piece begins.
     *
     * The offset is clamped into the text, so a stale stored position — the passage
     * was edited shorter in the meantime — starts the read somewhere valid rather
     * than at the end or past it.
     */
    fun speakFrom(raw: CharSequence?, fromChar: Int) {
        supersede()

        when (val validation = TextLimits.validate(raw)) {
            is TextValidation.Empty -> {
                state = PlaybackState.Idle
                listener?.onError("Nothing to read", null)
            }

            is TextValidation.Ready ->
                start(validation.text, truncatedFrom = null, fromChar = fromChar)

            is TextValidation.Truncated -> start(
                validation.text,
                truncatedFrom = validation.originalLength,
                fromChar = fromChar,
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
                // Between pieces there is nothing to pause in the player, but the
                // read must still stop advancing — otherwise pausing would be a
                // no-op the user could not distinguish from a failure.
                val paused = if (awaitingNext) true else player.pause()
                if (paused) {
                    playingSegment = false
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
                if (!current.paused) return
                state = current.copy(paused = false)
                listener?.onResumed(TtsEngineKind.ONLINE)

                // The player is the authority on whether a stream was paused
                // mid-piece. A read paused *between* pieces has nothing to resume, so
                // ask the player first and fall through to starting the next piece.
                // (Deciding this from a bookkeeping flag went wrong as soon as the
                // flag and the player could disagree.)
                if (player.resume()) {
                    playingSegment = true
                } else {
                    advanceIfPossible()
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
     * Cancel any outstanding work and release current playback, deleting every
     * staged file. Called before every new request and by [stop] / [shutdown].
     */
    private fun supersede() {
        generation++
        inFlight?.cancel()
        inFlight = null
        fetchJobs.values.forEach { it.cancel() }
        fetchJobs.clear()
        player.stop()
        offline.stop()
        acceptingProgress = false
        // The playing piece is tracked separately, so it is not in `staged`.
        currentFile?.delete()
        currentFile = null
        staged.values.forEach { it.delete() }
        staged.clear()
        failed.clear()
        segments = emptyList()
        fullText = ""
        onlineUrl = ""
        playIndex = -1
        awaitingNext = false
        playingSegment = false
        reportedEngine = null
    }

    private fun start(text: String, truncatedFrom: Int?, fromChar: Int = 0) {
        if (truncatedFrom != null) {
            // Say what will actually be read, before reading it.
            listener?.onError(
                "Text truncated to $MAX_INPUT_CHARS characters ($truncatedFrom offered)",
                null,
            )
        }

        fullText = text
        val url = settings.backendUrl.trim()

        // A resume offset only makes sense inside the text. Clamping here keeps the
        // invariant local: nothing below has to defend against a bad offset.
        val from = fromChar.coerceIn(0, text.length)

        if (settings.preferOnline && url.isNotEmpty()) {
            speakOnline(text, url, from)
        } else {
            speakOffline(text, fromChar = from, reportedFallback = null)
        }
    }

    /**
     * The effective speech rate. The backend clamps speed to `[0.5, 2.0]` itself,
     * so the engine clamps at the source: a value the UI offers but the backend
     * silently rewrites would make the two disagree about what is being spoken.
     */
    private fun effectiveSpeed(): Float = SpeedSteps.clamp(settings.speed)

    // ── Online, piece by piece ────────────────────────────────────────────────

    /**
     * Plan the read and fetch the first piece.
     *
     * The pieces are the whole passage, in order, so playing them in sequence reads
     * exactly what the user offered. The first piece is short on purpose: its
     * synthesis is what the user waits for before hearing anything.
     */
    private fun speakOnline(text: String, url: String, fromChar: Int = 0) {
        onlineUrl = url
        segments = planSegments(text)

        // Resume at a piece boundary: the first piece that still has text at or after
        // the offset. Starting mid-piece would begin mid-word, so the read picks up
        // from the start of the piece containing the offset — the most the user can
        // reasonably expect, and it never skips speech.
        //
        // `playIndex` is the piece *playing* and advanceIfPossible starts
        // `playIndex + 1`, so the wanted piece is reached by beginning one before it.
        // Nothing else on the advance or prefetch path needs to know about offsets.
        val firstToPlay = segments.indexOfFirst { it.startChar + it.text.length > fromChar }

        if (firstToPlay < 0) {
            // The offset is at or past the end: there is genuinely nothing to read.
            // Leave nothing staged rather than reading the tail of the passage back.
            playIndex = segments.lastIndex
            awaitingNext = false
            playingSegment = false
            state = PlaybackState.Idle
            return
        }

        playIndex = firstToPlay - 1
        awaitingNext = false
        playingSegment = false
        schedulePrefetch()
    }

    private fun planSegments(text: String): List<Segment> {
        var start = 0
        return SpeechChunker.splitForStreaming(text).map { piece ->
            val segment = Segment(piece, start)
            start += piece.length
            segment
        }
    }

    /**
     * Fetch the piece needed next, and [prefetchDepth] further pieces ahead of it,
     * skipping pieces already staged or in flight.
     *
     * The piece after the one playing is always fetched — [prefetchDepth] controls
     * only how far *beyond* it the engine reads ahead. Expressing it that way is
     * what lets a test set the depth to 0 and still get a working, strictly
     * sequential read.
     *
     * Idempotent: it is called whenever the playing piece or its availability
     * changes, and what to fetch is decided per index rather than by a cursor.
     */
    private fun schedulePrefetch() {
        if (segments.isEmpty()) return
        val base = playIndex
        val last = minOf(base + 1 + prefetchDepth, segments.lastIndex)
        for (i in (base + 1)..last) {
            if (i < 0) continue
            if (staged.containsKey(i) || fetchJobs.containsKey(i)) continue
            fetchSegment(generation, onlineUrl, i)
        }
    }

    private fun fetchSegment(myGeneration: Int, url: String, index: Int) {
        if (index !in segments.indices) return
        val segment = segments[index]

        val job = scope.launch {
            val result = transport.fetch(
                text = segment.text,
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
                        Log.w(TAG, "could not stage piece $index: ${e.message}")
                        withContext(mainDispatcher) {
                            if (myGeneration == generation) {
                                onSegmentFailed(index, "Could not store audio")
                            }
                        }
                        return@launch
                    }

                    // Take ownership synchronously, with no suspension point between
                    // staging and this assignment. From here every cancellation path
                    // (a new speak, stop, shutdown) runs supersede(), which deletes
                    // everything staged — so a cancellation landing during the hop to
                    // the main dispatcher cannot orphan the file.
                    staged[index] = file

                    withContext(mainDispatcher) {
                        fetchJobs.remove(index)
                        if (myGeneration != generation) {
                            staged.remove(index)?.delete()
                            return@withContext
                        }
                        onSegmentReady(index)
                    }
                }

                is SynthesisResult.Failure -> withContext(mainDispatcher) {
                    fetchJobs.remove(index)
                    if (myGeneration == generation) onSegmentFailed(index, result.reason)
                }
            }
        }

        fetchJobs[index] = job
    }

    /** A piece has been synthesised and staged. */
    private fun onSegmentReady(index: Int) {
        // Start the next piece now, so it is synthesised during this one's playback.
        schedulePrefetch()

        val current = state
        if (current is PlaybackState.Online && current.paused) return
        advanceIfPossible()
    }

    /** A piece could not be synthesised. */
    private fun onSegmentFailed(index: Int, reason: String) {
        failed[index] = reason
        // A failure further ahead does not interrupt what is playing; it is
        // reported when the read reaches it. A failure of the piece needed now
        // (or of the one playing) must fall back immediately.
        if (index <= playIndex + 1) reportFallback(index, reason)
    }

    /**
     * Play the next piece if it is staged, fall back if its fetch failed, or wait
     * for it if it is still being synthesised.
     *
     * Waiting rather than reporting completion is the honest behaviour: reporting
     * completion would tell the user the passage had been read when part of it had
     * not.
     */
    private fun advanceIfPossible() {
        if (segments.isEmpty()) return

        // Keep the pipeline fed. The piece after the playing one must always be
        // requested, and this is the single place every path passes through when the
        // playing piece changes — so scheduling here is what guarantees the read can
        // never stall waiting for a piece nobody asked for.
        schedulePrefetch()

        // A piece is in the player: the next one is ready (that is the whole point of
        // prefetching) but the user is still listening to this one. Starting it here
        // would cut the current piece off mid-sentence.
        if (playingSegment) return

        // Paused between pieces: the pending piece must wait for [resume].
        val current = state
        if (current is PlaybackState.Online && current.paused) return

        val next = playIndex + 1
        if (next > segments.lastIndex) {
            finish()
            return
        }

        val file = staged[next]
        if (file == null) {
            val reason = failed[next]
            if (reason != null) {
                reportFallback(next, reason)
            } else {
                awaitingNext = true
            }
            return
        }

        awaitingNext = false
        playSegment(next, file)
    }

    private fun playSegment(index: Int, file: File) {
        // Ownership moves from `staged` to `currentFile`; the file is deleted when
        // this piece finishes, on error, or on supersession.
        staged.remove(index)
        currentFile = file
        playIndex = index

        val started = player.play(
            file = file,
            onCompletion = { onSegmentFinished(index) },
            onError = { msg -> onPlaybackError(msg) },
        )

        if (!started) {
            // A 200 carrying bytes that are not decodable audio lands here.
            Log.w(TAG, "staged audio for piece $index could not be played; falling back")
            currentFile?.delete()
            currentFile = null
            reportFallback(index, "Audio could not be played")
            return
        }

        playingSegment = true
        state = PlaybackState.Online(text = fullText, positionMs = 0, paused = false)
        if (reportedEngine != TtsEngineKind.ONLINE) {
            reportedEngine = TtsEngineKind.ONLINE
            listener?.onStart(TtsEngineKind.ONLINE)
        }
    }

    /** The piece now playing reached its end. */
    private fun onSegmentFinished(index: Int) {
        currentFile?.delete()
        currentFile = null
        // Clear before advancing: this is the one moment the next piece is allowed
        // to start.
        playingSegment = false
        advanceIfPossible()
    }

    /**
     * Abandon the segmented plan and speak from [fromIndex] onwards with the
     * offline voice, so the remainder of the passage is still heard.
     */
    private fun reportFallback(fromIndex: Int, reason: String) {
        Log.w(TAG, "online synthesis failed at piece $fromIndex ($reason); falling back to offline voice")

        // The remaining text is the failing piece and everything after it: the
        // pieces partition the passage, so this is exactly what has not been read.
        // Joining the tail matters — taking only the failing piece would speak one
        // request's worth and silently drop the rest of the passage.
        val remaining = segments.drop(fromIndex).joinToString("") { it.text }
            .ifBlank { fullText }

        fetchJobs.values.forEach { it.cancel() }
        fetchJobs.clear()
        staged.values.forEach { it.delete() }
        staged.clear()
        failed.clear()
        currentFile?.delete()
        currentFile = null
        player.stop()
        segments = emptyList()
        playIndex = -1
        awaitingNext = false
        playingSegment = false
        reportedEngine = null

        whenOfflineReady(
            onUnavailable = {
                // There is nothing to fall back to, so do not report one: claiming a
                // fallback that cannot happen would misstate what the user hears.
                state = PlaybackState.Idle
                listener?.onError("$reason. Offline voice is unavailable", null)
            },
            action = {
                // Report the degradation even though speech continues: the user asked
                // for the neural voice and is about to hear the platform one, so a
                // silent substitution would misrepresent what is being spoken.
                listener?.onError(reason, TtsEngineKind.OFFLINE)
                startOfflineUtterance(remaining, fromChar = 0, reportedFallback = null)
            },
        )
    }

    private fun onPlaybackError(msg: String) {
        currentFile?.delete()
        currentFile = null
        playingSegment = false
        state = PlaybackState.Idle
        listener?.onError(msg, null)
    }

    private fun finish() {
        currentFile?.delete()
        currentFile = null
        playingSegment = false
        staged.values.forEach { it.delete() }
        staged.clear()
        failed.clear()
        state = PlaybackState.Idle
        listener?.onComplete()
    }

    private fun writeTempFile(bytes: ByteArray): File {
        val file = File(cacheDir, "sonata_${UUID.randomUUID()}.mp3")
        file.writeBytes(bytes)
        return file
    }

    // ── Offline ───────────────────────────────────────────────────────────────

    /**
     * Speak [text] offline from [fromChar].
     *
     * The state is committed before [OfflineVoice.speak] so the progress callback
     * has a state to update, and is rolled back if the utterance fails to start.
     */
    private fun speakOffline(text: String, fromChar: Int, reportedFallback: String?) {
        whenOfflineReady(
            onUnavailable = {
                state = PlaybackState.Idle
                listener?.onError(
                    reportedFallback?.let { "$it. Offline voice is unavailable" }
                        ?: "Offline voice is unavailable",
                    null,
                )
            },
            action = { startOfflineUtterance(text, fromChar, reportedFallback) },
        )
    }

    /**
     * Run [action] once the offline engine can speak, waiting at most
     * [offlineReadyTimeoutMs] for it; run [onUnavailable] when it never can.
     *
     * The wait runs off the main thread and is bounded: blocking the main thread
     * to wait would risk an ANR, which is worse than the failure it avoids.
     */
    private fun whenOfflineReady(onUnavailable: () -> Unit, action: () -> Unit) {
        if (offline.isReady()) {
            action()
            return
        }

        val myGeneration = generation
        val job = scope.launch {
            val ready = offline.awaitReady(offlineReadyTimeoutMs)
            withContext(mainDispatcher) {
                // A newer speak()/stop() superseded this request while we waited.
                if (myGeneration != generation) return@withContext
                if (ready) action() else onUnavailable()
            }
        }
        inFlight = job
    }

    private fun startOfflineUtterance(text: String, fromChar: Int, reportedFallback: String?) {

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
                    reportedEngine = TtsEngineKind.OFFLINE
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
            reportedEngine = TtsEngineKind.OFFLINE
            listener?.onStart(TtsEngineKind.OFFLINE)
        }
    }

    companion object {
        /**
         * Production wait for the platform speech engine to initialise.
         *
         * Generous relative to the sub-second cold start seen on hardware: the cost
         * of waiting too long is a brief silence, while the cost of giving up too
         * early is no speech at all.
         */
        const val DEFAULT_OFFLINE_READY_TIMEOUT_MS: Long = 10_000
    }
}
