package com.arkadia.sonata.data

/**
 * Adapts [SonataRepository] to [SonataStore].
 *
 * A one-line delegation per operation, rather than making the repository implement
 * the interface directly. The reason is that the repository is the *implementation*
 * of the invariants and is already covered by its own Robolectric tests; this
 * adapter is the seam the layers above depend on, so a fake can stand in for the
 * whole persistence layer in a JVM test. Keeping the two separate means the
 * repository's own tests keep exercising the real database, while the engine's
 * tests exercise the ordering rules without one.
 */
class RepositoryStore(private val repository: SonataRepository) : SonataStore {

    override suspend fun createDocument(
        text: String,
        source: DocumentSource,
        title: String?,
    ): Long = repository.createDocument(text, source, title)

    override suspend fun setCleanedText(documentId: Long, cleaned: String) =
        repository.setCleanedText(documentId, cleaned)

    override suspend fun renameDocument(documentId: Long, title: String) =
        repository.renameDocument(documentId, title)

    override suspend fun document(id: Long): Document? = repository.document(id)

    override suspend fun deleteDocument(documentId: Long): Int =
        repository.deleteDocument(documentId)

    override suspend fun beginNarration(
        documentId: Long?,
        title: String,
        voiceProfileId: Long?,
        modelVersion: String?,
    ): Long = repository.beginNarration(documentId, title, voiceProfileId, modelVersion)

    override suspend fun markGenerating(narrationId: Long) =
        repository.markGenerating(narrationId)

    override suspend fun completeNarration(
        narrationId: Long,
        audioPath: String,
        durationMs: Long,
    ): Boolean = repository.completeNarration(narrationId, audioPath, durationMs)

    override suspend fun failNarration(narrationId: Long, reason: String) =
        repository.failNarration(narrationId, reason)

    override suspend fun interruptNarration(narrationId: Long) =
        repository.interruptNarration(narrationId)

    override suspend fun recoverInterrupted(): Int = repository.recoverInterrupted()

    override suspend fun narration(id: Long): Narration? = repository.narration(id)

    override suspend fun savePosition(narrationId: Long, positionMs: Long) =
        repository.savePosition(narrationId, positionMs)

    override suspend fun renameNarration(narrationId: Long, title: String) =
        repository.renameNarration(narrationId, title)

    override suspend fun setFavorite(narrationId: Long, favorite: Boolean) =
        repository.setFavorite(narrationId, favorite)

    override suspend fun deleteNarration(narrationId: Long) =
        repository.deleteNarration(narrationId)

    override suspend fun createEnrolledProfile(
        name: String,
        samplePaths: List<String>,
        qualityScore: Float?,
    ): Long = repository.createEnrolledProfile(name, samplePaths, qualityScore)

    override suspend fun attachAdaptedModel(
        profileId: Long,
        modelPath: String,
        modelVersion: String,
    ) = repository.attachAdaptedModel(profileId, modelPath, modelVersion)

    override suspend fun voiceProfile(id: Long): VoiceProfile? = repository.voiceProfile(id)

    override suspend fun deleteVoiceProfile(profileId: Long) =
        repository.deleteVoiceProfile(profileId)

    override suspend fun createCollection(name: String): Long = repository.createCollection(name)

    override suspend fun addToCollection(collectionId: Long, narrationId: Long) =
        repository.addToCollection(collectionId, narrationId)

    override suspend fun removeFromCollection(collectionId: Long, narrationId: Long) =
        repository.removeFromCollection(collectionId, narrationId)

    override suspend fun deleteCollection(collectionId: Long) =
        repository.deleteCollection(collectionId)
}
