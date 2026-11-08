package com.arkadia.sonata.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The local-first persistence model.
 *
 * The governing principle is the specification's: **everything created in Sonata
 * belongs to the user.** Every text processed, narration generated, recording,
 * reading session and voice profile is persisted locally, and the user can create,
 * generate, listen, pause/resume, rename, save, export, revisit and delete.
 *
 * Two invariants are expressed *in the schema itself* rather than left to callers,
 * because a rule that lives only in a repository method is a rule a future caller
 * can forget:
 *
 *  1. **Deletion independence.** [Narration.documentId] is nullable and its foreign
 *     key is `ON DELETE SET NULL`. Deleting a document therefore cannot delete a
 *     narration the user wants to keep — the narration survives with its source
 *     reference cleared. The specification is explicit that "deleting a document
 *     must not silently delete a narration the user wants to keep", and this is the
 *     only place that guarantee can be made unconditional.
 *
 *  2. **The original is never overwritten.** [Document.originalText] and
 *     [Document.cleanedText] are separate columns. The preparation pipeline writes
 *     only the cleaned representation; the original is what the user gave Sonata
 *     and is preserved verbatim.
 */

/**
 * A text the user brought into Sonata.
 *
 * [originalText] is immutable in practice: the preparation layer writes
 * [cleanedText] and never touches the original. Keeping both is what lets the
 * Read-Along Canvas show the user's own text while the narration engine speaks the
 * normalised one.
 */
@Entity(
    tableName = "documents",
    indices = [Index("updatedAt"), Index("sourceType")],
)
data class Document(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Editable display title. Never empty — see [DocumentTitle]. */
    val title: String,

    /** Exactly what the user supplied. Never rewritten by normalisation. */
    @ColumnInfo(name = "originalText") val originalText: String,

    /**
     * The narration-ready representation produced by the preparation pipeline.
     *
     * Null until preparation has run. A document with a null [cleanedText] is
     * readable but not yet narratable, which is a real state the UI must show
     * rather than pretend away.
     */
    @ColumnInfo(name = "cleanedText") val cleanedText: String? = null,

    /** How the text arrived. Drives the "Imported texts" library filter. */
    @ColumnInfo(name = "sourceType") val sourceType: DocumentSource = DocumentSource.TYPED,

    /** When the document was created, epoch millis. */
    @ColumnInfo(name = "importedAt") val importedAt: Long,

    /** When the document was last changed, epoch millis. Drives "Recent". */
    @ColumnInfo(name = "updatedAt") val updatedAt: Long,
)

/** Where a document came from. */
enum class DocumentSource {
    /** Typed or pasted into Sonata. */
    TYPED,

    /** Handed over by another app (PROCESS_TEXT or share). */
    IMPORTED,

    /** A transcript of a recording the user made. */
    RECORDING,
}

/**
 * One generated narration.
 *
 * A narration is the durable artifact the specification cares most about: it owns
 * its audio file, its voice profile, its duration, its creation date and its
 * playback position, and it survives the deletion of the document it came from.
 *
 * [status] is the interrupted-generation contract. A narration is written as
 * [NarrationStatus.PENDING] before synthesis begins and only becomes
 * [NarrationStatus.COMPLETE] when the audio is on disk. A row found in
 * [NarrationStatus.GENERATING] after a process death is [NarrationStatus.INTERRUPTED]
 * — it is never silently presented as complete, because "no completed narration
 * should disappear" cuts both ways: an incomplete one must not be presented as
 * finished either.
 */
