package com.arkadia.sonata.export

/**
 * Reads a RIFF/WAVE file back into its PCM payload and format.
 *
 * The counterpart to [WavWriter], and it exists for one concrete reason: the
 * platform `TextToSpeech` engine can only produce **a file**, not a PCM buffer
 * (`synthesizeToFile`). To feed that audio into the engine's canonical path — which
 * stores PCM and wraps it with [WavWriter] — the file has to be read back. This is
 * that read.
 *
 * It walks the chunk list rather than assuming a 44-byte header. A platform engine
 * is free to emit `LIST`/`fact` chunks before `data`, and a fixed offset would then
 * read metadata as audio. Walking the chunks is the only version that is correct
 * for a file this code did not write.
 *
 * Returns null for anything it cannot parse rather than throwing: a truncated or
 * foreign file is a real state, and the caller reports it as a failure instead of
 * crashing on it.
 */
object WavReader {

    /** A parsed WAV: its format and its PCM payload. */
    class Parsed(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val pcm: ByteArray,
    )

    /**
     * Parse [wav], or return null when it is not a readable PCM WAVE file.
     *
     * Only the fields the engine needs are extracted. A file with no `fmt ` or no
     * `data` chunk is rejected, because either one missing means the audio cannot
     * be interpreted.
     */
    fun parse(wav: ByteArray): Parsed? {
        if (wav.size < 12) return null
        if (ascii(wav, 0, 4) != "RIFF" || ascii(wav, 8, 4) != "WAVE") return null

        var pos = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var dataOffset = -1
        var dataLength = 0

        while (pos + 8 <= wav.size) {
            val id = ascii(wav, pos, 4)
            val size = intLe(wav, pos + 4)
            // A negative size is a corrupt chunk; stop rather than loop forever.
            if (size < 0) return null
            val body = pos + 8

            when (id) {
                "fmt " -> {
                    if (body + 16 > wav.size) return null
                    channels = shortLe(wav, body + 2)
                    sampleRate = intLe(wav, body + 4)
                    bits = shortLe(wav, body + 14)
                }

                "data" -> {
                    // A declared size past the end is a truncated file: take what is
                    // actually there rather than rejecting the whole thing, because
                    // a partial read is still audio the user can hear.
                    dataOffset = body
                    dataLength = minOf(size, wav.size - body)
                }
            }

            // Chunks are word-aligned: an odd size is followed by one pad byte.
            pos = body + size + (size and 1)
        }

        if (dataOffset < 0 || sampleRate <= 0 || channels <= 0 || bits <= 0) return null
        if (dataLength <= 0) return null
        return Parsed(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bits,
            pcm = wav.copyOfRange(dataOffset, dataOffset + dataLength),
        )
    }

    private fun ascii(b: ByteArray, at: Int, len: Int): String {
        if (at + len > b.size) return ""
        val sb = StringBuilder(len)
        for (i in 0 until len) sb.append((b[at + i].toInt() and 0xFF).toChar())
        return sb.toString()
    }

    private fun intLe(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun shortLe(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
}
