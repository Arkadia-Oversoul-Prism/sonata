package com.arkadia.sonata.export

import java.io.File

/**
 * Owns the mapping from a narration to its audio file, and is the **only** thing
 * that deletes one.
 *
 * Phase 1 made deletion independence structural at the *row* level: a document
 * deletion cannot remove a narration record, because the foreign key is
 * `ON DELETE SET NULL`. This interface makes the same guarantee structural at the
 * *file* level. If every file deletion goes through [delete], then "deleting a
 * document never touches a narration file" is a property of there being one
 * deletion path, not a rule each caller must remember.
 *
 * Canonical audio is always WAV (PCM), because that is what the synthesis path
 * produces and it is the one format that needs no encoder. Export transcodes from
 * it, and an export is a *copy* out of here, never a move, so an export can never
 * destroy the original.
 */
interface FileStore {

    /** The canonical WAV file for [narrationId]. */
    fun canonicalPath(narrationId: Long): File

    /** The path a partial write goes to before it is promoted. */
    fun partialPath(narrationId: Long): File

    /** Whether a canonical file exists for [narrationId]. */
    fun exists(narrationId: Long): Boolean

    /**
     * Write [wavBytes] as the canonical audio for [narrationId].
     *
     * Written to a `.partial` path and renamed on success, so a crash mid-write
     * never leaves a half-file that looks complete. The rename is atomic on the
     * same filesystem, which is why the partial file is a sibling.
     */
    fun write(narrationId: Long, wavBytes: ByteArray): File

    /**
     * Delete every file belonging to [narrationId]. Idempotent: deleting an
     * already-absent file is success, not an error, so a retry after a partial
     * failure converges.
     */
    fun delete(narrationId: Long): Boolean

    /** Total bytes used by canonical audio. */
    fun totalBytes(): Long

    /** Bytes used by one narration's audio, or 0. */
    fun bytesFor(narrationId: Long): Long

    /**
     * Every narration id that has a file.
     *
     * A listing rather than a probe, so the orphan scan is O(files) instead of
     * O(highest id). The store is the only thing that knows what is on disk, which
     * is why the listing belongs here and not in the accountant.
     */
    fun allIds(): List<Long>
}

/**
 * [FileStore] over a directory.
 *
 * The directory is injected rather than derived from a `Context`, so the store is
 * testable on the JVM with a temporary directory and the production path is a
 * one-line subclass. That split is what lets the file-level deletion-independence
 * rule be pinned by a test rather than asserted in a comment.
 */
open class DirectoryFileStore(private val root: File) : FileStore {

    init {
        if (!root.exists()) root.mkdirs()
    }

    override fun canonicalPath(narrationId: Long): File = File(root, "$narrationId.wav")

    override fun partialPath(narrationId: Long): File = File(root, "$narrationId.wav.partial")

    override fun exists(narrationId: Long): Boolean = canonicalPath(narrationId).isFile

    override fun write(narrationId: Long, wavBytes: ByteArray): File {
        val partial = partialPath(narrationId)
        partial.writeBytes(wavBytes)
        val target = canonicalPath(narrationId)
        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) {
            // Rename can fail across a mount boundary; fall back to a copy so the
            // write still succeeds rather than losing the audio.
            target.writeBytes(wavBytes)
            partial.delete()
        }
        return target
    }

    override fun delete(narrationId: Long): Boolean {
        var removed = false
        val canonical = canonicalPath(narrationId)
        if (canonical.isFile && canonical.delete()) removed = true
        val partial = partialPath(narrationId)
        if (partial.isFile && partial.delete()) removed = true
        return removed
    }

    override fun totalBytes(): Long =
        root.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    override fun bytesFor(narrationId: Long): Long = canonicalPath(narrationId).length()

    override fun allIds(): List<Long> =
        root.listFiles()
            ?.filter { it.isFile }
            ?.mapNotNull { it.name.substringBefore('.').toLongOrNull() }
            ?.distinct()
            ?: emptyList()
}