@Entity(
    tableName = "narrations",
    foreignKeys = [
        ForeignKey(
            entity = Document::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            // The deletion-independence invariant, enforced by SQLite itself.
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = VoiceProfile::class,
            parentColumns = ["id"],
            childColumns = ["voiceProfileId"],
            // A narration keeps its audio even if the voice profile is removed; the
            // audio is the user's, and losing it because a profile was tidied away
            // would be exactly the silent loss the specification forbids.
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("documentId"), Index("voiceProfileId"), Index("createdAt"), Index("status")],
)
data class Narration(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /**
     * The document this narration was made from, or null.
     *
     * Null is a normal, permanent state: it is what a narration becomes when its
     * source document is deleted. The narration is not orphaned — it is
     * independent, which is the point.
     */
    @ColumnInfo(name = "documentId") val documentId: Long? = null,

    /** Editable title, independent of the document's. */
    val title: String,

    /** Absolute path to the audio file in app-private storage. */
    @ColumnInfo(name = "audioPath") val audioPath: String? = null,

    /** The voice profile used, or null for a bundled base voice. */
    @ColumnInfo(name = "voiceProfileId") val voiceProfileId: Long? = null,

    /**
     * The model version that produced this audio.
     *
     * Recorded because a voice model can be replaced, and a narration must remain
     * attributable to the model that actually made it. Without this, "which model
     * made this?" is unanswerable after an update.
     */
    @ColumnInfo(name = "modelVersion") val modelVersion: String? = null,

    /** Audio duration in milliseconds; 0 until known. */
    @ColumnInfo(name = "durationMs") val durationMs: Long = 0,

    /** When the narration was created, epoch millis. */
    @ColumnInfo(name = "createdAt") val createdAt: Long,

    /** Where the user stopped listening, in milliseconds. */
    @ColumnInfo(name = "playbackPositionMs") val playbackPositionMs: Long = 0,

    /** Generation state. See the class KDoc. */
    val status: NarrationStatus = NarrationStatus.PENDING,

    /** Why generation failed, when it did. User-presentable. */
    @ColumnInfo(name = "errorReason") val errorReason: String? = null,

    /** Whether the user marked this narration a favourite. */
    val favorite: Boolean = false,
)

/**
 * Generation state of a narration.
 *
 * The transitions are deliberately narrow, and [LibraryRules] is the only place
 * that decides whether a transition is allowed — so an illegal transition is a
 * test failure rather than a corrupted row.
 */
enum class NarrationStatus {
    /** Recorded, synthesis not started. */
    PENDING,

    /** Synthesis in progress. A row left here after a process death is interrupted. */
    GENERATING,

    /** Audio is on disk and playable. */
    COMPLETE,

    /** Synthesis failed; [Narration.errorReason] says why. */
    FAILED,

    /**
     * The process died mid-synthesis.
     *
     * Distinct from [FAILED] on purpose: a failure is a fact about the work, an
     * interruption is a fact about the session. The user can retry either, but only
     * the interruption is recoverable without changing anything.
     */
    INTERRUPTED,
}

/**
 * A reusable voice.
 *
 * The specification separates **enrollment** (capture samples, create a profile)
 * from **model training** (adapt a neural model). The schema keeps them separate
 * too: [kind] says which one this profile is, and [modelPath] is null for an
 * enrolled-only profile. That is what lets enrollment ship on its own, before the
 * device gate for adaptation is passed.
 */
@Entity(
    tableName = "voice_profiles",
    indices = [Index("createdAt")],
)
data class VoiceProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** The name the user gave the voice. */
    val name: String,

    /** Whether this is an enrolled profile or an adapted model. */
    val kind: VoiceProfileKind,

    /**
     * Paths to the recorded samples, comma-separated.
     *
     * These never leave the device. The specification's privacy boundary is that
     * voice samples remain local, and nothing in this schema has a network path.
     */
    @ColumnInfo(name = "samplePaths") val samplePaths: String = "",

    /** Path to the adapted model, or null for an enrolled-only profile. */
    @ColumnInfo(name = "modelPath") val modelPath: String? = null,

    /** The model version, when there is a model. */
    @ColumnInfo(name = "modelVersion") val modelVersion: String? = null,

    /** Sample-quality score in 0..1, or null if not analysed. */
    @ColumnInfo(name = "qualityScore") val qualityScore: Float? = null,

    /** When the profile was created, epoch millis. */
    @ColumnInfo(name = "createdAt") val createdAt: Long,
)

/** Whether a voice profile is enrolled only, or has an adapted model. */
enum class VoiceProfileKind {
    /** Samples captured and analysed; no model. Usable as a reference. */
    ENROLLED,

    /** A model was adapted locally from the samples. */
    ADAPTED,
}

/** A user-defined collection of narrations. */
@Entity(tableName = "collections", indices = [Index("createdAt")])
data class Collection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "createdAt") val createdAt: Long,
)

/**
 * Membership of a narration in a collection.
 *
 * A join table rather than a column on [Narration], because a narration may belong
 * to several collections and because deleting a collection must not delete the
 * narrations in it — the same independence rule as documents, one level down.
 */
@Entity(
    tableName = "collection_items",
    primaryKeys = ["collectionId", "narrationId"],
    foreignKeys = [
        ForeignKey(
            entity = Collection::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Narration::class,
            parentColumns = ["id"],
            childColumns = ["narrationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("narrationId")],
)
data class CollectionItem(
    @ColumnInfo(name = "collectionId") val collectionId: Long,
    @ColumnInfo(name = "narrationId") val narrationId: Long,
    @ColumnInfo(name = "addedAt") val addedAt: Long,
)

/**
 * A listening session.
 *
 * Recorded so "Recent activity" is a fact rather than a guess, and so an
 * interrupted session can be distinguished from an interrupted *generation*.
 */
@Entity(
    tableName = "reading_sessions",
    foreignKeys = [
        ForeignKey(
            entity = Narration::class,
            parentColumns = ["id"],
            childColumns = ["narrationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("narrationId"), Index("startedAt")],
)
data class ReadingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "narrationId") val narrationId: Long,
    @ColumnInfo(name = "startedAt") val startedAt: Long,
    @ColumnInfo(name = "endedAt") val endedAt: Long? = null,
    @ColumnInfo(name = "lastPositionMs") val lastPositionMs: Long = 0,
)
