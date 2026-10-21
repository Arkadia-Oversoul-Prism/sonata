package com.arkadia.sonata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Result of a synthesis request.
 *
 * Deliberately not a `data class`: the payload is a `ByteArray`, and a generated
 * `equals`/`hashCode` would compare arrays by identity, which silently breaks
 * value comparison in tests and callers. Compare [bytes] explicitly.
 */
sealed class SynthesisResult {

    class Success(
        val bytes: ByteArray,
        val contentType: String?,
        /** The backend's own `X-TTS-Engine` label, when it reports one. */
        val engineLabel: String?,
    ) : SynthesisResult()

    /** [reason] is safe to surface to the user. */
    class Failure(val reason: String) : SynthesisResult()
}

/**
 * Fetches synthesised audio from the Arkadia backend.
 *
 * Extracted so the engine's validation, fallback and cleanup logic can be tested
 * without a network or an Android framework.
 */
interface TtsTransport {

    /**
     * Request audio for [text].
     *
     * Implementations must not throw: every failure — transport, non-success
     * HTTP, malformed or empty audio — is returned as [SynthesisResult.Failure]
     * with a user-presentable reason, because every failure path in the engine
     * falls back to offline speech.
     */
    suspend fun fetch(
        text: String,
        voice: String,
        speed: Float,
        baseUrl: String,
    ): SynthesisResult

    /** Release transport resources. */
    fun close()
}

/**
 * OkHttp implementation of [TtsTransport] against `POST {baseUrl}/api/tts`.
 *
 * Contract (source-backed, Arkadia `api/main.py:2128`):
 *  - request  `{"text": String, "voice": String, "speed": Number}` (speed clamped
 *    server-side to `[0.5, 2.0]`)
 *  - success  raw audio bytes with an `audio/…` content type and `X-TTS-Engine`
 *  - failure  `400` (missing text / bad JSON), `502` (synthesis failed)
 *  - no authentication or API key required
 */
class OkHttpTtsTransport(
    private val client: OkHttpClient = defaultClient(),
) : TtsTransport {

    override suspend fun fetch(
        text: String,
        voice: String,
        speed: Float,
        baseUrl: String,
    ): SynthesisResult = withContext(Dispatchers.IO) {
        val root = baseUrl.trim().trimEnd('/')
        if (root.isEmpty()) return@withContext SynthesisResult.Failure("No backend URL configured")

        val payload = JSONObject().apply {
            put("text", text)
            put("voice", voice)
            put("speed", SpeedSteps.clamp(speed).toDouble())
        }.toString()

        val request = try {
            Request.Builder()
                .url("$root/api/tts")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        } catch (e: IllegalArgumentException) {
            // Malformed user-entered URL — a configuration error, not a crash.
            return@withContext SynthesisResult.Failure("Invalid backend URL")
        }

        try {
            // `use` closes the response (and its connection) on every path. The
            // previous implementation never closed it, leaking connections.
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use SynthesisResult.Failure("Backend returned HTTP ${response.code}")
                }

                val contentType = response.header("Content-Type")
                if (contentType != null && !contentType.startsWith("audio/")) {
                    return@use SynthesisResult.Failure("Backend returned $contentType, not audio")
                }

                val bytes = response.body?.bytes()
                if (bytes == null || bytes.isEmpty()) {
                    return@use SynthesisResult.Failure("Backend returned empty audio")
                }

                SynthesisResult.Success(
                    bytes = bytes,
                    contentType = contentType,
                    engineLabel = response.header("X-TTS-Engine"),
                )
            }
        } catch (e: Exception) {
            SynthesisResult.Failure(describeFailure(e))
        }
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * A user-presentable reason for a failed request.
         *
         * Android blocks cleartext HTTP by default from API 28, and the resulting
         * UnknownServiceException carries a message written for a developer
         * ("CLEARTEXT communication to ... not permitted by network security
         * policy"), not for the person who typed the URL. Reporting only the
         * exception class would leave the user with no idea that the remedy is an
         * https:// backend, so the block is named explicitly.
         */
        fun describeFailure(e: Exception): String = when {
            e is java.net.UnknownServiceException &&
                e.message?.contains("CLEARTEXT", ignoreCase = true) == true ->
                "This backend uses plain HTTP, which Android blocks. Use an https:// URL."

            else -> "Network error: ${e.javaClass.simpleName}"
        }

        /**
         * The backend synthesises the whole utterance before responding, so the
         * time to first byte is synthesis time, not transfer time. The read
         * timeout therefore has to cover synthesis of a full 5000-character
         * request, and [callTimeout] bounds the whole call so a stalled
         * connection cannot hang the engine forever.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}
