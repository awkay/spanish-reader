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
    fun v1ToV2KeepsDataAndAddsPhraseTables() {
        helper.createDatabase("m.db", 1).apply {
            execSQL("INSERT INTO lessons (title, text, created_at, current_page) VALUES ('T', 'Hola.', 1, 0)")
            execSQL("INSERT INTO vocab (form, lemma, status, translation, context_sentence, first_seen, last_seen, times_seen) VALUES ('hola', NULL, 1, NULL, NULL, 1, 1, 1)")
            execSQL("INSERT INTO gloss_cache (form_key, sentence_hash, json, stored_at) VALUES ('hola', 'h', '{}', 1)")
            close()
        }
        val db = helper.runMigrationsAndValidate("m.db", 2, true)
        db.query("SELECT COUNT(*) FROM lessons").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT status FROM vocab WHERE form = 'hola'").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM gloss_cache").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.execSQL("INSERT INTO phrases (sentence_hash, phrase, meaning, stored_at) VALUES ('h', 'sin embargo', 'however', 1)")
        db.execSQL("INSERT INTO phrase_scans (sentence_hash, scanned_at) VALUES ('h', 1)")
        db.close()
    }
}
