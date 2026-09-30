package net.awkay.spanishreader

import android.app.Application
import net.awkay.spanishreader.data.AppDatabase
import net.awkay.spanishreader.data.RoomGlossCache
import net.awkay.spanishreader.data.VocabRepository

class SpanishReaderApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.create(this) }
    val vocabRepository: VocabRepository by lazy { VocabRepository(database) }
    val glossCache: RoomGlossCache by lazy { RoomGlossCache(database.glosses()) }
}
