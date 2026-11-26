package com.arkadia.sonata

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.arkadia.sonata.engine.PlatformSynthesis
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [PlatformSynthesis] backed by `android.speech.tts.TextToSpeech.synthesizeToFile`.
 *
 * This is the class that makes Sonata able to produce an **audio file** on a device
 * with no neural model. The distinction from [AndroidOfflineVoice] matters:
 *
 *  - [AndroidOfflineVoice] *streams* speech to the speaker. It is the playback
 *    path, and it produces nothing that can be saved, exported or replayed.
 *  - This class *renders* speech to a file. It is the library path, and its output
 *    is what the engine stores as a narration.
 *
 * Both are the same platform engine; they differ in which API is used. A read-aloud
 * app needs both, and before this class only the first existed — which is why a
 * narration could never be saved.
 *
 * `synthesizeToFile` is asynchronous and reports through
 * [UtteranceProgressListener.onDone]/[onError], so each call is bridged to a
 * suspend function with a [CompletableDeferred]. The wait is bounded: an engine
 * that never calls back must not suspend a narration forever.
 */
class AndroidPlatformSynthesis(
    context: Context,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : PlatformSynthesis {

    private val appContext = context.applicationContext

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    private var readySignal = CompletableDeferred<Boolean>()

    /** The in-flight render, if any. Only one at a time: the engine is serial. */
    @Volatile
    private var pending: CompletableDeferred<ByteArray?>? = null

    @Volatile
    private var pendingId: String? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.US
                Log.i(TAG, "Platform synthesis engine ready")
            } else {
                Log.w(TAG, "Platform synthesis init failed (status=$status)")
            }
            readySignal.complete(ready)
        }
    }

    override fun isAvailable(): Boolean = ready

    /**
     * Render [text] to WAV bytes.
     *
     * The file is written into the app's cache directory and deleted after it is
     * read, so a render leaves no residue. It is read on [Dispatchers.IO] because
     * it is a real file read, and the whole call is bounded by [timeoutMs].
     */
    override suspend fun synthesizeToWav(
        text: String,
        voiceId: String?,
        speed: Float,
        pitch: Float,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val engine = tts
        if (!ready || engine == null) {
            Log.w(TAG, "synthesizeToWav called before the engine was ready")
            return@withContext null
        }
        if (text.isBlank()) return@withContext null

        // Wait for readiness if the callback has not landed yet. A cold start can
        // reach here before initialisation completes, and refusing then would fail
        // the one path that exists to guarantee audio.
        if (!ready) {
            val ok = withTimeoutOrNull(timeoutMs) { readySignal.await() } ?: false
            if (!ok) return@withContext null
        }

        engine.setSpeechRate(speed.coerceIn(0.25f, 4.0f))
        engine.setPitch(pitch.coerceIn(0.5f, 2.0f))

        val id = "sonata_render_${UUID.randomUUID()}"
        val out = File(appContext.cacheDir, "$id.wav")
        val done = CompletableDeferred<ByteArray?>()
        pending = done
        pendingId = id

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                if (utteranceId != pendingId) return
                // Read inside the callback's thread but off the main thread: the
                // file is complete at this point, so the read is safe.
                val bytes = try {
                    if (out.exists() && out.length() > 0) out.readBytes() else null
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read rendered audio", e)
                    null
                } finally {
                    out.delete()
                }
                pending?.complete(bytes)
            }

            @Deprecated("Superseded by onError(String, Int)", ReplaceWith("onError(id, 0)"))
            override fun onError(utteranceId: String?) {
                if (utteranceId != pendingId) return
                out.delete()
                pending?.complete(null)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId != pendingId) return
                Log.w(TAG, "Platform render failed (code $errorCode)")
                out.delete()
                pending?.complete(null)
            }
        })

        val started = engine.synthesizeToFile(text, null, out, id)
        if (started == TextToSpeech.ERROR) {
            out.delete()
            pending = null
            pendingId = null
            return@withContext null
        }

        val result = withTimeoutOrNull(timeoutMs) { done.await() }
        pending = null
        pendingId = null
        if (result == null) {
            // The engine never called back. Stop it so the next render is not
            // queued behind a stuck utterance, and report the failure.
            Log.w(TAG, "Platform render timed out after ${timeoutMs}ms")
            engine.stop()
            out.delete()
        }
        result
    }

    /** Release the engine. */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        readySignal = CompletableDeferred<Boolean>().apply { complete(false) }
    }

    private companion object {
        const val TAG = "SonataPlatformTts"

        /**
         * How long a single render may take before it is abandoned.
         *
         * Generous, because a long passage on a slow device is legitimately slow,
         * but finite: an engine that never calls back must not hang a narration.
         */
        const val DEFAULT_TIMEOUT_MS = 60_000L
    }
}
