package com.arkadia.sonata

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [OfflineVoice] backed by the platform `TextToSpeech` engine.
 *
 * Because the platform engine cannot pause, this implementation speaks the
 * remainder of the text and reports the character offset reached through
 * [UtteranceProgressListener.onRangeStart] (API 26+; `minSdk` is 29), so a pause
 * can resume from the last spoken character rather than restarting the utterance.
 */
class AndroidOfflineVoice(
    context: Context,
    private val onReady: () -> Unit = {},
) : OfflineVoice {

    private var tts: TextToSpeech? = null

    /**
     * Completed from the platform initialisation callback. A deferred rather than
     * a plain flag so a caller can wait for the callback instead of polling it.
     */
    @Volatile
    private var readySignal = CompletableDeferred<Boolean>()

    private var ready = false

    /** Offset, within the currently spoken string, of the last character reached. */
    @Volatile
    private var lastRangeStart = 0

    private var currentUtteranceId: String? = null

    /**
     * Utterance ids queued for the current request. A long passage is several
     * queued utterances, so progress and completion must be matched against all of
     * them rather than one id.
     */
    @Volatile
    private var queuedIds: List<String> = emptyList()

    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            readySignal.complete(ready)
            if (ready) {
                // Locale.US is the correct locale for the backend's voice catalogue
                // (en-US / en-GB neural voices); failure is non-fatal.
                tts?.language = Locale.US
                Log.i(TAG, "Android TTS ready")
                onReady()
            } else {
                Log.w(TAG, "Android TTS init failed (status=$status)")
            }
        }
    }

    override fun isReady(): Boolean = ready

    override suspend fun awaitReady(timeoutMs: Long): Boolean {
        if (ready) return true
        // A non-positive bound means "do not wait". Bounding the wait matters:
        // a platform engine that never calls back must not suspend speech forever.
        if (timeoutMs <= 0) return ready
        return withTimeoutOrNull(timeoutMs) { readySignal.await() } ?: false
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
        val engine = tts
        if (!ready || engine == null) {
            onError("Offline voice is not available on this device")
            return
        }

        val from = fromChar.coerceIn(0, text.length)
        val remainder = text.substring(from)
        if (remainder.isBlank()) {
            onDone()
            return
        }

        lastRangeStart = 0
        engine.setSpeechRate(SpeedSteps.clamp(speed))
        engine.setPitch(pitch.coerceIn(0.5f, 2.0f))

        // Chunk to the platform's own ceiling. Without this, a passage longer than
        // getMaxSpeechInputLength() is rejected by speak() with ERROR and nothing is
        // heard — which is what made a paragraph fail while a word worked.
        val pieces = SpeechChunker.split(remainder, platformLimit())
        if (pieces.isEmpty()) {
            onDone()
            return
        }

        val ids = pieces.map { "sonata_${UUID.randomUUID()}" }
        currentUtteranceId = ids.first()
        queuedIds = ids

        // baseOffset[i] is where piece i starts, in the caller's coordinate space.
        val baseOffsets = IntArray(pieces.size)
        var running = from
        for (i in pieces.indices) {
            baseOffsets[i] = running
            running += pieces[i].length
        }

        var startedReported = false

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {

            override fun onStart(id: String?) {
                if (!startedReported) {
                    startedReported = true
                    onStart()
                }
            }

            /**
             * Reports progress within the spoken remainder. The offset is
             * translated back into the full text's coordinate space so a pause
             * records an offset the caller can resume from.
             */
            override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) {
                val index = queuedIds.indexOf(id ?: return)
                if (index < 0) return
                val absolute = baseOffsets[index] + start
                lastRangeStart = absolute - from
                onRange(absolute)
            }

            override fun onDone(id: String?) {
                val index = queuedIds.indexOf(id ?: return)
                if (index < 0) return
                // Report the end of the piece just finished, so a pause between
                // pieces resumes at the boundary rather than repeating the piece.
                lastRangeStart = baseOffsets[index] + pieces[index].length - from
                onRange(baseOffsets[index] + pieces[index].length)
                // Only the final piece completes the request; the rest are queued.
                if (index == queuedIds.lastIndex) onDone()
            }

            @Deprecated("Superseded by onError(String, Int)", ReplaceWith("onError(id, 0)"))
            override fun onError(id: String?) {
                if (id !in queuedIds) return
                onError("Offline speech failed")
            }

            override fun onError(id: String?, errorCode: Int) {
                if (id !in queuedIds) return
                onError("Offline speech failed (code $errorCode)")
            }
        })

        // QUEUE_ADD for everything after the first piece, so the whole passage is
        // queued with the engine and continues without a gap between pieces.
        val first = engine.speak(pieces.first(), TextToSpeech.QUEUE_FLUSH, null, ids.first())
        if (first == TextToSpeech.ERROR) {
            queuedIds = emptyList()
            onError("Offline speech could not start")
            return
        }
        for (i in 1 until pieces.size) {
            val queued = engine.speak(pieces[i], TextToSpeech.QUEUE_ADD, null, ids[i])
            if (queued == TextToSpeech.ERROR) {
                // Report rather than pretending the whole passage was read.
                queuedIds = emptyList()
                onError("Offline speech stopped after ${baseOffsets[i]} characters")
                return
            }
        }
    }

    /**
     * The platform's own speech input ceiling, read from the engine when it can be.
     * The documented value is 4000; reading it keeps the chunker correct on a
     * platform that changes it.
     */
    private fun platformLimit(): Int = try {
        TextToSpeech.getMaxSpeechInputLength()
    } catch (e: Exception) {
        SpeechChunker.PLATFORM_LIMIT
    }

    /** The character offset last reached in the current utterance. */
    fun lastRangeStart(): Int = lastRangeStart

    override fun stop() {
        currentUtteranceId = null
        queuedIds = emptyList()
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        // A waiter must not hang on a shut-down engine; re-arm the signal so a
        // later awaitReady() resolves immediately as "not ready".
        readySignal = CompletableDeferred<Boolean>().apply { complete(false) }
    }

    private companion object {
        const val TAG = "SonataTts"
    }
}
