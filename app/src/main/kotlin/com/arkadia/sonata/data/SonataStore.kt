package com.arkadia.sonata.data

/**
 * The persistence operations the rest of the app depends on.
 *
 * [SonataRepository] is the implementation; this interface exists so the layers
 * above it — the engine, the Voice Studio, the file coordinator — can be tested
 * against a fake without a Room database, an Android framework or a device.
 *
 * That is not a testing convenience bolted on afterwards: the specification's
 * invariants are the *safety properties* of the app, and a safety property that can
 * only be exercised through a real database is a safety property that will not be
 * exercised. Depending on an interface is what makes the engine's
 * "save before synthesise" ordering and the coordinator's "record and file together"
 * rule testable in isolation.
 */
interface SonataStore {

    // ── Documents ────────────────────────────────────────────────────────────

    suspend fun createDocument(
        text: String,
        source: DocumentSource = DocumentSource.TYPED,
        title: String? = null,
    ): Long

    suspend fun setCleanedText(documentId: Long, cleaned: String)

    suspend fun renameDocument(documentId: Long, title: String)

    suspend fun document(id: Long): Document?

    /** Delete a document, keeping every narration made from it. Returns how many were kept. */
    suspend fun deleteDocument(documentId: Long): Int

    // ── Narrations ───────────────────────────────────────────────────────────

    suspend fun beginNarration(
        documentId: Long?,
        title: String,
        voiceProfileId: Long? = null,
        modelVersion: String? = null,
    ): Long

    suspend fun markGenerating(narrationId: Long)

    suspend fun completeNarration(narrationId: Long, audioPath: String, durationMs: Long): Boolean

    suspend fun failNarration(narrationId: Long, reason: String)

    suspend fun interruptNarration(narrationId: Long)

    suspend fun recoverInterrupted(): Int

    suspend fun narration(id: Long): Narration?

    suspend fun savePosition(narrationId: Long, positionMs: Long)

    suspend fun renameNarration(narrationId: Long, title: String)

    suspend fun setFavorite(narrationId: Long, favorite: Boolean)

    suspend fun deleteNarration(narrationId: Long)

    // ── Voice profiles ───────────────────────────────────────────────────────

    suspend fun createEnrolledProfile(
        name: String,
        samplePaths: List<String>,
        qualityScore: Float? = null,
    ): Long

    suspend fun attachAdaptedModel(profileId: Long, modelPath: String, modelVersion: String)

    suspend fun voiceProfile(id: Long): VoiceProfile?

    suspend fun deleteVoiceProfile(profileId: Long)

    // ── Collections ──────────────────────────────────────────────────────────

    suspend fun createCollection(name: String): Long

    suspend fun addToCollection(collectionId: Long, narrationId: Long)

    suspend fun removeFromCollection(collectionId: Long, narrationId: Long)

    suspend fun deleteCollection(collectionId: Long)
}
