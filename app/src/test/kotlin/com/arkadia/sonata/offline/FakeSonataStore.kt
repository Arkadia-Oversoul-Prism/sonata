package com.arkadia.sonata.offline

import com.arkadia.sonata.data.Collection
import com.arkadia.sonata.data.Document
import com.arkadia.sonata.data.DocumentSource
import com.arkadia.sonata.data.DocumentTitle
import com.arkadia.sonata.data.LibraryRules
import com.arkadia.sonata.data.Narration
import com.arkadia.sonata.data.NarrationStatus
import com.arkadia.sonata.data.SonataStore
import com.arkadia.sonata.data.VoiceProfile
import com.arkadia.sonata.data.VoiceProfileKind

/**
 * An in-memory [SonataStore] for JVM tests.
 *
 * It reproduces the two invariants the real schema enforces, because a fake that
 * does not is a fake that lets a test pass on behaviour the app does not have:
 *
 *  - **Deletion independence** — deleting a document nulls the `documentId` of its
 *    narrations rather than removing them, exactly as `ON DELETE SET NULL` does.
 *  - **The transition table** — a status change is refused unless
 *    [LibraryRules.canTransition] allows it, so an interrupted narration cannot be
 *    completed by a test that forgets the rule.
 */
class FakeSonataStore(private val clock: () -> Long = { 1_000L }) : SonataStore {

    private val documents = LinkedHashMap<Long, Document>()
    private val narrations = LinkedHashMap<Long, Narration>()
    private val profiles = LinkedHashMap<Long, VoiceProfile>()
    private val collections = LinkedHashMap<Long, Collection>()
    private val collectionItems = LinkedHashMap<Long, MutableSet<Long>>()
    private var nextId = 1L

    /** Every status a narration passed through, in order. Used to pin ordering. */
    val statusHistory = mutableListOf<Pair<Long, NarrationStatus>>()

    /** Whether a narration row existed before synthesis was attempted. */
    var rowExistedBeforeSynthesis = false

    fun documents(): List<Document> = documents.values.toList()
    fun narrations(): List<Narration> = narrations.values.toList()
    fun profiles(): List<VoiceProfile> = profiles.values.toList()

    override suspend fun createDocument(
        text: String,
        source: DocumentSource,
        title: String?,
    ): Long {
        val id = nextId++
        val now = clock()
        documents[id] = Document(
            id = id,
            title = title?.let { DocumentTitle.rename(it) } ?: DocumentTitle.fromText(text),
            originalText = text,
            cleanedText = null,
            sourceType = source,
            importedAt = now,
            updatedAt = now,
        )
        return id
    }

    override suspend fun setCleanedText(documentId: Long, cleaned: String) {
        val doc = documents[documentId] ?: return
        documents[documentId] = doc.copy(cleanedText = cleaned, updatedAt = clock())
    }

    override suspend fun renameDocument(documentId: Long, title: String) {
        val doc = documents[documentId] ?: return
        documents[documentId] = doc.copy(title = DocumentTitle.rename(title), updatedAt = clock())
    }

    override suspend fun document(id: Long): Document? = documents[id]

    override suspend fun deleteDocument(documentId: Long): Int {
        documents.remove(documentId)
        // ON DELETE SET NULL: the narrations survive with their link cleared.
        narrations.replaceAll { _, n ->
            if (n.documentId == documentId) n.copy(documentId = null) else n
        }
        return narrations.size
    }

    override suspend fun beginNarration(
        documentId: Long?,
        title: String,
        voiceProfileId: Long?,
        modelVersion: String?,
    ): Long {
        val id = nextId++
        narrations[id] = Narration(
            id = id,
            documentId = documentId,
            title = DocumentTitle.rename(title),
            voiceProfileId = voiceProfileId,
            modelVersion = modelVersion,
            createdAt = clock(),
            status = NarrationStatus.PENDING,
        )
        statusHistory += id to NarrationStatus.PENDING
        return id
    }

    override suspend fun markGenerating(narrationId: Long) {
        transition(narrationId, NarrationStatus.GENERATING, null)
    }

