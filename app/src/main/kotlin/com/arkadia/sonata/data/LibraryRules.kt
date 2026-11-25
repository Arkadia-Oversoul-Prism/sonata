package com.arkadia.sonata.data

/**
 * The library's rules, as pure functions.
 *
 * These are separated from the repository and the database on purpose. The rules
 * that decide what the user sees and what a deletion is allowed to do are the
 * safety properties of the library, and a safety property that can only be
 * exercised through a database is a safety property that will not be exercised.
 * Everything here is a total function over plain values, so it is testable without
 * Room, without a device, and without a coroutine.
 */

/** The library views the specification names. */
enum class LibraryFilter {
    /** Every document and narration. */
    ALL,

    /** Recently touched items, newest first. */
    RECENT,

    /** Generated narrations only. */
    NARRATIONS,

    /** Documents that arrived from another app. */
    IMPORTED,

    /** Documents that are transcripts of the user's own recordings. */
    RECORDINGS,

    /** Narrations the user marked as favourites. */
    FAVORITES,

    /** A user-defined collection. */
    COLLECTION,
}

/** A single row in the library, whichever table it came from. */
sealed class LibraryEntry {
    abstract val id: Long
    abstract val title: String
    abstract val updatedAt: Long

    data class OfDocument(val document: Document) : LibraryEntry() {
        override val id: Long get() = document.id
        override val title: String get() = document.title
        override val updatedAt: Long get() = document.updatedAt
    }

    data class OfNarration(val narration: Narration) : LibraryEntry() {
        override val id: Long get() = narration.id
        override val title: String get() = narration.title
        override val updatedAt: Long get() = narration.createdAt
    }
}

object LibraryRules {

    /**
     * Apply [filter] to the library.
     *
     * [collectionNarrationIds] is the membership of the collection being viewed, and
     * is only consulted for [LibraryFilter.COLLECTION]; passing it for any other
     * filter is harmless.
     */
    fun apply(
        filter: LibraryFilter,
        documents: List<Document>,
        narrations: List<Narration>,
        collectionNarrationIds: Set<Long> = emptySet(),
        recentLimit: Int = RECENT_LIMIT,
    ): List<LibraryEntry> {
        val entries: List<LibraryEntry> = when (filter) {
            LibraryFilter.ALL ->
                documents.map { LibraryEntry.OfDocument(it) } +
                    narrations.map { LibraryEntry.OfNarration(it) }

            LibraryFilter.RECENT ->
                (documents.map { LibraryEntry.OfDocument(it) } +
                    narrations.map { LibraryEntry.OfNarration(it) })
                    .sortedByDescending { it.updatedAt }
                    .take(recentLimit)

            LibraryFilter.NARRATIONS ->
                narrations.map { LibraryEntry.OfNarration(it) }

            LibraryFilter.IMPORTED ->
                documents.filter { it.sourceType == DocumentSource.IMPORTED }
                    .map { LibraryEntry.OfDocument(it) }

            LibraryFilter.RECORDINGS ->
                documents.filter { it.sourceType == DocumentSource.RECORDING }
                    .map { LibraryEntry.OfDocument(it) }

            LibraryFilter.FAVORITES ->
                narrations.filter { it.favorite }.map { LibraryEntry.OfNarration(it) }

            LibraryFilter.COLLECTION ->
                narrations.filter { it.id in collectionNarrationIds }
                    .map { LibraryEntry.OfNarration(it) }
        }
        return entries.sortedByDescending { it.updatedAt }
    }

    /** How many items "Recent" shows. */
    const val RECENT_LIMIT = 50

    /**
     * Whether a narration may move from [from] to [to].
     *
     * The transition table is explicit rather than implied, because the interrupted
     * -generation contract depends on it: a narration may only reach
     * [NarrationStatus.COMPLETE] from [NarrationStatus.GENERATING], so a row that
     * was interrupted can never be quietly relabelled complete. It must be retried,
     * which is a visible action.
     */
    fun canTransition(from: NarrationStatus, to: NarrationStatus): Boolean = when (from) {
        NarrationStatus.PENDING ->
            to == NarrationStatus.GENERATING || to == NarrationStatus.FAILED

        NarrationStatus.GENERATING ->
            to == NarrationStatus.COMPLETE ||
                to == NarrationStatus.FAILED ||
                to == NarrationStatus.INTERRUPTED

        // A completed narration is terminal: re-generating produces a new narration
        // rather than overwriting the audio the user already has.
        NarrationStatus.COMPLETE -> false

        // A failed or interrupted narration may be retried.
        NarrationStatus.FAILED -> to == NarrationStatus.GENERATING
        NarrationStatus.INTERRUPTED -> to == NarrationStatus.GENERATING
    }

    /**
     * Whether a narration is playable.
     *
     * Only a complete narration with audio on disk is playable. This is the single
     * predicate the player consults, so an interrupted narration can never be
     * presented as something the user can listen to.
     */
    fun isPlayable(narration: Narration): Boolean =
        narration.status == NarrationStatus.COMPLETE && !narration.audioPath.isNullOrBlank()

    /**
     * Whether a narration is recoverable without the user changing anything.
     *
     * An interrupted generation is recoverable by retrying; a failed one may need
     * the user to fix something first, so it is reported differently.
     */
    fun isRecoverable(narration: Narration): Boolean =
        narration.status == NarrationStatus.INTERRUPTED

    /**
     * The title to show for a narration whose document has been deleted.
     *
     * A narration that outlives its document keeps its own title, so this is only a
     * fallback for a narration that never had one. It exists so the library never
     * shows a blank row where the user's audio is.
     */
    fun displayTitle(narration: Narration): String =
        narration.title.ifBlank { UNTITLED_NARRATION }

    const val UNTITLED_NARRATION = "Untitled narration"
}

/**
 * Titles for documents.
 *
 * A document always has a title, because a library of untitled rows is not a
 * library. The rule is pure so it can be tested, and it is applied at creation
 * rather than at display so the stored value is always meaningful.
 */
object DocumentTitle {

    /** The longest title kept before it is shortened for display. */
    const val MAX_LENGTH = 80

    /**
     * Derive a title from the document's own text.
     *
     * The first non-blank line, shortened. This is what makes an imported document
     * arrive with a name the user recognises instead of "Untitled".
     */
    fun fromText(text: String): String {
        val firstLine = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return UNTITLED
        return shorten(firstLine)
    }

    /** Shorten [title] to [MAX_LENGTH], marking the cut. */
    fun shorten(title: String): String {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return UNTITLED
        return if (trimmed.length <= MAX_LENGTH) trimmed else trimmed.take(MAX_LENGTH - 1).trimEnd() + "\u2026"
    }

    /** Rename, refusing to store an empty title. */
    fun rename(candidate: String): String = shorten(candidate).ifBlank { UNTITLED }

    const val UNTITLED = "Untitled"
}
