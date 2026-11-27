package com.arkadia.sonata

import com.arkadia.sonata.data.LibraryRules
import com.arkadia.sonata.data.Narration
import com.arkadia.sonata.data.NarrationStatus

/** What a library row can do when the user taps it. */
enum class LibraryOffer {
    /** A file exists and may be played. */
    REPLAY,

    /** Nothing to play. [LibraryRow.subtitle] says why. */
    NONE,
}

/** One row of the saved-narration list. */
data class LibraryRow(
    val id: Long,
    val title: String,
    val subtitle: String,
    val offer: LibraryOffer,
)

/**
 * Turns stored narrations into the rows the library shows.
 *
 * Pure, so the rule that matters can be tested without a database, a file system
 * or a device. That rule is the one the product cannot get wrong: **a row is only
 * offered for replay when the audio is actually there.** The database saying
 * `COMPLETE` is not that proof — a row can be complete and its file gone (the
 * user cleared storage, or a write was interrupted after the row was updated).
 * [LibraryRules.isPlayable] covers the row's own claim and [audioExists] covers
 * the disk, and both are required.
 */
object LibraryPresenter {

    /**
     * The rows for [narrations], newest first.
     *
     * [audioExists] is injected rather than assumed so this stays a pure function.
     * The screen passes a real file check; the tests pass a map.
     */
    fun rows(
        narrations: List<Narration>,
        audioExists: (String) -> Boolean = { true },
    ): List<LibraryRow> = narrations
        .sortedByDescending { it.createdAt }
        .map { narration ->
            val playable = LibraryRules.isPlayable(narration) &&
                audioExists(narration.audioPath.orEmpty())
            LibraryRow(
                id = narration.id,
                title = LibraryRules.displayTitle(narration),
                subtitle = subtitle(narration, playable),
                offer = if (playable) LibraryOffer.REPLAY else LibraryOffer.NONE,
            )
        }

    /**
     * The line above the list, derived from the rows already built.
     *
     * It counts what the user can actually play rather than what the database
     * holds, for the same reason [rows] does: "3 saved" beside a list where none of
     * them play is a claim the app cannot keep. Taking the rows rather than the
     * narrations keeps the file check to one pass.
     */
    fun summary(rows: List<LibraryRow>): String {
        if (rows.isEmpty()) return NO_NARRATIONS
        val ready = rows.count { it.offer == LibraryOffer.REPLAY }
        return "$ready of ${rows.size} ready to replay"
    }

    /** Duration as `m:ss`, or `h:mm:ss` past an hour. Zero means "not known yet". */
    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0) return ""
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private fun subtitle(narration: Narration, playable: Boolean): String {
        val state = when (narration.status) {
            NarrationStatus.COMPLETE ->
                if (playable) READY else AUDIO_MISSING

            NarrationStatus.GENERATING -> "Generating\u2026"
            NarrationStatus.PENDING -> "Queued"
            NarrationStatus.INTERRUPTED -> "Interrupted"
            NarrationStatus.FAILED -> "Failed"
        }
        val duration = formatDuration(narration.durationMs)
        return if (playable && duration.isNotEmpty()) "$state \u00b7 $duration" else state
    }

    const val NO_NARRATIONS = "No saved narrations yet"
    private const val READY = "Ready"
    private const val AUDIO_MISSING = "Audio file missing"
}
