package com.arkadia.sonata.export

/**
 * The audio container formats Sonata can write.
 *
 * Every format here is produced **offline**, by the platform or by Sonata itself.
 * There is deliberately no format that requires a network encoder: the
 * specification's offline boundary applies to export exactly as it applies to
 * synthesis, so a format that could only be produced by a cloud service is not
 * offered at all rather than offered and silently degraded.
 */
enum class AudioFormat(
    val extension: String,
    val mimeType: String,
    /**
     * Whether the platform can produce this format with no bundled encoder.
     *
     * This is a statement about Android, not about this build: Android ships an
     * AAC encoder and no MP3 encoder. What *this* build can actually write is
     * decided by [AudioExporter.supportedFormats], which consults the injected
     * encoder rather than this flag.
     */
    val platformProvided: Boolean,
) {
    /** Uncompressed PCM in a RIFF container. Sonata writes the header itself. */
    WAV("wav", "audio/wav", true),

    /** AAC in an MP4 container, via `MediaCodec` + `MediaMuxer`. */
    M4A("m4a", "audio/mp4", true),

    /**
     * MPEG-1 Audio Layer III.
     *
     * Android ships no MP3 *encoder* (only a decoder), so this format is offered
     * only when a bundled offline encoder is present. The UI reads
     * [AudioExporter.supportedFormats] rather than assuming, so MP3 is never
     * offered by reaching for a cloud encoder.
     */
    MP3("mp3", "audio/mpeg", false),
}
