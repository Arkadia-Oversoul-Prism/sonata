package com.arkadia.sonata.reading

import com.arkadia.sonata.ResumePoint

/**
 * Where the read had got, **per document**.
 *
 * S3's acceptance criteria name "persistent reading position" and "resume after
 * process death" for a library of several documents. The app had one position for
 * one passage: `Prefs.lastPassage` and `Prefs.lastSpokenChars` are single values, so
 * reading document B discarded where the user was in document A. Returning to A
 * offered nothing, and the user had no way to tell that from "A was never opened".
 *
 * That is not a bug in [ResumePoint] — the rule for *whether* a stored offset may be
 * applied is unchanged and still lives there, so there is one authority for it. What
 * was missing is the keying: a position belongs to a document.
 *
 * Three properties are expressed here rather than left to the caller, because each
 * is a way the feature can lose the user's place:
 *
 *  1. **A position is looked up by document and text, not by recency.** A stored
 *     offset is only applied when the passage length and fingerprint both match the
 *     text in front of the user, so an edited document resumes from the top instead
 *     of resuming into the wrong paragraph. The final decision is delegated to
 *     [ResumePoint.offset], so the "nothing left worth resuming" and "past the end"
 *     rules are not restated here.
 *  2. **The store is bounded.** A long library would otherwise accumulate a row per
 *     document forever, which is storage the user cannot see or reclaim. The oldest
 *     positions are evicted, and eviction costs a lost place, never a wrong one.
 *  3. **A corrupt stored value decodes to what is still readable.** A mangled
 *     preference must not crash the app and must not be silently trusted. Decoding
 *     is total: unreadable entries are dropped and the rest are kept.
 */
data class ReadingPosition(
    /** The document this position belongs to. */
    val documentId: Long,

    /** How many characters of the passage had been read. */
    val offsetChars: Int,

    /** The length of the passage the offset was recorded against. */
    val passageLength: Int,

    /** [ReadingPositions.fingerprint] of that passage. */
    val passageFingerprint: Long,

    /** When the position was last recorded, epoch millis. */
    val recordedAt: Long,
)

/**
 * The pure rules for a library of reading positions.
 *
 * Kept free of `SharedPreferences`, `Room` and the Android framework for the same
 * reason [ResumePoint] is free of the Activity: "carry on where I left off" is the
 * whole safety property of a long-form reader, and a safety property that can only
 * be exercised through a device is one that will not be exercised. Everything here
 * is a total function over plain values.
 *
 * The persistence format lives here too. A second, untested parser in the
 * preference layer is exactly how a stored format drifts from the code that wrote
 * it, so [encode] and [decode] are the only implementation and they are tested as a
 * round trip, including the corrupt cases.
 */
object ReadingPositions {

    /** How many documents may keep a position. Older ones are evicted. */
    const val MAX_ENTRIES = 32

    /** The format version this build writes, and the only one it reads. */
    const val VERSION = "v1"

    private const val SEPARATOR = ";"
    private const val FIELD = ":"

    private val FNV_OFFSET_BASIS = 0xcbf29ce484222325uL
    private val FNV_PRIME = 0x100000001b3uL

    /**
     * Record where the read is in [documentId].
     *
     * Upserts: a document holds exactly one position, because a document with two
     * "carry on" affordances has no answer to "carry on from which?". The result is
     * ordered newest first and truncated to [limit], so eviction is deterministic
     * and the bound is a property of the rule rather than of the storage layer.
     *
     * [offsetChars] is clamped at 0. A negative offset is not a position and would
     * otherwise be indistinguishable from "no position" only by accident.
     */
    fun remember(
        existing: List<ReadingPosition>,
        documentId: Long,
        passage: String,
        offsetChars: Int,
        now: Long,
        limit: Int = MAX_ENTRIES,
    ): List<ReadingPosition> {
        val entry = ReadingPosition(
            documentId = documentId,
            offsetChars = offsetChars.coerceAtLeast(0),
            passageLength = passage.length,
            passageFingerprint = fingerprint(passage),
            recordedAt = now,
        )
        return (existing.filter { it.documentId != documentId } + entry)
            .sortedWith(
                compareByDescending<ReadingPosition> { it.recordedAt }
                    .thenBy { it.documentId },
            )
            .take(limit.coerceAtLeast(0))
    }

