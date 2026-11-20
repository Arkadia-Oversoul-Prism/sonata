package com.arkadia.sonata.voice

/**
 * The acoustic quality of a recorded sample.
 *
 * Pure, so the gate is testable without a microphone. The specification requires
 * that a poor sample is rejected with a reason rather than silently producing a
 * bad voice, and this is where that decision is made.
 *
 * The three measures are deliberately simple and independent, because each catches
 * a different way a recording is unusable:
 *
 *  - **Level** — too quiet and the model learns noise; too loud and it learns
 *    clipping. Both are unrecoverable after the fact.
 *  - **Speech ratio** — a recording that is mostly silence teaches the model
 *    silence. This is the measure that catches "the user left the room".
 *  - **Duration** — too short and there is not enough material to characterise a
 *    voice, whatever its quality.
 */
data class SampleQuality(
    /** Peak amplitude, 0..1. */
    val peak: Float,
    /** RMS amplitude, 0..1. */
    val rms: Float,
    /** Fraction of frames judged to contain speech, 0..1. */
    val speechRatio: Float,
    /** Duration in milliseconds. */
    val durationMs: Long,
) {
    /** A single 0..1 score, for display and for ranking samples. */
    val score: Float get() = SampleQualityGate.score(this)
}

/**
 * The acoustic gate.
 *
 * Named `SampleQualityGate` rather than `SampleQuality` because Kotlin does not
 * allow a class and an object to share a name in one package — the first version
 * of this file declared both and did not compile.
 */
object SampleQualityGate {

    /** Below this RMS the recording is too quiet to characterise a voice. */
    const val MIN_RMS = 0.02f

    /** Above this peak the recording is clipping, which is unrecoverable. */
    const val MAX_PEAK = 0.99f

    /** Below this fraction of speech the recording is mostly silence. */
    const val MIN_SPEECH_RATIO = 0.35f

    /** Below this duration there is not enough material. */
    const val MIN_DURATION_MS = 3_000L

    /** The duration a good sample aims for. */
    const val TARGET_DURATION_MS = 15_000L

    /**
     * Whether [quality] is good enough to enrol.
     *
     * All four conditions must hold. A sample that fails any one is rejected with
     * the specific reason from [rejectionReason], so the user is told what to fix
     * rather than just "try again".
     */
    fun isAcceptable(quality: SampleQuality): Boolean = rejectionReason(quality) == null

    /**
     * Why [quality] was rejected, or null when it is acceptable.
     *
     * Ordered so the most actionable problem is reported first: a recording that is
     * both too short and too quiet is better described as too short, because
     * recording longer is the first thing to try.
     */
    fun rejectionReason(quality: SampleQuality): String? = when {
        quality.durationMs < MIN_DURATION_MS ->
            "That recording is too short. Aim for at least ${MIN_DURATION_MS / 1000} seconds."

        quality.peak >= MAX_PEAK ->
            "That recording is clipping. Move a little further from the microphone."

        quality.rms < MIN_RMS ->
            "That recording is too quiet. Move closer to the microphone."

        quality.speechRatio < MIN_SPEECH_RATIO ->
            "That recording is mostly silence. Read the passage aloud without long pauses."

        else -> null
    }

    /**
     * A 0..1 score for [quality].
     *
     * Each measure contributes independently and is clamped, so no single measure
     * can saturate the score. The previous implementation let two amplitudes both
     * reach 1.0, which made a comparison between them meaningless; clamping each
     * term separately is what fixes that.
     */
    fun score(quality: SampleQuality): Float {
        val level = (quality.rms / 0.15f).coerceIn(0f, 1f)
        val clarity = (quality.speechRatio / 0.8f).coerceIn(0f, 1f)
        val length = (quality.durationMs.toFloat() / TARGET_DURATION_MS).coerceIn(0f, 1f)
        val headroom = if (quality.peak >= MAX_PEAK) 0f else 1f
        return (level * 0.35f + clarity * 0.35f + length * 0.2f + headroom * 0.1f)
            .coerceIn(0f, 1f)
    }

    /**
     * Analyse raw 16-bit PCM.
     *
     * Pure and dependency-free, so the gate is testable with synthetic audio. The
     * frame size is 20 ms, which is short enough to resolve speech from silence and
     * long enough that a single sample cannot decide a frame.
     */
    fun analyse(pcm: ByteArray, sampleRate: Int): SampleQuality {
        if (pcm.size < 2 || sampleRate <= 0) {
            return SampleQuality(0f, 0f, 0f, 0)
        }
        val samples = pcm.size / 2
        val frameSamples = (sampleRate / 50).coerceAtLeast(1) // 20 ms
        var peak = 0f
        var sumSquares = 0.0
        var speechFrames = 0
        var totalFrames = 0

        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort() / 32768f
            val magnitude = kotlin.math.abs(sample)
            if (magnitude > peak) peak = magnitude
            sumSquares += (sample * sample).toDouble()
            i += 2
        }

        // Frame-level speech detection: a frame counts as speech when its RMS is
        // above a fraction of the recording's own RMS, which adapts to the
        // recording's level rather than assuming one.
        val overallRms = kotlin.math.sqrt(sumSquares / samples.coerceAtLeast(1)).toFloat()
        val speechThreshold = (overallRms * 0.5f).coerceAtLeast(0.005f)

        var frameStart = 0
        while (frameStart < samples) {
            val frameEnd = minOf(frameStart + frameSamples, samples)
            var frameSum = 0.0
            var k = frameStart
            while (k < frameEnd) {
                val lo = pcm[k * 2].toInt() and 0xFF
                val hi = pcm[k * 2 + 1].toInt()
                val s = ((hi shl 8) or lo).toShort() / 32768f
                frameSum += (s * s).toDouble()
                k++
            }
            val frameRms = kotlin.math.sqrt(frameSum / (frameEnd - frameStart).coerceAtLeast(1)).toFloat()
            if (frameRms >= speechThreshold) speechFrames++
            totalFrames++
            frameStart = frameEnd
        }

        val durationMs = samples * 1000L / sampleRate
        return SampleQuality(
            peak = peak,
            rms = overallRms,
            speechRatio = if (totalFrames == 0) 0f else speechFrames.toFloat() / totalFrames,
            durationMs = durationMs,
        )
    }
}
