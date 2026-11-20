package com.arkadia.sonata.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/** Converts the enums to and from their stored string form. */
class Converters {
    @TypeConverter fun documentSourceToString(v: DocumentSource): String = v.name
    @TypeConverter fun stringToDocumentSource(v: String): DocumentSource =
        runCatching { DocumentSource.valueOf(v) }.getOrDefault(DocumentSource.TYPED)

    @TypeConverter fun narrationStatusToString(v: NarrationStatus): String = v.name
    @TypeConverter fun stringToNarrationStatus(v: String): NarrationStatus =
        runCatching { NarrationStatus.valueOf(v) }.getOrDefault(NarrationStatus.PENDING)

    @TypeConverter fun voiceProfileKindToString(v: VoiceProfileKind): String = v.name
    @TypeConverter fun stringToVoiceProfileKind(v: String): VoiceProfileKind =
        runCatching { VoiceProfileKind.valueOf(v) }.getOrDefault(VoiceProfileKind.ENROLLED)
}

/**
 * The local database.
 *
 * Local-first by construction: there is no remote source, no sync, and no network
 * path anywhere in this file. The database is the source of truth for everything
 * the user creates, which is what makes the specification's "everything created in
 * Sonata belongs to the user" true rather than aspirational.
 *
 * `exportSchema` is on so the schema is versioned in the repository. A voice model
 * and a narration must remain attributable across app updates, and that requires
 * the schema history to be a tracked artifact rather than an implicit one.
 */
@Database(
    entities = [
        Document::class,
        Narration::class,
        VoiceProfile::class,
        Collection::class,
        CollectionItem::class,
        ReadingSession::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SonataDatabase : RoomDatabase() {

    abstract fun documentDao(): DocumentDao
    abstract fun narrationDao(): NarrationDao
    abstract fun voiceProfileDao(): VoiceProfileDao
    abstract fun collectionDao(): CollectionDao
    abstract fun readingSessionDao(): ReadingSessionDao
    abstract fun libraryDao(): LibraryDao

    companion object {
        const val NAME = "sonata.db"

        @Volatile private var instance: SonataDatabase? = null

        /** The process-wide database. */
        fun get(context: Context): SonataDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): SonataDatabase =
            Room.databaseBuilder(context, SonataDatabase::class.java, NAME)
                // No destructive fallback: a migration that drops the user's library
                // would be the exact silent loss the specification forbids. A missing
                // migration must fail loudly in development instead.
                .build()
    }
}
