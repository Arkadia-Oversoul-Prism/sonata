package com.arkadia.sonata.export

/**
 * Writes a RIFF/WAVE container around raw PCM.
 *
 * Pure and dependency-free, so the header is testable byte-for-byte without a
 * device. WAV is the one format Sonata can always produce: it needs no encoder,
 * only a 44-byte header, which is why it is the guaranteed export path and the
 * canonical on-disk format for a narration's audio.
 *
 * The layout written is the canonical 16-byte PCM `fmt ` chunk:
 *
 * ```
 * offset  size  field
 *   0      4    "RIFF"
 *   4      4    file size - 8
 *   8      4    "WAVE"
 *  12      4    "fmt "
 *  16      4    16            (PCM fmt chunk size)
 *  20      2    1             (audio format: PCM)
 *  22      2    channels
 *  24      4    sample rate
 *  28      4    byte rate     (sampleRate * channels * bitsPerSample / 8)
 *  32      2    block align   (channels * bitsPerSample / 8)
 *  34      2    bits per sample
 *  36      4    "data"
 *  40      4    data size
 *  44      …    PCM samples, little-endian
 * ```
 */
object WavWriter {

    /** Bytes in the canonical PCM header. */
    const val HEADER_BYTES = 44

    /**
     * Wrap [pcm] in a WAV header.
     *
     * [pcm] is assumed to be 16-bit little-endian samples, which is what the
     * synthesis path produces. [channels] defaults to mono because a cloned voice
     * is mono; the parameter exists so a stereo source is not mislabelled.
     */
    fun wrap(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int = 1,
        bitsPerSample: Int = 16,
    ): ByteArray {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels in 1..2) { "channels must be 1 or 2" }
        require(bitsPerSample in setOf(8, 16, 24, 32)) { "unsupported bit depth" }

        val blockAlign = channels * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign
        val out = ByteArray(HEADER_BYTES + pcm.size)

        writeAscii(out, 0, "RIFF")
        writeIntLe(out, 4, HEADER_BYTES - 8 + pcm.size)
        writeAscii(out, 8, "WAVE")
        writeAscii(out, 12, "fmt ")
        writeIntLe(out, 16, 16)
        writeShortLe(out, 20, 1)
        writeShortLe(out, 22, channels)
        writeIntLe(out, 24, sampleRate)
        writeIntLe(out, 28, byteRate)
        writeShortLe(out, 32, blockAlign)
        writeShortLe(out, 34, bitsPerSample)
        writeAscii(out, 36, "data")
        writeIntLe(out, 40, pcm.size)
        System.arraycopy(pcm, 0, out, HEADER_BYTES, pcm.size)
        return out
    }

    /**
     * The PCM payload of a WAV file, with the header removed.
     *
     * Returns an empty array for a file that is header-only or shorter than a
     * header, rather than throwing: a truncated file is a real state after a
     * crash, and the caller reports it rather than crashing on it.
     */
    fun payload(wav: ByteArray): ByteArray =
        if (wav.size <= HEADER_BYTES) ByteArray(0)
        else wav.copyOfRange(HEADER_BYTES, wav.size)

    /**
     * The duration of [pcmBytes] of audio at [sampleRate], in milliseconds.
     *
     * Derived from the byte count rather than tracked separately, so a duration
     * can never disagree with the audio it describes.
     */
    fun durationMs(
        pcmBytes: Int,
        sampleRate: Int,
        channels: Int = 1,
        bitsPerSample: Int = 16,
    ): Long {
        val blockAlign = channels * bitsPerSample / 8
        if (blockAlign <= 0 || sampleRate <= 0) return 0
        val frames = pcmBytes / blockAlign
        return frames * 1000L / sampleRate
    }

    private fun writeAscii(out: ByteArray, at: Int, s: String) {
        for (i in s.indices) out[at + i] = s[i].code.toByte()
    }

    private fun writeIntLe(out: ByteArray, at: Int, v: Int) {
        out[at] = (v and 0xFF).toByte()
        out[at + 1] = ((v ushr 8) and 0xFF).toByte()
        out[at + 2] = ((v ushr 16) and 0xFF).toByte()
        out[at + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun writeShortLe(out: ByteArray, at: Int, v: Int) {
        out[at] = (v and 0xFF).toByte()
        out[at + 1] = ((v ushr 8) and 0xFF).toByte()
    }
}
