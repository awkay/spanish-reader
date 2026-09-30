package net.awkay.spanishreader.gloss

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import net.awkay.spanishreader.core.gloss.Gloss
import net.awkay.spanishreader.core.gloss.GlossProvider
import net.awkay.spanishreader.core.gloss.GlossRequest
import net.awkay.spanishreader.core.gloss.GlossResult
import net.awkay.spanishreader.core.gloss.Glosser
import net.awkay.spanishreader.core.gloss.GlosserConfig
import net.awkay.spanishreader.core.gloss.InMemoryGlossCache
import net.awkay.spanishreader.data.SettingsRepository
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GlossServiceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun settings() = SettingsRepository(PreferenceDataStoreFactory.create { tmp.newFile("s.preferences_pb").also { it.delete() } })

    private class Fake(val fail: Boolean) : Glosser {
        var calls = 0
        override suspend fun gloss(requests: List<GlossRequest>) = requests.map {
            calls++
            if (fail) GlossResult.Failure(it.id, "offline") else GlossResult.Success(it.id, Gloss(it.form, it.form, "noun", "live"))
        }
    }

    private val configured = GlosserConfig(GlossProvider.ZAI, apiKey = "k", model = "glm")

    @Test
    fun unconfiguredGlosserReportsWhatIsMissing() = runTest {
        val service = GlossService(settings(), InMemoryGlossCache(), factory = { _, _ -> Fake(false) })
        val r = assertIs<LookupResult.Failed>(service.lookup("casa", "Mi casa."))
        assertTrue("API key is required" in r.message, r.message)
    }

    @Test
    fun liveLookupIsCachedAndFallsBackToOtherSentence() = runTest {
        val s = settings()
        s.update { it.copy(provider = GlossProvider.ZAI) }
        s.updateProvider(configured)
        val cache = InMemoryGlossCache()
        val live = Fake(false)
        val service = GlossService(s, cache, factory = { _, _ -> live })
        assertEquals("live", assertIs<LookupResult.Found>(service.lookup("banco", "Fui al banco.")).gloss.meaningInContext)
        service.lookup("banco", "Fui al banco.")
        assertEquals(1, live.calls)

        val offline = GlossService(s, cache, factory = { _, _ -> Fake(true) })
        val r = assertIs<LookupResult.Found>(offline.lookup("banco", "Otro banco."))
        assertTrue(r.fromOtherSentence)
        assertIs<LookupResult.Failed>(offline.lookup("perro", "El perro."))
    }

    @Test
    fun improveUsesTheImproveModelSendsThePreviousAnswerAndReplacesTheCache() = runTest {
        val s = settings()
        s.update { it.copy(provider = GlossProvider.ZAI, improveModel = "glm-5.3") }
        s.updateProvider(configured)
        val cache = InMemoryGlossCache()
        val seen = mutableListOf<Pair<GlosserConfig, GlossRequest>>()
        val service = GlossService(s, cache, factory = { p, _ ->
            object : Glosser {
                override suspend fun gloss(requests: List<GlossRequest>) = requests.map {
                    seen += p to it
                    GlossResult.Success(it.id, Gloss(it.form, it.form, "noun", if (it.previous != null) "better" else "first"))
                }
            }
        })
        service.lookup("banco", "Me senté en el banco.")
        val previous = cache.get("banco", "Me senté en el banco.")!!
        val r = assertIs<LookupResult.Found>(service.improve("banco", "Me senté en el banco.", previous))
        assertEquals("better", r.gloss.meaningInContext)
        assertEquals("glm-5.3", seen.last().first.model)
        assertEquals("first", seen.last().second.previous?.meaningInContext)
        assertEquals("better", cache.get("banco", "Me senté en el banco.")?.meaningInContext)
    }

    @Test
    fun improveFallsBackToClaudeWhenConfigured() = runTest {
        val s = settings()
        s.update { it.copy(provider = GlossProvider.ZAI) }
        s.updateProvider(configured)
        assertEquals(configured, s.current().improveGlosser)
        s.updateProvider(GlosserConfig(GlossProvider.ANTHROPIC, apiKey = "a"))
        assertEquals(GlossProvider.ANTHROPIC, s.current().improveGlosser.provider)
    }

    @Test
    fun translateUsesTheStoredTranslationOrFetchesAndStoresIt() = runTest {
        val s = settings()
        s.update { it.copy(provider = GlossProvider.ZAI) }
        s.updateProvider(configured)
        val db = androidx.room.Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), net.awkay.spanishreader.data.AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val store = net.awkay.spanishreader.data.PhraseStore(db.phrases())
        var calls = 0
        val analyzer = object : net.awkay.spanishreader.core.gloss.SentenceAnalyzer {
            override suspend fun analyze(sentences: List<String>) = sentences.map {
                calls++
                net.awkay.spanishreader.core.gloss.SentenceAnalysis("However, he arrived.", listOf(net.awkay.spanishreader.core.gloss.FoundPhrase("sin embargo", "however")))
            }
        }
        val service = GlossService(s, InMemoryGlossCache(), factory = { _, _ -> Fake(false) }, sentenceFactory = { analyzer }, sentences = store)
        assertEquals("However, he arrived.", service.translate("Sin embargo, llegó.").getOrThrow())
        assertEquals("However, he arrived.", service.translate("Sin embargo, llegó.").getOrThrow())
        assertEquals(1, calls)
        assertEquals("sin embargo", store.forSentence("Sin embargo, llegó.").single().phrase)
        db.close()
    }

    @Test
    fun settingsPersistPerProvider() = runTest {
        val s = settings()
        s.updateProvider(configured)
        s.updateProvider(GlosserConfig(GlossProvider.ANTHROPIC, apiKey = "a"))
        s.update { it.copy(provider = GlossProvider.ZAI, fallbackToAnthropic = true) }
        val cur = s.current()
        assertEquals(configured, cur.primaryGlosser)
        assertEquals("a", cur.fallbackGlosser?.apiKey)
        s.update { it.copy(provider = GlossProvider.ANTHROPIC) }
        assertEquals(null, s.current().fallbackGlosser)
    }
}