    override suspend fun completeNarration(
        narrationId: Long,
        audioPath: String,
        durationMs: Long,
    ): Boolean {
        val current = narrations[narrationId] ?: return false
        if (!LibraryRules.canTransition(current.status, NarrationStatus.COMPLETE)) return false
        narrations[narrationId] = current.copy(
            audioPath = audioPath,
            durationMs = durationMs,
            status = NarrationStatus.COMPLETE,
            errorReason = null,
        )
        statusHistory += narrationId to NarrationStatus.COMPLETE
        return true
    }

    override suspend fun failNarration(narrationId: Long, reason: String) {
        transition(narrationId, NarrationStatus.FAILED, reason)
    }

    override suspend fun interruptNarration(narrationId: Long) {
        transition(narrationId, NarrationStatus.INTERRUPTED, null)
    }

    override suspend fun recoverInterrupted(): Int {
        var count = 0
        narrations.replaceAll { id, n ->
            if (n.status == NarrationStatus.GENERATING || n.status == NarrationStatus.PENDING) {
                count++
                statusHistory += id to NarrationStatus.INTERRUPTED
                n.copy(status = NarrationStatus.INTERRUPTED)
            } else {
                n
            }
        }
        return count
    }

    private fun transition(narrationId: Long, to: NarrationStatus, reason: String?) {
        val current = narrations[narrationId] ?: return
        if (!LibraryRules.canTransition(current.status, to)) return
        narrations[narrationId] = current.copy(status = to, errorReason = reason)
        statusHistory += narrationId to to
    }

    override suspend fun narration(id: Long): Narration? = narrations[id]

    override suspend fun savePosition(narrationId: Long, positionMs: Long) {
        val n = narrations[narrationId] ?: return
        narrations[narrationId] = n.copy(playbackPositionMs = positionMs.coerceAtLeast(0))
    }

    override suspend fun renameNarration(narrationId: Long, title: String) {
        val n = narrations[narrationId] ?: return
        narrations[narrationId] = n.copy(title = DocumentTitle.rename(title))
    }

    override suspend fun setFavorite(narrationId: Long, favorite: Boolean) {
        val n = narrations[narrationId] ?: return
        narrations[narrationId] = n.copy(favorite = favorite)
    }

    override suspend fun deleteNarration(narrationId: Long) {
        narrations.remove(narrationId)
    }

    override suspend fun createEnrolledProfile(
        name: String,
        samplePaths: List<String>,
        qualityScore: Float?,
    ): Long {
        val id = nextId++
        profiles[id] = VoiceProfile(
            id = id,
            name = name.trim().ifBlank { "My voice" },
            kind = VoiceProfileKind.ENROLLED,
            samplePaths = samplePaths.joinToString(","),
            modelPath = null,
            modelVersion = null,
            qualityScore = qualityScore,
            createdAt = clock(),
        )
        return id
    }

    override suspend fun attachAdaptedModel(
        profileId: Long,
        modelPath: String,
        modelVersion: String,
    ) {
        val p = profiles[profileId] ?: return
        profiles[profileId] = p.copy(
            kind = VoiceProfileKind.ADAPTED,
            modelPath = modelPath,
            modelVersion = modelVersion,
        )
    }

    override suspend fun voiceProfile(id: Long): VoiceProfile? = profiles[id]

    override suspend fun deleteVoiceProfile(profileId: Long) {
        profiles.remove(profileId)
        // ON DELETE SET NULL: narrations keep their audio.
        narrations.replaceAll { _, n ->
            if (n.voiceProfileId == profileId) n.copy(voiceProfileId = null) else n
        }
    }

    override suspend fun createCollection(name: String): Long {
        val id = nextId++
        collections[id] = Collection(id = id, name = name.trim(), createdAt = clock())
        collectionItems[id] = mutableSetOf()
        return id
    }

    override suspend fun addToCollection(collectionId: Long, narrationId: Long) {
        collectionItems.getOrPut(collectionId) { mutableSetOf() }.add(narrationId)
    }

    override suspend fun removeFromCollection(collectionId: Long, narrationId: Long) {
        collectionItems[collectionId]?.remove(narrationId)
    }

    override suspend fun deleteCollection(collectionId: Long) {
        collections.remove(collectionId)
        collectionItems.remove(collectionId)
    }
}
