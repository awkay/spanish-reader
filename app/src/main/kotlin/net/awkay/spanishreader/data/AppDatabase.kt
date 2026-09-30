package net.awkay.spanishreader.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Schema history (exported to app/schemas/): v1 lessons, vocab, gloss_cache; v2 adds phrases + phrase_scans.
 * Every change needs a migration now that the app is installed with real data.
 */
@Database(
    entities = [LessonEntity::class, VocabEntity::class, GlossEntity::class, PhraseEntity::class, PhraseScanEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lessons(): LessonDao
    abstract fun vocab(): VocabDao
    abstract fun glosses(): GlossDao
    abstract fun phrases(): PhraseDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "spanish-reader.db").build()
    }
}
