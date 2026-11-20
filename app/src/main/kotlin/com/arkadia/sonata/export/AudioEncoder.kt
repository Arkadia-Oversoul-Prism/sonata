package com.arkadia.sonata.export

/**
 * Encodes PCM into a container format.
 *
 * A seam so the export path is testable without `MediaCodec`: the WAV encoder is
 * pure Kotlin, and the platform encoders are injected. [supports] is what makes
 * "MP3 is not offered when no encoder is present" a fact rather than a promise —
 * the exporter asks, and the UI reads the answer.
 */
interface AudioEncoder {

    /** Whether this encoder can produce [format]. */
    fun supports(format: AudioFormat): Boolean

    /**
     * Encode [pcm] (16-bit little-endian, [sampleRate] Hz) as [format].
     *
     * Implementations must not throw for a format they [supports]; a failure is
     * reported by the caller as an export failure with a user-presentable reason.
     */
    suspend fun encode(pcm: ByteArray, sampleRate: Int, format: AudioFormat): ByteArray
}

/** WAV via [WavWriter]. Always available: it needs no encoder, only a header. */
class WavEncoder : AudioEncoder {
    override fun supports(format: AudioFormat): Boolean = format == AudioFormat.WAV

    override suspend fun encode(pcm: ByteArray, sampleRate: Int, format: AudioFormat): ByteArray =
        WavWriter.wrap(pcm, sampleRate)
}

/**
 * An encoder that delegates to the first of several that supports the format.
 *
 * This is how the platform encoders (AAC via `MediaCodec`, and an MP3 encoder if
 * one is ever bundled) are composed with the always-present WAV encoder without
 * the exporter knowing which is which.
 */
class CompositeEncoder(private val encoders: List<AudioEncoder>) : AudioEncoder {

    override fun supports(format: AudioFormat): Boolean = encoders.any { it.supports(format) }

    override suspend fun encode(pcm: ByteArray, sampleRate: Int, format: AudioFormat): ByteArray {
        val encoder = encoders.firstOrNull { it.supports(format) }
            ?: error("no encoder for $format")
        return encoder.encode(pcm, sampleRate, format)
    }
}
