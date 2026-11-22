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
import net.awkay.spanishreader.data.AiProfile
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
        s.useConfig(configured)
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
        s.useConfig(configured)
        s.saveProfile(AiProfile("better", provider = GlossProvider.ZAI.name, apiKey = "k", model = "glm-5.3"))
        s.update { it.copy(improveProfileId = "better") }
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
    fun improveUsesTheActiveProfileUnlessAnotherIsChosen() = runTest {
        val s = settings()
        s.useConfig(configured)
        assertEquals(configured, s.current().improveGlosser)
        s.saveProfile(AiProfile("claude", provider = GlossProvider.ANTHROPIC.name, apiKey = "a"))
        assertEquals(configured, s.current().improveGlosser) // saving another profile doesn't change what's used
        s.update { it.copy(improveProfileId = "claude") }
        assertEquals(GlossProvider.ANTHROPIC, s.current().improveGlosser.provider)
    }

    @Test
    fun translateUsesTheStoredTranslationOrFetchesAndStoresIt() = runTest {
        val s = settings()
        s.useConfig(configured)
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
    fun profilesAreKeptSwitchedAndDeleted() = runTest {
        val s = settings()
        val router = AiProfile("r", provider = GlossProvider.OPENROUTER.name, apiKey = "or", model = "deepseek/deepseek-v4.1-flash")
        val glm = AiProfile("g", provider = GlossProvider.ZAI_RESPONSES.name, apiKey = "z", model = "glm-5.3")
        s.saveProfile(router)
        s.saveProfile(glm)
        s.saveProfile(router.copy(id = "r2", model = "google/gemini-3.8-flash")) // same key, another model
        assertEquals("r", s.current().activeProfileId) // the first one saved is used until another is chosen
        assertEquals("https://openrouter.ai/api/v1", s.current().primaryGlosser.effectiveBaseUrl)
        s.activate("g")
        assertEquals(GlosserConfig(GlossProvider.ZAI_RESPONSES, apiKey = "z", model = "glm-5.3"), s.current().primaryGlosser)
        s.update { it.copy(fallbackProfileId = "r2", improveProfileId = "r2") }
        assertEquals("google/gemini-3.8-flash", s.current().fallbackGlosser?.model)
        assertEquals("or", s.current().fallbackGlosser?.apiKey)
        s.activate("r2")
        assertEquals(null, s.current().fallbackGlosser) // never falls back to itself
        s.deleteProfile("r2")
        val cur = s.current()
        assertEquals(listOf("r", "g"), cur.aiProfiles.map { it.id })
        assertEquals("r", cur.activeProfileId)
        assertEquals("" to "", cur.improveProfileId to cur.fallbackProfileId)
        assertEquals("openrouter.ai · deepseek/deepseek-v4.1-flash", AiProfile("x", provider = GlossProvider.OPENAI_COMPATIBLE.name,
            baseUrl = "https://openrouter.ai/api/v1", model = "deepseek/deepseek-v4.1-flash").label)
        assertEquals("OpenRouter · deepseek/deepseek-v4.1-flash", router.label)
    }
}