    /**
     * The offset to resume [documentId] from, or 0 to start at the beginning.
     *
     * 0 is returned whenever anything is uncertain: no stored position, a different
     * passage, or a position [ResumePoint] declines. A missing "carry on" is a small
     * annoyance; a wrong one is a bug the user cannot explain.
     */
    fun offsetFor(
        existing: List<ReadingPosition>,
        documentId: Long,
        passage: String,
    ): Int {
        val stored = existing.firstOrNull { it.documentId == documentId } ?: return 0
        if (stored.passageLength != passage.length) return 0
        if (stored.passageFingerprint != fingerprint(passage)) return 0
        // The caller holds the text, so the stored passage *is* the current passage
        // once the two checks above agree; ResumePoint owns the remaining rules.
        return ResumePoint.offset(
            storedPassage = passage,
            storedOffset = stored.offsetChars,
            currentPassage = passage,
        )
    }

    /**
     * Drop the position for [documentId].
     *
     * Called when a document is deleted. A position outliving its document is not
     * harmful on its own, but it occupies one of the [MAX_ENTRIES] slots and would
     * describe a document id that no longer exists.
     */
    fun forget(existing: List<ReadingPosition>, documentId: Long): List<ReadingPosition> =
        existing.filter { it.documentId != documentId }

    /**
     * A stable 64-bit identity for a passage.
     *
     * FNV-1a over code points, so an astral-plane character counts once rather than
     * as two halves of a surrogate pair.
     *
     * This is not a cryptographic hash and is not claimed to be one. It stands in
     * for "is this the same text?" alongside [ReadingPosition.passageLength], and a
     * collision would resume the user into the wrong place. That trade is taken
     * deliberately: storing the passage itself would duplicate every document into
     * the preference file, and the alternative — trusting the document id alone —
     * is wrong for the editable screen, where the text can change under a valid id.
     *
     * The one comparison that must never be approximate is the *offset*, and that
     * is not approximated: it is bounded by the length check that precedes it.
     */
    fun fingerprint(passage: String): Long {
        var hash = FNV_OFFSET_BASIS
        var i = 0
        while (i < passage.length) {
            val codePoint = passage.codePointAt(i)
            i += Character.charCount(codePoint)
            hash = (hash xor codePoint.toUInt().toULong()) * FNV_PRIME
        }
        return hash.toLong()
    }

    /**
     * Serialise [positions] for storage.
     *
     * Empty input encodes to the empty string, which is what an unset preference
     * reads back as — so "never recorded anything" and "recorded nothing" are the
     * same state rather than two.
     */
    fun encode(positions: List<ReadingPosition>): String {
        if (positions.isEmpty()) return ""
        return buildString {
            append(VERSION)
            for (p in positions) {
                append(SEPARATOR)
                append(p.documentId).append(FIELD)
                append(p.offsetChars).append(FIELD)
                append(p.passageLength).append(FIELD)
                append(p.passageFingerprint).append(FIELD)
                append(p.recordedAt)
            }
        }
    }

    /**
     * Read [encoded] back, keeping every entry that is still readable.
     *
     * Total: it never throws. An unknown version or a malformed entry yields fewer
     * positions, never an exception and never a guessed value — a position that
     * cannot be read is a lost place, and losing a place is recoverable in a way
     * that resuming from a fabricated offset is not.
     */
    fun decode(encoded: String): List<ReadingPosition> {
        if (encoded.isBlank()) return emptyList()
        val parts = encoded.split(SEPARATOR)
        if (parts.firstOrNull() != VERSION) return emptyList()
        return parts.drop(1).mapNotNull { decodeEntry(it) }
    }

    private fun decodeEntry(raw: String): ReadingPosition? {
        val fields = raw.split(FIELD)
        if (fields.size != 5) return null
        val documentId = fields[0].toLongOrNull() ?: return null
        val offsetChars = fields[1].toIntOrNull() ?: return null
        val passageLength = fields[2].toIntOrNull() ?: return null
        val passageFingerprint = fields[3].toLongOrNull() ?: return null
        val recordedAt = fields[4].toLongOrNull() ?: return null
        if (offsetChars < 0 || passageLength < 0) return null
        return ReadingPosition(
            documentId = documentId,
            offsetChars = offsetChars,
            passageLength = passageLength,
            passageFingerprint = passageFingerprint,
            recordedAt = recordedAt,
        )
    }
}
