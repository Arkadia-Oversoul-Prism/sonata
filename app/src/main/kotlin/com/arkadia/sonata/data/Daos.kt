package com.arkadia.sonata.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data access for the local library.
 *
 * Every query is written so the specification's invariants hold at the SQL level,
 * not only in the repository. In particular, no delete here cascades from a
 * document to a narration: the schema's `ON DELETE SET NULL` is the only effect a
 * document deletion has on narrations, and that is deliberate.
 */

@Dao
interface DocumentDao {

    @Insert
    suspend fun insert(document: Document): Long

    @Update
    suspend fun update(document: Document)

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun byId(id: Long): Document?

    @Query("SELECT * FROM documents ORDER BY updatedAt DESC")
    fun all(): Flow<List<Document>>

    @Query("SELECT * FROM documents ORDER BY updatedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<Document>>

    @Query("SELECT * FROM documents WHERE sourceType = :source ORDER BY updatedAt DESC")
    fun bySource(source: DocumentSource): Flow<List<Document>>

    @Query("SELECT COUNT(*) FROM documents")
    suspend fun count(): Int

    /**
     * Delete a document.
     *
     * The narrations made from it are **not** deleted: the foreign key is
     * `ON DELETE SET NULL`, so they survive with `documentId` cleared. This is the
     * deletion-independence invariant, and it is enforced by SQLite rather than by
     * this method remembering to do it.
     */
    @Delete
    suspend fun delete(document: Document)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface NarrationDao {

    @Insert
    suspend fun insert(narration: Narration): Long

    @Update
    suspend fun update(narration: Narration)

    @Query("SELECT * FROM narrations WHERE id = :id")
    suspend fun byId(id: Long): Narration?

    @Query("SELECT * FROM narrations ORDER BY createdAt DESC")
    fun all(): Flow<List<Narration>>

    @Query("SELECT * FROM narrations ORDER BY createdAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<Narration>>

    @Query("SELECT * FROM narrations WHERE favorite = 1 ORDER BY createdAt DESC")
    fun favorites(): Flow<List<Narration>>

    @Query("SELECT * FROM narrations WHERE documentId = :documentId ORDER BY createdAt DESC")
    fun byDocument(documentId: Long): Flow<List<Narration>>

    @Query("SELECT * FROM narrations WHERE status = :status ORDER BY createdAt DESC")
    fun byStatus(status: NarrationStatus): Flow<List<Narration>>

    @Query("SELECT COUNT(*) FROM narrations")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM narrations WHERE documentId IS NULL")
    suspend fun countWithoutDocument(): Int

    /** Update only the playback position, so a resume never rewrites the whole row. */
    @Query("UPDATE narrations SET playbackPositionMs = :positionMs WHERE id = :id")
    suspend fun updatePosition(id: Long, positionMs: Long)

    @Query("UPDATE narrations SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE narrations SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE narrations SET status = :status, errorReason = :reason WHERE id = :id")
    suspend fun setStatus(id: Long, status: NarrationStatus, reason: String?)

    /**
     * Mark every narration still generating as interrupted.
     *
     * Called once at startup. A row in [NarrationStatus.GENERATING] can only mean
     * the process died mid-synthesis, because a live process would have moved it on.
     * Marking them here is what makes "recover or clearly mark incomplete work" a
     * fact rather than an intention.
     */
    @Query(
        "UPDATE narrations SET status = 'INTERRUPTED' " +
            "WHERE status = 'GENERATING' OR status = 'PENDING'",
    )
    suspend fun markInterrupted(): Int

    @Delete
    suspend fun delete(narration: Narration)

    @Query("DELETE FROM narrations WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface VoiceProfileDao {

    @Insert
    suspend fun insert(profile: VoiceProfile): Long

    @Update
    suspend fun update(profile: VoiceProfile)

    @Query("SELECT * FROM voice_profiles WHERE id = :id")
    suspend fun byId(id: Long): VoiceProfile?

    @Query("SELECT * FROM voice_profiles ORDER BY createdAt DESC")
    fun all(): Flow<List<VoiceProfile>>

    @Query("SELECT COUNT(*) FROM voice_profiles")
    suspend fun count(): Int

    @Delete
    suspend fun delete(profile: VoiceProfile)

    @Query("DELETE FROM voice_profiles WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface CollectionDao {

    @Insert
    suspend fun insert(collection: Collection): Long

    @Query("SELECT * FROM collections ORDER BY createdAt DESC")
    fun all(): Flow<List<Collection>>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun byId(id: Long): Collection?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addItem(item: CollectionItem)

    @Query("DELETE FROM collection_items WHERE collectionId = :collectionId AND narrationId = :narrationId")
    suspend fun removeItem(collectionId: Long, narrationId: Long)

    @Query(
        "SELECT n.* FROM narrations n " +
            "INNER JOIN collection_items ci ON ci.narrationId = n.id " +
            "WHERE ci.collectionId = :collectionId ORDER BY ci.addedAt DESC",
    )
    fun narrationsIn(collectionId: Long): Flow<List<Narration>>

    @Query("SELECT COUNT(*) FROM collection_items WHERE collectionId = :collectionId")
    suspend fun itemCount(collectionId: Long): Int

    /**
     * Delete a collection.
     *
     * Only the membership rows cascade; the narrations themselves are untouched.
     * Deleting a collection is a statement about the collection, not about the
     * user's audio.
     */
    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ReadingSessionDao {

    @Insert
    suspend fun insert(session: ReadingSession): Long

    @Query("UPDATE reading_sessions SET endedAt = :endedAt, lastPositionMs = :positionMs WHERE id = :id")
    suspend fun end(id: Long, endedAt: Long, positionMs: Long)

    @Query("SELECT * FROM reading_sessions WHERE narrationId = :narrationId ORDER BY startedAt DESC")
    fun forNarration(narrationId: Long): Flow<List<ReadingSession>>

    @Query("SELECT * FROM reading_sessions ORDER BY startedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<ReadingSession>>

    @Query("SELECT COUNT(*) FROM reading_sessions")
    suspend fun count(): Int
}

/**
 * Cross-table operations that must be atomic.
 *
 * Kept in one DAO so a transaction can span the tables it needs to; Room requires
 * the `@Transaction` method to live on a DAO that can see every table it touches.
 */
@Dao
interface LibraryDao {

    /**
     * Delete a document and report how many narrations survived it.
     *
     * The count is returned rather than assumed, because the whole point of the
     * invariant is that the user can be *told* their narrations are safe. A silent
     * survival is indistinguishable from a silent loss.
     */
    @Transaction
    suspend fun deleteDocumentKeepingNarrations(
        documentDao: DocumentDao,
        narrationDao: NarrationDao,
        documentId: Long,
    ): Int {
        val before = narrationDao.count()
        documentDao.deleteById(documentId)
        val after = narrationDao.count()
        return before - after
    }
}
