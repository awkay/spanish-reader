package net.awkay.spanishreader.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The app is installed with real data now: every schema bump must keep it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun v1ToCurrentKeepsDataAndAddsNewTables() {
        helper.createDatabase("m.db", 1).apply {
            execSQL("INSERT INTO lessons (title, text, created_at, current_page) VALUES ('T', 'Hola.', 1, 0)")
            execSQL("INSERT INTO vocab (form, lemma, status, translation, context_sentence, first_seen, last_seen, times_seen) VALUES ('hola', NULL, 1, NULL, NULL, 1, 1, 1)")
            execSQL("INSERT INTO gloss_cache (form_key, sentence_hash, json, stored_at) VALUES ('hola', 'h', '{}', 1)")
            close()
        }
        helper.runMigrationsAndValidate("m.db", 2, true).close()
        val db = helper.runMigrationsAndValidate("m.db", 3, true)
        db.query("SELECT COUNT(*) FROM lessons").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT status FROM vocab WHERE form = 'hola'").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM gloss_cache").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.execSQL("INSERT INTO phrases (sentence_hash, phrase, meaning, stored_at) VALUES ('h', 'sin embargo', 'however', 1)")
        db.execSQL("INSERT INTO phrase_scans (sentence_hash, scanned_at) VALUES ('h', 1)")
        db.execSQL("INSERT INTO sentence_translations (sentence_hash, translation, stored_at) VALUES ('h', 'Hello.', 1)")
        db.close()
        val v4 = helper.runMigrationsAndValidate("m.db", 4, true)
        v4.query("SELECT title, video_id, source_url FROM lessons").use {
            it.moveToFirst()
            assertEquals("T", it.getString(0))
            assertEquals(true, it.isNull(1) && it.isNull(2))
        }
        v4.execSQL("UPDATE lessons SET video_id = 'dQw4w9WgXcQ', source_url = 'https://www.youtube.com/watch?v=dQw4w9WgXcQ'")
        v4.close()
    }
}
