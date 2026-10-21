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
        val utteranceId = "sonata_${UUID.randomUUID()}"
        currentUtteranceId = utteranceId

        engine.setSpeechRate(SpeedSteps.clamp(speed))
        engine.setPitch(pitch.coerceIn(0.5f, 2.0f))
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {

            override fun onStart(id: String?) {
                onStart()
            }

            /**
             * Reports progress within the spoken remainder. The offset is
             * translated back into the full text's coordinate space so a pause
             * records an offset the caller can resume from.
             */
            override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) {
                if (id != utteranceId) return
                lastRangeStart = start
                onRange(from + start)
            }

            override fun onDone(id: String?) {
                if (id != utteranceId) return
                onDone()
            }

            @Deprecated("Superseded by onError(String, Int)", ReplaceWith("onError(id, 0)"))
            override fun onError(id: String?) {
                if (id != utteranceId) return
                onError("Offline speech failed")
            }

            override fun onError(id: String?, errorCode: Int) {
                if (id != utteranceId) return
                onError("Offline speech failed (code $errorCode)")
            }
        })

        val result = engine.speak(remainder, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            onError("Offline speech could not start")
        }
    }

    /** The character offset last reached in the current utterance. */
    fun lastRangeStart(): Int = lastRangeStart

    override fun stop() {
        currentUtteranceId = null
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
