package net.awkay.spanishreader.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.test.runTest
import net.awkay.spanishreader.core.gloss.GlossProvider
import net.awkay.spanishreader.core.gloss.GlosserConfig
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings saved by earlier versions (one config per provider) become AI profiles on upgrade, nothing lost. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsMigrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = PreferenceDataStoreFactory.create { tmp.newFile("s.preferences_pb").also { it.delete() } }

    private fun key(p: GlossProvider, field: String) = stringPreferencesKey("gloss.${p.name}.$field")

    @Test
    fun oldSettingsBecomeProfilesAndSurviveTheFirstSave() = runTest {
        val store = store()
        store.edit {
            it[stringPreferencesKey("gloss.provider")] = GlossProvider.ZAI_RESPONSES.name
            it[key(GlossProvider.ZAI_RESPONSES, "baseUrl")] = "https://api.z.ai/api/v1"
            it[key(GlossProvider.ZAI_RESPONSES, "apiKey")] = "zai-key"
            it[key(GlossProvider.ZAI_RESPONSES, "model")] = "glm-5.3"
            it[key(GlossProvider.ANTHROPIC, "baseUrl")] = "https://api.anthropic.com"
            it[key(GlossProvider.ANTHROPIC, "apiKey")] = "claude-key"
            it[key(GlossProvider.ANTHROPIC, "model")] = "claude-haiku-4-5"
            it[key(GlossProvider.OLLAMA_LOCAL, "baseUrl")] = ""
            it[stringPreferencesKey("gloss.improveModel")] = "glm-5.3-prime"
            it[booleanPreferencesKey("gloss.fallbackToAnthropic")] = true
            it[intPreferencesKey("reader.fontSize")] = 24
        }
        val s = SettingsRepository(store)
        val before = s.current()
        // z.ai, Claude, and the improve model as a copy of the z.ai profile; empty providers are left out.
        assertEquals(listOf("legacy-ZAI_RESPONSES", "legacy-ANTHROPIC", "legacy-improve"), before.aiProfiles.map { it.id })
        assertEquals(GlosserConfig(GlossProvider.ZAI_RESPONSES, "https://api.z.ai/api/v1", "zai-key", "glm-5.3"), before.primaryGlosser)
        assertEquals(GlosserConfig(GlossProvider.ZAI_RESPONSES, "https://api.z.ai/api/v1", "zai-key", "glm-5.3-prime"), before.improveGlosser)
        assertEquals("claude-key", before.fallbackGlosser?.apiKey)
        assertEquals(24, before.readerFontSize)
        assertEquals(before, s.current()) // reading again gives the same profiles

        // The first save stores the profiles; from then on they are what counts.
        s.update { it.copy(readerFontSize = 22) }
        val after = s.current()
        assertEquals(before.aiProfiles, after.aiProfiles)
        assertEquals(before.activeProfileId, after.activeProfileId)
        assertEquals(22, after.readerFontSize)
        s.deleteProfile("legacy-ANTHROPIC")
        assertNull(s.current().fallbackGlosser)
        assertEquals(2, s.current().aiProfiles.size)
    }

    @Test
    fun withoutAnImproveModelClaudeStaysTheImproveChoiceWhenItWasSetUp() = runTest {
        val store = store()
        store.edit {
            it[stringPreferencesKey("gloss.provider")] = GlossProvider.ZAI_CODING.name
            it[key(GlossProvider.ZAI_CODING, "apiKey")] = "k"
            it[key(GlossProvider.ZAI_CODING, "model")] = "glm-5.3-flash"
            it[key(GlossProvider.ANTHROPIC, "apiKey")] = "a"
        }
        val s = SettingsRepository(store).current()
        assertEquals(GlossProvider.ANTHROPIC, s.improveGlosser.provider)
        assertNull(s.fallbackGlosser) // the fallback switch was off
    }

    /**
     * Exactly what the previous version stored: a slot for every provider (empty strings), and a provider's default URL
     * (plus Claude's default model) written as soon as it was picked in the dropdown. Slots holding only those defaults
     * were never set up and don't become profiles; one with a URL or model the user typed does.
     */
    @Test
    fun slotsHoldingOnlyDefaultsFromBrowsingTheDropdownAreLeftOut() = runTest {
        val store = store()
        store.edit {
            for (gp in GlossProvider.entries) for (f in listOf("baseUrl", "apiKey", "model")) it[key(gp, f)] = ""
            // Browsed: defaults filled in by the dropdown, nothing typed.
            it[key(GlossProvider.OLLAMA_CLOUD, "baseUrl")] = "https://ollama.com/v1"
            it[key(GlossProvider.ZAI, "baseUrl")] = "https://api.z.ai/api/paas/v4"
            it[key(GlossProvider.ANTHROPIC, "baseUrl")] = "https://api.anthropic.com"
            it[key(GlossProvider.ANTHROPIC, "model")] = "claude-haiku-4-5"
            // Set up: a key typed in.
            it[key(GlossProvider.ZAI_CODING, "baseUrl")] = "https://api.z.ai/api/coding/paas/v4"
            it[key(GlossProvider.ZAI_CODING, "apiKey")] = "k"
            it[key(GlossProvider.ZAI_CODING, "model")] = "glm-5.3-flash"
            // A local server: only a URL typed (no key needed), not a default.
            it[key(GlossProvider.OLLAMA_LOCAL, "baseUrl")] = "http://192.168.1.20:11434/v1"
            // The one in use is kept even with nothing but its default URL.
            it[stringPreferencesKey("gloss.provider")] = GlossProvider.ZAI_RESPONSES.name
            it[key(GlossProvider.ZAI_RESPONSES, "baseUrl")] = "https://api.z.ai/api/v1"
            it[stringPreferencesKey("gloss.improveModel")] = ""
            it[booleanPreferencesKey("gloss.fallbackToAnthropic")] = false
        }
        val s = SettingsRepository(store).current()
        assertEquals(listOf("legacy-OLLAMA_LOCAL", "legacy-ZAI_RESPONSES", "legacy-ZAI_CODING"), s.aiProfiles.map { it.id })
        assertEquals("legacy-ZAI_RESPONSES", s.activeProfileId)
        assertEquals("" to "", s.improveProfileId to s.fallbackProfileId) // Claude was never set up
    }

    @Test
    fun aFreshInstallHasNoProfiles() = runTest {
        val s = SettingsRepository(store()).current()
        assertTrue(s.aiProfiles.isEmpty())
        assertTrue(!s.primaryGlosser.isComplete)
    }
}
