package net.awkay.spanishreader.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [LessonEntity::class, VocabEntity::class, GlossEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lessons(): LessonDao
    abstract fun vocab(): VocabDao
    abstract fun glosses(): GlossDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "spanish-reader.db").build()
    }
}
