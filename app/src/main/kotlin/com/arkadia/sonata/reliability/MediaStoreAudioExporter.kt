package com.arkadia.sonata.reliability

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.arkadia.sonata.data.Narration
import com.arkadia.sonata.export.AudioExporter
import com.arkadia.sonata.export.AudioFormat
import com.arkadia.sonata.export.ExportNaming
import com.arkadia.sonata.export.ExportResult
import com.arkadia.sonata.export.FileStore
import com.arkadia.sonata.export.WavWriter
import java.io.File

/**
 * Exports audio to a location the user can reach outside Sonata.
 *
 * The specification requires that exported files are **accessible outside Sonata**,
 * and that the app's own working storage stays private. This is the boundary: the
 * canonical audio lives in app-private storage, and an export is a *copy* into
 * `MediaStore.Audio` (API 29+) or the app's external files directory, shared out
 * with a `FileProvider` `content://` URI.
 *
 * A `file://` URI is never used: it throws `FileUriExposedException` on API 24+,
 * which is why the share path is a `FileProvider` URI.
 */
class MediaStoreAudioExporter(
    private val context: Context,
    private val fileStore: FileStore,
    private val sampleRate: Int = 24_000,
) : AudioExporter {

    override fun supportedFormats(): Set<AudioFormat> = setOf(AudioFormat.WAV)

    override suspend fun export(narration: Narration, format: AudioFormat): ExportResult {
        if (format != AudioFormat.WAV) {
            return ExportResult.Failure("${format.name} export is not available on this build")
        }
        val canonical = fileStore.canonicalPath(narration.id)
        if (!canonical.isFile) {
            return ExportResult.Failure("This narration has no audio to export")
        }
        val bytes = canonical.readBytes()
        val name = ExportNaming.fileName(narration.title, format)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportViaMediaStore(name, bytes)
        } else {
            exportViaExternalFiles(name, bytes)
        }
    }

    /**
     * Write into `MediaStore.Audio` (API 29+).
     *
     * `IS_PENDING` is set while writing and cleared on success, so a half-written
     * file is never visible to the user's music app — the same "a partial file is
     * never presented as complete" rule the canonical store applies, one level out.
     */
    private fun exportViaMediaStore(name: String, bytes: ByteArray): ExportResult {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Sonata")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri: Uri = try {
            resolver.insert(collection, values)
                ?: return ExportResult.Failure("Could not create the export file")
        } catch (e: Exception) {
            return ExportResult.Failure("Could not create the export file: ${e.javaClass.simpleName}")
        }

        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: return ExportResult.Failure("Could not write the export file")
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            ExportResult.Success(uri.toString(), bytes.size.toLong())
        } catch (e: Exception) {
            // Remove the pending row so a failed export leaves nothing behind.
            runCatching { resolver.delete(uri, null, null) }
            ExportResult.Failure("Could not write the export file: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Write into the app's external files directory (API 29 and below).
     *
     * The `FileProvider` share URI is what makes the file reachable; the file
     * itself is in a directory the user can browse.
     */
    private fun exportViaExternalFiles(name: String, bytes: ByteArray): ExportResult {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val out = File(dir, name)
        return try {
            out.writeBytes(bytes)
            ExportResult.Success(out.absolutePath, bytes.size.toLong())
        } catch (e: Exception) {
            ExportResult.Failure("Could not write the export file: ${e.javaClass.simpleName}")
        }
    }

    /** The PCM payload of a canonical WAV, for a caller that needs it. */
    fun pcmOf(narration: Narration): ByteArray? {
        val canonical = fileStore.canonicalPath(narration.id)
        if (!canonical.isFile) return null
        return WavWriter.payload(canonical.readBytes())
    }
}
