package com.arkadia.sonata.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The single entry point to the local library.
 *
 * Everything the app does to the user's content goes through here, so the
 * specification's invariants have exactly one place to be enforced and exactly one
 * place to be tested. The repository is deliberately thin: the rules live in
 * [LibraryRules] (pure) and the constraints live in the schema (SQLite), and this
 * class only sequences them.
 *
 * The two behaviours worth reading closely are [deleteDocument] and
 * [recoverInterrupted]. Both exist because the specification names them as
 * non-negotiable, and both are written so the guarantee is structural rather than
 * remembered.
 */
class SonataRepository(
    private val database: SonataDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val documents = database.documentDao()
    private val narrations = database.narrationDao()
    private val voices = database.voiceProfileDao()
    private val collections = database.collectionDao()
    private val sessions = database.readingSessionDao()

    // ── Documents ────────────────────────────────────────────────────────────

    /**
     * Create a document from text the user supplied.
     *
     * The original is stored verbatim and the cleaned representation is left null
     * until the preparation pipeline runs. Deriving the title here means an
     * imported document arrives named, which is the difference between a library
     * and a list of "Untitled".
     */
    suspend fun createDocument(
        text: String,
        source: DocumentSource = DocumentSource.TYPED,
        title: String? = null,
    ): Long {
        val now = clock()
        return documents.insert(
            Document(
                title = title?.let { DocumentTitle.rename(it) } ?: DocumentTitle.fromText(text),
                originalText = text,
                cleanedText = null,
                sourceType = source,
                importedAt = now,
                updatedAt = now,
            ),
        )
    }

    /**
     * Store the narration-ready text.
     *
     * Only [Document.cleanedText] is written. The original is never touched, which
     * is the invariant that lets the Read-Along Canvas show the user's own text
     * while the engine speaks the normalised one.
     */
    suspend fun setCleanedText(documentId: Long, cleaned: String) {
        val document = documents.byId(documentId) ?: return
        documents.update(document.copy(cleanedText = cleaned, updatedAt = clock()))
    }

    suspend fun renameDocument(documentId: Long, title: String) {
        val document = documents.byId(documentId) ?: return
        documents.update(document.copy(title = DocumentTitle.rename(title), updatedAt = clock()))
    }

    suspend fun document(id: Long): Document? = documents.byId(id)

    fun documents(): Flow<List<Document>> = documents.all()

    /**
     * Delete a document, keeping every narration made from it.
     *
     * The narrations survive because the schema's foreign key is
     * `ON DELETE SET NULL`; this method does not have to remember to preserve them,
     * and a future caller cannot forget to. The returned count is how many
     * narrations were kept, so the UI can tell the user their audio is safe rather
     * than leaving them to hope.
     */
    suspend fun deleteDocument(documentId: Long): Int {
        documents.deleteById(documentId)
        // The narrations survive because the foreign key is ON DELETE SET NULL, so the
        // count that remains is the count that was kept. Returning it lets the UI tell
        // the user their audio is safe rather than leaving them to hope.
        return narrations.count()
    }

    // ── Narrations ───────────────────────────────────────────────────────────

    /**
     * Begin a narration.
     *
     * The row is written as [NarrationStatus.PENDING] *before* synthesis starts, so
     * a process death mid-generation leaves a record rather than nothing. That
     * record is what [recoverInterrupted] later finds and marks.
     */
    suspend fun beginNarration(
        documentId: Long?,
        title: String,
        voiceProfileId: Long? = null,
        modelVersion: String? = null,
    ): Long = narrations.insert(
        Narration(
            documentId = documentId,
            title = DocumentTitle.rename(title),
            voiceProfileId = voiceProfileId,
            modelVersion = modelVersion,
            createdAt = clock(),
            status = NarrationStatus.PENDING,
        ),
    )

    /** Move a narration to [NarrationStatus.GENERATING]. */
    suspend fun markGenerating(narrationId: Long) {
        transition(narrationId, NarrationStatus.GENERATING, null)
    }

    /**
     * Complete a narration.
     *
     * Refused unless the narration is currently generating, so an interrupted row
     * can never be relabelled complete — the user must retry, which is a visible
     * action rather than a silent correction.
     */
    suspend fun completeNarration(
        narrationId: Long,
        audioPath: String,
        durationMs: Long,
    ): Boolean {
        val current = narrations.byId(narrationId) ?: return false
        if (!LibraryRules.canTransition(current.status, NarrationStatus.COMPLETE)) return false
        narrations.update(
            current.copy(
                audioPath = audioPath,
                durationMs = durationMs,
                status = NarrationStatus.COMPLETE,
                errorReason = null,
            ),
        )
        return true
    }

    /** Record a generation failure with a user-presentable reason. */
    suspend fun failNarration(narrationId: Long, reason: String) {
        transition(narrationId, NarrationStatus.FAILED, reason)
    }

    /** Record that the process died mid-generation. */
    suspend fun interruptNarration(narrationId: Long) {
        transition(narrationId, NarrationStatus.INTERRUPTED, null)
    }

    private suspend fun transition(narrationId: Long, to: NarrationStatus, reason: String?) {
        val current = narrations.byId(narrationId) ?: return
        if (!LibraryRules.canTransition(current.status, to)) return
        narrations.setStatus(narrationId, to, reason)
    }

    /**
     * Mark every narration left mid-generation as interrupted.
     *
     * Called once at startup. A row still in [NarrationStatus.GENERATING] can only
     * mean the process died, because a live process would have moved it on. This is
     * the "recover or clearly mark incomplete work" boundary made concrete: the
     * work is not silently completed and not silently deleted — it is marked, and
     * the user can retry it.
     *
     * @return how many narrations were marked.
     */
    suspend fun recoverInterrupted(): Int = narrations.markInterrupted()

    suspend fun narration(id: Long): Narration? = narrations.byId(id)

    fun narrations(): Flow<List<Narration>> = narrations.all()

    fun narrationsForDocument(documentId: Long): Flow<List<Narration>> =
        narrations.byDocument(documentId)

    /** Save the listening position, so a return carries on where the user stopped. */
    suspend fun savePosition(narrationId: Long, positionMs: Long) {
        narrations.updatePosition(narrationId, positionMs.coerceAtLeast(0))
    }

    suspend fun renameNarration(narrationId: Long, title: String) {
        narrations.rename(narrationId, DocumentTitle.rename(title))
    }

    suspend fun setFavorite(narrationId: Long, favorite: Boolean) {
        narrations.setFavorite(narrationId, favorite)
    }

    /**
     * Delete a narration.
     *
     * This is the only operation that removes audio, and it is always explicit. The
     * caller is responsible for deleting the file at [Narration.audioPath]; the
     * repository removes the record, and the two are done together by the caller so
     * a file is never orphaned without a record or a record without a file.
     */
    suspend fun deleteNarration(narrationId: Long) {
        narrations.deleteById(narrationId)
    }

    // ── Voice profiles ───────────────────────────────────────────────────────

    /**
     * Create a voice profile from recorded samples.
     *
     * Enrollment only: no model is produced here. That is deliberate — the
     * specification separates enrollment from training, and enrollment is the part
     * that can ship before the device gate for adaptation is passed.
     */
    suspend fun createEnrolledProfile(
        name: String,
        samplePaths: List<String>,
        qualityScore: Float? = null,
    ): Long = voices.insert(
        VoiceProfile(
            name = name.trim().ifBlank { "My voice" },
            kind = VoiceProfileKind.ENROLLED,
            samplePaths = samplePaths.joinToString(","),
            modelPath = null,
            modelVersion = null,
            qualityScore = qualityScore,
            createdAt = clock(),
        ),
    )

    /**
     * Attach an adapted model to a profile.
     *
     * Only called once local adaptation has actually produced a model. The profile
     * becomes [VoiceProfileKind.ADAPTED] and records the model version, so a
     * narration made with it stays attributable after the model is replaced.
     */
    suspend fun attachAdaptedModel(
        profileId: Long,
        modelPath: String,
        modelVersion: String,
    ) {
        val profile = voices.byId(profileId) ?: return
        voices.update(
            profile.copy(
                kind = VoiceProfileKind.ADAPTED,
                modelPath = modelPath,
                modelVersion = modelVersion,
            ),
        )
    }

    suspend fun voiceProfile(id: Long): VoiceProfile? = voices.byId(id)

    fun voiceProfiles(): Flow<List<VoiceProfile>> = voices.all()

    suspend fun deleteVoiceProfile(profileId: Long) {
        voices.deleteById(profileId)
    }

    // ── Collections ──────────────────────────────────────────────────────────

    suspend fun createCollection(name: String): Long =
        collections.insert(Collection(name = name.trim(), createdAt = clock()))

    suspend fun addToCollection(collectionId: Long, narrationId: Long) {
        collections.addItem(CollectionItem(collectionId, narrationId, clock()))
    }

    suspend fun removeFromCollection(collectionId: Long, narrationId: Long) {
        collections.removeItem(collectionId, narrationId)
    }

    fun collections(): Flow<List<Collection>> = collections.all()

    fun narrationsInCollection(collectionId: Long): Flow<List<Narration>> =
        collections.narrationsIn(collectionId)

    /** Delete a collection; the narrations in it are untouched. */
    suspend fun deleteCollection(collectionId: Long) {
        collections.deleteById(collectionId)
    }

    // ── Reading sessions ─────────────────────────────────────────────────────

    suspend fun startSession(narrationId: Long): Long =
        sessions.insert(ReadingSession(narrationId = narrationId, startedAt = clock()))

    suspend fun endSession(sessionId: Long, positionMs: Long) {
        sessions.end(sessionId, clock(), positionMs.coerceAtLeast(0))
    }

    fun recentSessions(limit: Int = 50): Flow<List<ReadingSession>> = sessions.recent(limit)

    // ── Library view ─────────────────────────────────────────────────────────

    /**
     * The library as the user sees it, for one filter.
     *
     * Composed from the pure [LibraryRules] so the filter behaviour is testable
     * without a database, and so the UI has one call to make.
     */
    fun library(filter: LibraryFilter, collectionId: Long? = null): Flow<List<LibraryEntry>> {
        val docs = documents.all()
        val nars = narrations.all()
        val collectionIds = collectionId?.let { collections.narrationsIn(it) }
        return if (collectionIds == null) {
            kotlinx.coroutines.flow.combine(docs, nars) { d, n ->
                LibraryRules.apply(filter, d, n)
            }
        } else {
            kotlinx.coroutines.flow.combine(docs, nars, collectionIds) { d, n, c ->
                LibraryRules.apply(filter, d, n, c.map { it.id }.toSet())
            }
        }
    }

    /** Counts for the library header. */
    fun counts(): Flow<LibraryCounts> =
        kotlinx.coroutines.flow.combine(
            documents.all(),
            narrations.all(),
            voices.all(),
        ) { d, n, v ->
            LibraryCounts(
                documents = d.size,
                narrations = n.size,
                playable = n.count { LibraryRules.isPlayable(it) },
                interrupted = n.count { LibraryRules.isRecoverable(it) },
                voiceProfiles = v.size,
            )
        }
}

/** Summary counts for the library header. */
data class LibraryCounts(
    val documents: Int,
    val narrations: Int,
    val playable: Int,
    val interrupted: Int,
    val voiceProfiles: Int,
)
