package com.arkadia.sonata.export

import com.arkadia.sonata.data.Narration
import java.io.File

/**
 * Writes a narration's audio out as a real file the user can keep.
 *
 * The seam exists so the export *rules* — which formats are offered, what a file
 * is named, that an export is a copy and never a move — are testable on the JVM
 * without `MediaCodec`, `MediaStore` or a device.
 */
interface AudioExporter {

    /**
     * The formats this build can actually produce.
     *
     * MP3 is absent unless a bundled offline encoder is present. The UI reads this
     * rather than assuming, so a format Sonata cannot write is never offered — the
     * specification's rule that a format is not offered by reaching for a cloud
     * encoder.
     */
    fun supportedFormats(): Set<AudioFormat>

    /**
     * Export [narration] in [format] to a user-reachable location.
     *
     * Returns the destination, or a failure with a user-presentable reason. The
     * canonical file is left untouched: export copies.
     */
    suspend fun export(narration: Narration, format: AudioFormat): ExportResult
}

/** Outcome of an export. */
sealed class ExportResult {
    /** [destination] is a human-readable location, e.g. a MediaStore display path. */
    data class Success(val destination: String, val bytes: Long) : ExportResult()

    /** [reason] is safe to show the user. */
    data class Failure(val reason: String) : ExportResult()
}

/**
 * [AudioExporter] that writes into a directory.
 *
 * Used directly for the app's own export folder and by the MediaStore exporter in
 * Phase 7, which supplies a directory it has been granted. Keeping the file logic
 * here — naming, uniqueness, the copy-not-move rule — means the MediaStore path
 * adds only the platform plumbing, not a second copy of the rules.
 */
class FileAudioExporter(
    private val fileStore: FileStore,
    private val encoder: AudioEncoder,
    private val destinationDir: File,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) : AudioExporter {

    override fun supportedFormats(): Set<AudioFormat> =
        AudioFormat.entries.filter { encoder.supports(it) }.toSet()

    override suspend fun export(narration: Narration, format: AudioFormat): ExportResult {
        if (!encoder.supports(format)) {
            return ExportResult.Failure("${format.name} export is not available on this build")
        }
        val canonical = fileStore.canonicalPath(narration.id)
        if (!canonical.isFile) {
            return ExportResult.Failure("This narration has no audio to export")
        }

        val pcm = WavWriter.payload(canonical.readBytes())
        if (pcm.isEmpty()) {
            return ExportResult.Failure("This narration's audio is empty")
        }

        val encoded = encoder.encode(pcm, sampleRate, format)
        if (!destinationDir.exists()) destinationDir.mkdirs()

        val taken = destinationDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val name = ExportNaming.uniqueName(narration.title, format, taken)
        val out = File(destinationDir, name)
        out.writeBytes(encoded)
        return ExportResult.Success(out.absolutePath, encoded.size.toLong())
    }

    companion object {
        /** 24 kHz mono is what the offline neural voices produce. */
        const val DEFAULT_SAMPLE_RATE = 24_000
    }
}
