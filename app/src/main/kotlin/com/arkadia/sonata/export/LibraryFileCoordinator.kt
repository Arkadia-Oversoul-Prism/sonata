package com.arkadia.sonata.export

import com.arkadia.sonata.data.SonataStore
import java.io.File

/**
 * The one place a narration's record and its file are removed together.
 *
 * Phase 1's repository deliberately left the file/record pairing to the caller and
 * documented that the caller must remove both. That is a contract a future caller
 * can forget, and forgetting it produces exactly the two failures the
 * specification forbids: an orphaned file the user cannot see, or a record whose
 * audio is gone. This class makes the pairing structural by being the only thing
 * that performs it.
 *
 * The three deletion rules, restated because they are the whole point:
 *
 *  - Deleting a **document** never touches any narration file. It delegates to the
 *    repository, whose foreign key is `ON DELETE SET NULL`, and touches no file.
 *  - Deleting a **narration** removes its record **and** its file, in one call.
 *  - Deleting a **collection** removes neither records nor files.
 */
class LibraryFileCoordinator(
    private val repository: SonataStore,
    private val fileStore: FileStore,
) {

    /**
     * Delete a narration and its audio.
     *
     * The file is removed first. If the process dies between the two, the result is
     * a record whose audio is missing — which the library already handles, because
     * [com.arkadia.sonata.data.LibraryRules.isPlayable] requires the audio path to
     * be non-blank and the file to exist. The reverse order would leave an orphaned
     * file with no record, which nothing can find again.
     */
    suspend fun deleteNarration(narrationId: Long) {
        fileStore.delete(narrationId)
        repository.deleteNarration(narrationId)
    }

    /**
     * Delete a document, keeping every narration made from it.
     *
     * Returns how many narrations were kept, so the UI can tell the user their
     * audio is safe rather than leaving them to hope.
     */
    suspend fun deleteDocument(documentId: Long): Int = repository.deleteDocument(documentId)

    /** Delete a collection. Neither records nor files are touched. */
    suspend fun deleteCollection(collectionId: Long) {
        repository.deleteCollection(collectionId)
    }

    /**
     * Delete a voice profile and its recorded samples.
     *
     * The samples are the user's voice and are the most sensitive thing Sonata
     * holds, so removing the profile removes them from disk rather than leaving
     * them behind. Narrations made with the profile keep their audio: the foreign
     * key is `ON DELETE SET NULL`, so the audio survives the profile.
     */
    suspend fun deleteVoiceProfile(profileId: Long, samplePaths: List<String>) {
        samplePaths.forEach { path ->
            val file = File(path)
            if (file.isFile) file.delete()
        }
        repository.deleteVoiceProfile(profileId)
    }
}
