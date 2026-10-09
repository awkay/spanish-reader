package net.awkay.spanishreader.share

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.awkay.spanishreader.core.gloss.FoundPhrase
import net.awkay.spanishreader.core.gloss.Gloss
import net.awkay.spanishreader.core.gloss.GlossCache
import net.awkay.spanishreader.core.text.ImportCleaner
import net.awkay.spanishreader.core.text.Tokenizer
import net.awkay.spanishreader.data.LessonEntity
import net.awkay.spanishreader.data.LessonRepository
import net.awkay.spanishreader.data.PhraseStore
import net.awkay.spanishreader.data.SettingsRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * "Share to web": uploads a lesson to the household web app's shared library (portal.fulcrologic.com),
 * together with the AI work already done for its sentences (glosses, translations, idioms), so the other reader
 * gets it without paying for it again. Never sends vocabulary or word statuses.
 * Also the reverse, "Get from web": lists the shared library and copies a lesson plus its cached AI results here.
 */
class WebShareService(
    private val settings: SettingsRepository,
    private val glossCache: GlossCache,
    private val sentences: PhraseStore,
    private val http: OkHttpClient,
) {
    private val json = Json { encodeDefaults = false; explicitNulls = false }
    private val lenient = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Returns the shared lesson's server id. */
    suspend fun share(lesson: LessonEntity): String = withContext(Dispatchers.IO) {
        val body = payload(lesson, settings.current().webName).toString().toRequestBody(JSON)
        val text = call("/api/lessons", body, "Sharing")
        ((Json.parseToJsonElement(text) as JsonObject)["id"] as JsonPrimitive).content
    }

    /** The web app's shared library, newest first (as the server orders it). */
    suspend fun listShared(): List<SharedLessonSummary> = withContext(Dispatchers.IO) {
        (Json.parseToJsonElement(call("/api/lessons", null, "Loading the shared library")) as JsonArray).map { e ->
            val o = e.jsonObject
            SharedLessonSummary(
                id = o.str("id")!!,
                title = o.str("title").orEmpty(),
                words = (o["words"] as? JsonPrimitive)?.intOrNull ?: 0,
                sharedBy = o.str("sharedBy")?.takeIf { it.isNotBlank() },
                createdAtMillis = (o["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
                videoId = o.str("videoId")?.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * Copies shared lesson [id] into the local library through the normal import path, then stores whatever AI
     * results the server has for its sentences. A lesson already here with the same title and text is reused.
     * Vocabulary and word statuses are never touched.
     */
    suspend fun download(id: String, lessons: LessonRepository): Downloaded = withContext(Dispatchers.IO) {
        val o = Json.parseToJsonElement(call("/api/lessons/${id.encodeForPath()}", null, "Downloading")).jsonObject
        val title = o.str("title").orEmpty()
        // Already cleaned on the device that shared it; no line joining, so a poem's lines stay as sent.
        val cleaned = ImportCleaner.clean(o.str("text").orEmpty(), joinWrappedLines = false)
        val existing = lessons.all().firstOrNull { it.text == cleaned && it.title == title.trim() }
        val lessonId = existing?.id ?: lessons.import(title, cleaned, joinWrappedLines = false)
        val videoId = o.str("videoId")?.takeIf { VIDEO_ID.matches(it) }
        if (videoId != null && existing?.videoId != videoId) lessons.setVideo(lessonId, videoId, o.str("sourceUrl"))
        val stored = lessons.get(lessonId)!!
        // The lesson is kept even if this part fails; adding it again later retries just the AI results.
        val ai = try {
            pullCache(stored.text)
        } catch (e: IOException) {
            null
        }
        Downloaded(lessonId, alreadyHad = existing != null, aiResults = ai)
    }

    /**
     * Turns a YouTube video into a lesson on the web server (it downloads and transcribes the audio, which can take
     * minutes), reporting [onProgress] while waiting, then adds it here like [download].
     */
    suspend fun importYouTube(
        url: String,
        lessons: LessonRepository,
        onProgress: (String) -> Unit,
        pollMillis: Long = 3000,
    ): Downloaded {
        val start = buildJsonObject { put("url", url.trim()) }.toString().toRequestBody(JSON)
        var job = withContext(Dispatchers.IO) { Json.parseToJsonElement(call("/api/youtube", start, "Starting the import")).jsonObject }
        while (true) {
            when (job.str("status")) {
                "error" -> throw IOException(job.str("error") ?: "The import failed")
                "done" -> job.str("lessonId")?.let { return download(it, lessons) }
            }
            val detail = job.str("detail") ?: if (job.str("status") == "queued") "Waiting for another import to finish" else null
            onProgress(listOfNotNull(job.str("title"), detail).joinToString(" — "))
            delay(pollMillis)
            val id = job.str("id") ?: throw IOException("The server didn't start the import")
            job = withContext(Dispatchers.IO) {
                Json.parseToJsonElement(call("/api/youtube/jobs/${id.encodeForPath()}", null, "Checking the import")).jsonObject
            }
        }
    }

    /**
     * The original recording of a YouTube lesson's page: one MP3 for [sentences] (in order) plus each sentence's
     * [start, end] in milliseconds within it. Cached in [dir], so a page is fetched once and then plays offline.
     */
    suspend fun videoPage(videoId: String, sentences: List<String>, dir: File): VideoPage = withContext(Dispatchers.IO) {
        require(VIDEO_ID.matches(videoId)) { "Not a YouTube video ID: $videoId" }
        val key = MessageDigest.getInstance("SHA-256")
            .digest((listOf(videoId) + sentences).joinToString("\u0000").toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
        val mp3 = File(dir, "$key.mp3")
        val meta = File(dir, "$key.json")
        if (mp3.exists() && meta.exists()) {
            runCatching { return@withContext VideoPage(mp3, parseTimings(meta.readText())) }
        }
        val body = buildJsonObject { putJsonArray("sentences") { sentences.forEach { add(JsonPrimitive(it)) } } }
        val text = call("/api/youtube/${videoId.encodeForPath()}/audio", body.toString().toRequestBody(JSON), "Loading the video audio")
        val o = Json.parseToJsonElement(text).jsonObject
        val audioPath = o.str("audio") ?: throw IOException("The server sent no audio")
        require(audioPath.startsWith("/api/audio/")) { "Unexpected audio path" }
        val timings = parseTimings(o["timings"].toString())
        dir.mkdirs()
        val tmp = File(dir, "$key.tmp")
        tmp.writeBytes(callBytes(audioPath, "Downloading the video audio"))
        if (!tmp.renameTo(mp3)) throw IOException("Could not save the video audio")
        meta.writeText(o["timings"].toString())
        VideoPage(mp3, timings)
    }

    private fun parseTimings(text: String): List<LongRange> = (Json.parseToJsonElement(text) as JsonArray).map { t ->
        val pair = t as JsonArray
        (pair[0] as JsonPrimitive).content.toLong()..(pair[1] as JsonPrimitive).content.toLong()
    }

    /**
     * Stores the server's shared sentence cache (glosses, translations, idioms) for [text]'s sentences into the
     * local caches, under the same keys the app uses. Local results win: nothing already cached is overwritten.
     * Returns how many glosses and translations were added.
     */
    internal suspend fun pullCache(text: String): Int {
        val tokens = Tokenizer.tokenize(text)
        val sentenceByHash = LinkedHashMap<String, String>()
        for (i in tokens.sentenceRanges.indices) {
            val sentence = tokens.sentenceText(i)
            sentenceByHash.getOrPut(GlossCache.sentenceHash(sentence)) { sentence }
        }
        var added = 0
        for (chunk in sentenceByHash.keys.chunked(CACHE_CHUNK)) {
            val body = buildJsonObject { putJsonArray("hashes") { chunk.forEach { add(JsonPrimitive(it)) } } }
            val data = Json.parseToJsonElement(call("/api/cache/get", body.toString().toRequestBody(JSON), "Loading AI results"))
                as? JsonObject ?: continue
            for ((hash, value) in data) {
                val sentence = sentenceByHash[hash] ?: continue
                val d = value as? JsonObject ?: continue
                (d["glosses"] as? JsonObject)?.forEach { (form, g) ->
                    val gloss = runCatching { lenient.decodeFromJsonElement(Gloss.serializer(), g) }.getOrNull() ?: return@forEach
                    if (glossCache.get(form, sentence) == null) {
                        glossCache.put(form, sentence, gloss)
                        added++
                    }
                }
                val translation = d.str("translation")
                if (!translation.isNullOrBlank() && sentences.translation(sentence) == null) {
                    sentences.saveTranslation(sentence, translation)
                    added++
                }
                val phrases = (d["phrases"] as? JsonArray).orEmpty().mapNotNull { p ->
                    val po = p as? JsonObject ?: return@mapNotNull null
                    val phrase = po.str("phrase")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    FoundPhrase(phrase, po.str("meaning").orEmpty())
                }
                sentences.save(sentence, phrases)
                if ((d["scanned"] as? JsonPrimitive)?.booleanOrNull == true) sentences.markScanned(listOf(sentence))
            }
        }
        return added
    }

    /** POSTs [body] (or GETs when null) to [path] with the Bearer token, logging in when there is none or it expired. */
    private suspend fun call(path: String, body: okhttp3.RequestBody?, what: String): String =
        callBytes(path, what, body).toString(Charsets.UTF_8)

    private suspend fun callBytes(path: String, what: String, body: okhttp3.RequestBody? = null): ByteArray {
        val s = settings.current()
        val base = s.webUrl.trim().trimEnd('/')
        require(base.startsWith("https://") || base.startsWith("http://")) { "Set the web app address in Settings" }
        require(s.webAccessCode.isNotBlank()) { "Set the web app access code in Settings" }
        var token = s.webToken.ifBlank { login(base, s.webAccessCode) }
        var resp = send(base + path, token, body)
        if (resp.first == 401) {
            token = login(base, s.webAccessCode)
            resp = send(base + path, token, body)
        }
        if (resp.first !in 200..299) {
            throw IOException(errorOf(resp.second.toString(Charsets.UTF_8)) ?: "$what failed (HTTP ${resp.first})")
        }
        return resp.second
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

    private fun send(url: String, token: String, body: okhttp3.RequestBody?): Pair<Int, ByteArray> {
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .apply { if (body != null) post(body) }.build()
        return http.newCall(req).execute().use { it.code to it.body.bytes() }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun String.encodeForPath(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

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
            lesson.videoId?.let {
                put("source", "youtube")
                put("videoId", it)
                lesson.sourceUrl?.let { u -> put("sourceUrl", u) }
            }
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
        const val CACHE_CHUNK = 500 // the server accepts up to 2000 hashes per request
        val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    }
}

data class SharedLessonSummary(
    val id: String,
    val title: String,
    val words: Int,
    val sharedBy: String?,
    val createdAtMillis: Long,
    val videoId: String? = null,
)

/** A page of a YouTube lesson's original audio; [timings] are each sentence's start..end (ms) within [file]. */
data class VideoPage(val file: File, val timings: List<LongRange>)

/**
 * Result of [WebShareService.download]: the local lesson, whether it was already here, and how many AI results
 * (glosses + translations) came along; null when they couldn't be fetched.
 */
data class Downloaded(val lessonId: Long, val alreadyHad: Boolean, val aiResults: Int?)
