package net.awkay.spanishreader.share

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.awkay.spanishreader.core.gloss.FoundPhrase
import net.awkay.spanishreader.core.gloss.Gloss
import net.awkay.spanishreader.core.gloss.GlossCache
import net.awkay.spanishreader.core.text.Tokenizer
import net.awkay.spanishreader.data.LessonEntity
import net.awkay.spanishreader.data.PhraseStore
import net.awkay.spanishreader.data.SettingsRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * "Share to web": uploads a lesson to the household web app's shared library (portal.fulcrologic.com),
 * together with the AI work already done for its sentences (glosses, translations, idioms), so the other reader
 * gets it without paying for it again. Never sends vocabulary or word statuses.
 */
class WebShareService(
    private val settings: SettingsRepository,
    private val glossCache: GlossCache,
    private val sentences: PhraseStore,
    private val http: OkHttpClient,
) {
    private val json = Json { encodeDefaults = false; explicitNulls = false }

    /** Returns the shared lesson's server id. */
    suspend fun share(lesson: LessonEntity): String = withContext(Dispatchers.IO) {
        val s = settings.current()
        val base = s.webUrl.trim().trimEnd('/')
        require(base.startsWith("https://") || base.startsWith("http://")) { "Set the web app address in Settings" }
        require(s.webAccessCode.isNotBlank()) { "Set the web app access code in Settings" }
        val body = payload(lesson, s.webName).toString().toRequestBody(JSON)
        var token = s.webToken.ifBlank { login(base, s.webAccessCode) }
        var resp = post(base, token, body)
        if (resp.first == 401) {
            token = login(base, s.webAccessCode)
            resp = post(base, token, body)
        }
        if (resp.first !in 200..299) throw IOException(errorOf(resp.second) ?: "Sharing failed (HTTP ${resp.first})")
        ((Json.parseToJsonElement(resp.second) as JsonObject)["id"] as JsonPrimitive).content
    }

    private suspend fun login(base: String, code: String): String {
        val req = Request.Builder().url("$base/api/login")
            .post(buildJsonObject { put("code", code.trim()) }.toString().toRequestBody(JSON)).build()
        val (status, text) = http.newCall(req).execute().use { it.code to it.body.string() }
        if (status != 200) throw IOException(errorOf(text) ?: "Web app login failed (HTTP $status)")
        val token = ((Json.parseToJsonElement(text) as JsonObject)["token"] as JsonPrimitive).content
        settings.update { it.copy(webToken = token) }
        return token
    }

    private fun post(base: String, token: String, body: okhttp3.RequestBody): Pair<Int, String> {
        val req = Request.Builder().url("$base/api/lessons").header("Authorization", "Bearer $token").post(body).build()
        return http.newCall(req).execute().use { it.code to it.body.string() }
    }

    private fun errorOf(text: String): String? =
        runCatching { ((Json.parseToJsonElement(text) as JsonObject)["error"] as JsonPrimitive).content }.getOrNull()

    private class SentenceInfo(val hash: String, val translation: String?, val phrases: List<FoundPhrase>, val glosses: Map<String, Gloss>)

    /** The lesson plus everything cached for its sentences, in the server's SentenceData shape. */
    internal suspend fun payload(lesson: LessonEntity, sharedBy: String): JsonObject {
        val text = Tokenizer.tokenize(lesson.text)
        val infos = ArrayList<SentenceInfo>()
        val done = HashSet<String>()
        for (i in text.sentenceRanges.indices) {
            val sentence = text.sentenceText(i)
            val hash = GlossCache.sentenceHash(sentence)
            if (!done.add(hash)) continue
            val glosses = LinkedHashMap<String, Gloss>()
            for (t in text.sentenceTokens(i)) {
                val form = t.normalized ?: continue
                if (form !in glosses) glossCache.get(t.text, sentence)?.let { glosses[form] = it }
            }
            val info = SentenceInfo(hash, sentences.translation(sentence), sentences.forSentence(sentence), glosses)
            if (info.translation != null || info.phrases.isNotEmpty() || glosses.isNotEmpty()) infos += info
        }
        return buildJsonObject {
            put("title", lesson.title)
            put("text", lesson.text)
            if (sharedBy.isNotBlank()) put("sharedBy", sharedBy.trim())
            putJsonArray("sentences") {
                for (info in infos) addJsonObject {
                    put("hash", info.hash)
                    info.translation?.let { put("translation", it) }
                    if (info.phrases.isNotEmpty()) putJsonArray("phrases") {
                        info.phrases.forEach { p -> addJsonObject { put("phrase", p.phrase); put("meaning", p.meaning) } }
                    }
                    // A stored translation comes from the sentence analysis, which also looked for idioms.
                    if (info.translation != null) put("scanned", true)
                    if (info.glosses.isNotEmpty()) putJsonObject("glosses") {
                        info.glosses.forEach { (form, g) -> put(form, json.encodeToJsonElement(Gloss.serializer(), g)) }
                    }
                }
            }
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
