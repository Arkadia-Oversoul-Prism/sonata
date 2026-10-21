package com.arkadia.sonata

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Test doubles for the engine's seams.
 *
 * These are hand-written rather than mock-framework doubles so each records the
 * calls the engine actually makes; the assertions then read as statements about
 * behaviour (how many times playback started, which file was released) rather
 * than about interaction bookkeeping.
 */

/** Records requests and returns a scripted result. */
class FakeTransport(
    private val result: SynthesisResult = SynthesisResult.Success(
        bytes = ByteArray(64) { 1 },
        contentType = "audio/mpeg",
        engineLabel = "edge_tts",
    ),
    private val delayMs: Long = 0,
) : TtsTransport {

    val requests = CopyOnWriteArrayList<Triple<String, String, Float>>()
    var closed = false
        private set

    override suspend fun fetch(
        text: String,
        voice: String,
        speed: Float,
        baseUrl: String,
    ): SynthesisResult {
        requests += Triple(text, voice, speed)
        if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
        return result
    }

    override fun close() {
        closed = true
    }
}

/** Records playback control and lets a test drive completion/error. */
class FakeAudioPlayer : AudioPlayer {

    var playing = false
        private set
    var paused = false
        private set

    var playCalls = 0
        private set
    var stopCalls = 0
        private set

    /** The file most recently handed to [play]; used to assert cleanup. */
    var lastFile: File? = null
        private set

    /** When false, [play] reports that playback could not start. */
    var startSucceeds = true

    private var onCompletion: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    /** Position returned by [currentPositionMs]; lets a test simulate progress. */
    var positionMs = 0

    override fun play(file: File, onCompletion: () -> Unit, onError: (String) -> Unit): Boolean {
        playCalls++
        lastFile = file
        this.onCompletion = onCompletion
        this.onError = onError
        if (!startSucceeds) {
            playing = false
            return false
        }
        playing = true
        paused = false
        return true
    }

    override fun pause(): Boolean {
        if (!playing) return false
        playing = false
        paused = true
        return true
    }

    override fun resume(): Boolean {
        if (!paused) return false
        paused = false
        playing = true
        return true
    }

    override fun currentPositionMs(): Int = positionMs

    override fun isPlaying(): Boolean = playing

    override fun stop() {
        stopCalls++
        playing = false
        paused = false
    }

    /** Simulate the player reaching the end of the audio. */
    fun complete() {
        playing = false
        onCompletion?.invoke()
    }

    /** Simulate a playback error after a successful start. */
    fun fail(msg: String) {
        playing = false
        onError?.invoke(msg)
    }
}

/** Records offline speech and lets a test drive progress/completion/error. */
class FakeOfflineVoice(var ready: Boolean = true) : OfflineVoice {

    data class Utterance(val text: String, val fromChar: Int, val speed: Float, val pitch: Float)

    val utterances = CopyOnWriteArrayList<Utterance>()
    var stopCalls = 0
        private set

    /** Set when a caller is suspended in [awaitReady], so a test can release it. */
    @Volatile
    private var readinessGate: CompletableDeferred<Boolean>? = null

    /** How many times a caller actually had to wait. */
    var awaitReadyCalls = 0
        private set

    private var onRange: ((Int) -> Unit)? = null
    private var onDone: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    override fun isReady(): Boolean = ready

    /**
     * Suspends until readiness arrives or [timeoutMs] elapses.
     *
     * The bound is modelled with virtual time rather than a wall clock, so the
     * test scheduler decides when the wait expires instead of the machine's speed.
     */
    override suspend fun awaitReady(timeoutMs: Long): Boolean {
        if (ready) return true
        awaitReadyCalls++
        val gate = CompletableDeferred<Boolean>()
        readinessGate = gate
        return withTimeoutOrNull(timeoutMs) { gate.await() } ?: false
    }

    /** Simulate the platform engine completing initialisation successfully. */
    fun becomeReady() {
        ready = true
        readinessGate?.complete(true)
    }

    /** Simulate the platform engine failing to initialise within the bound. */
    fun denyReadiness() {
        readinessGate?.complete(false)
    }

    override fun speak(
        text: String,
        fromChar: Int,
        speed: Float,
        pitch: Float,
        onStart: () -> Unit,
        onRange: (Int) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        utterances += Utterance(text, fromChar, speed, pitch)
        this.onRange = onRange
        this.onDone = onDone
        this.onError = onError
        onStart()
    }

    override fun stop() {
        stopCalls++
    }

    /** Simulate the engine reporting progress at [charOffset]. */
    fun reportRange(charOffset: Int) {
        onRange?.invoke(charOffset)
    }

    /** Simulate the utterance finishing. */
    fun complete() {
        onDone?.invoke()
    }

    /** Simulate an offline speech failure. */
    fun fail(msg: String) {
        onError?.invoke(msg)
    }
}

/** A [TtsSettings] with mutable fields, for constructing engine scenarios. */
class FakeSettings(
    override var voice: String = "aria",
    override var speed: Float = 1.0f,
    override var pitch: Float = 1.0f,
    override var backendUrl: String = "https://example.test",
    override var preferOnline: Boolean = true,
) : TtsSettings

/** Records engine callbacks in order. */
class RecordingListener : TtsEngine.Listener {

    sealed class Event {
        data class Started(val engine: TtsEngineKind) : Event()
        data class Paused(val engine: TtsEngineKind) : Event()
        data class Resumed(val engine: TtsEngineKind) : Event()
        object Completed : Event()
        data class Failed(val msg: String, val fellBackTo: TtsEngineKind?) : Event()
    }

    val events = CopyOnWriteArrayList<Event>()

    override fun onStart(engine: TtsEngineKind) { events += Event.Started(engine) }
    override fun onPaused(engine: TtsEngineKind) { events += Event.Paused(engine) }
    override fun onResumed(engine: TtsEngineKind) { events += Event.Resumed(engine) }
    override fun onComplete() { events += Event.Completed }
    override fun onError(msg: String, fellBackTo: TtsEngineKind?) {
        events += Event.Failed(msg, fellBackTo)
    }

    val starts: List<Event.Started>
        get() = events.filterIsInstance<Event.Started>()

    val failures: List<Event.Failed>
        get() = events.filterIsInstance<Event.Failed>()

    val completed: Boolean
        get() = events.any { it is Event.Completed }
}
