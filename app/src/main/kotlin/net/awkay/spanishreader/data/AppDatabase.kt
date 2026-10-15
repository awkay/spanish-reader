package net.awkay.spanishreader.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Schema history (exported to app/schemas/): v1 lessons, vocab, gloss_cache; v2 adds phrases + phrase_scans;
 * v3 adds sentence_translations; v4 adds lessons.video_id + source_url (YouTube lessons); v5 adds drill_cards +
 * drill_progress (lightning verb drill).
 * Every change needs a migration now that the app is installed with real data.
 */
@Database(
    entities = [
        LessonEntity::class, VocabEntity::class, GlossEntity::class, PhraseEntity::class, PhraseScanEntity::class,
        SentenceTranslationEntity::class, DrillCardsEntity::class, DrillProgressEntity::class,
    ],
    version = 5,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
    ],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lessons(): LessonDao
    abstract fun vocab(): VocabDao
    abstract fun glosses(): GlossDao
    abstract fun phrases(): PhraseDao
    abstract fun drill(): DrillDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "spanish-reader.db").build()
    }
}
