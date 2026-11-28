package com.arkadia.sonata.imports

import com.arkadia.sonata.TextLimits
import com.arkadia.sonata.TextValidation
import com.arkadia.sonata.data.DocumentTitle
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Turns a file the user shared into Sonata into text the reader can speak.
 *
 * S3's first step is "Import -> Open -> Read". The app had two ways in, both of
 * them *text*: PROCESS_TEXT (the selection menu) and SEND with `EXTRA_TEXT` (the
 * share sheet's plain-text body). A user who shares a `.txt` or `.md` **file**
 * sends `EXTRA_STREAM` instead, and that arrived nowhere — the handoff looked for
 * a text extra, found none, and told the user "No text selected".
 *
 * Everything here is a pure function over bytes and a file name, so the decisions
 * that matter (what is accepted, what is refused, and why) are exercised on the
 * JVM without a device or a ContentProvider. [com.arkadia.sonata.ProcessTextActivity]
 * is the only Android-bound part: it opens the stream and reports the outcome.
 */
object DocumentImport {

    /**
     * The most bytes read from a shared file.
     *
     * A ceiling is required because the stream is chosen by another app and can be
     * arbitrarily large; reading it whole would exhaust memory before any rule could
     * refuse it. Two megabytes is far above any text a read-aloud session covers,
     * and the *character* ceiling that actually bounds the read is
     * [TextLimits.MAX_INPUT_CHARS], applied after decoding so an over-long document
     * is reported as truncated rather than silently cut.
     */
    const val MAX_IMPORT_BYTES = 2_000_000

    /** How the bytes were interpreted, so the caller can say so if it matters. */
    enum class ImportEncoding { UTF8, LATIN1 }

    /** Why a shared file was refused. */
    enum class Reason {
        /** Larger than [MAX_IMPORT_BYTES]. */
        TOO_LARGE,

        /** Contains a NUL byte, which no text file does. */
        BINARY,
    }

    /** The outcome of importing one shared file. */
    sealed class ImportOutcome {
        /**
         * Speakable text.
         *
         * [truncatedFrom] is the original character count when the text exceeded
         * [TextLimits.MAX_INPUT_CHARS], and null otherwise — the same contract as
         * [TextValidation.Truncated], so a caller can tell the user what happened.
         */
        data class Accepted(
            val text: String,
            val title: String,
            val encoding: ImportEncoding,
            val truncatedFrom: Int? = null,
        ) : ImportOutcome()

        data class Rejected(val reason: Reason) : ImportOutcome()

        /** The file decoded to nothing speakable. */
        object Empty : ImportOutcome()
    }

    /** The result of reading a stream under a byte ceiling. */
    sealed class StreamRead {
        class Ok(val bytes: ByteArray) : StreamRead()

        /** More than the ceiling was available; nothing was kept. */
        object OverCeiling : StreamRead()
    }

    /**
     * Read at most [maxBytes] from [input], without reading the rest.
     *
     * One byte past the ceiling is read on purpose: that is what distinguishes a
     * file of exactly [maxBytes] (accepted) from one byte more (refused). A read
     * that stopped at the ceiling could not tell them apart.
     */
    fun readBounded(input: InputStream, maxBytes: Int = MAX_IMPORT_BYTES): StreamRead {
        val buffer = ByteArray(maxBytes + 1)
        var filled = 0
        while (filled < buffer.size) {
            val read = input.read(buffer, filled, buffer.size - filled)
            if (read < 0) break
            filled += read
        }
        if (filled > maxBytes) return StreamRead.OverCeiling
        return StreamRead.Ok(buffer.copyOf(filled))
    }

    /**
     * Import [bytes], which arrived under the name [fileName].
     *
     * The title comes from the file name when there is one, because that is the name
     * the user recognises from wherever the file came from; otherwise it is derived
     * from the first line, the rule [DocumentTitle] already applies at creation.
     * Both go through [DocumentTitle], so a title is never empty and never longer
     * than the display limit.
     */
    fun import(fileName: String?, bytes: ByteArray): ImportOutcome {
        if (bytes.size > MAX_IMPORT_BYTES) return ImportOutcome.Rejected(Reason.TOO_LARGE)
        if (bytes.any { it == 0.toByte() }) return ImportOutcome.Rejected(Reason.BINARY)

        val (text, encoding) = decode(bytes)
        return when (val validated = TextLimits.validate(text)) {
            TextValidation.Empty -> ImportOutcome.Empty

            is TextValidation.Ready -> ImportOutcome.Accepted(
                text = validated.text,
                title = title(fileName, validated.text),
                encoding = encoding,
            )

            is TextValidation.Truncated -> ImportOutcome.Accepted(
                text = validated.text,
                title = title(fileName, validated.text),
                encoding = encoding,
                truncatedFrom = validated.originalLength,
            )
        }
    }

    /**
     * Decode [bytes] as UTF-8, falling back to ISO-8859-1.
     *
     * `String(bytes, UTF_8)` is not used because it *substitutes* U+FFFD for
     * malformed input, which turns a decoding failure into text the user never
     * wrote. Strict decoding reports the failure instead, and the fallback then
     * interprets the bytes as Latin-1 — a mapping that cannot fail, so a
     * Windows-era `.txt` reads as itself rather than as a refusal. The
     * interpretation is returned so it is never a guess the caller cannot see.
     */
    private fun decode(bytes: ByteArray): Pair<String, ImportEncoding> {
        val withoutBom = if (
            bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }

        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(withoutBom)).toString() to ImportEncoding.UTF8
        } catch (_: CharacterCodingException) {
            String(withoutBom, StandardCharsets.ISO_8859_1) to ImportEncoding.LATIN1
        }
    }

    /**
     * A title for the imported document.
     *
     * The file name without its final extension, because `chapter-one.md` is a name
     * and `.md` is not. A name with no extension is kept whole, and a name that is
     * blank falls back to the text's first line rather than to "Untitled".
     */
    private fun title(fileName: String?, text: String): String {
        val base = fileName?.substringAfterLast('/')?.trim().orEmpty()
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot).trim() else base
        return if (stem.isNotEmpty()) DocumentTitle.rename(stem) else DocumentTitle.fromText(text)
    }
}
