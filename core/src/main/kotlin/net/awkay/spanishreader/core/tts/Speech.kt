package net.awkay.spanishreader.core.tts

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.awkay.spanishreader.core.gloss.RetryPolicy
import net.awkay.spanishreader.core.gloss.executeWithRetry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

/** Turns one sentence into an audio file. Implementations: cloud TTS (here) and Android on-device TTS (app). */
interface SentenceSynthesizer {
    /** Identifies the voice/settings; audio cached under one key is never reused for another. */
    val cacheKey: String

    /** File extension of the produced audio, without the dot. */
    val fileExtension: String

    /** Writes audio for [text] to [file]; throws on failure. */
    suspend fun synthesizeTo(text: String, file: File)
}

/**
 * Content-addressed audio cache: one file per (synthesizer, sentence text) under [root]/<cacheKey>/.
 * Files are written to a temp name and renamed so a crash never leaves a truncated file in the cache.
 */
class SentenceAudioCache(private val root: File) {
    fun fileFor(synth: SentenceSynthesizer, text: String): File =
        File(File(root, sanitize(synth.cacheKey)), "${sha256(text.trim())}.${synth.fileExtension}")

    /** Returns the cached file for [text], synthesizing it first if needed. */
    suspend fun ensure(synth: SentenceSynthesizer, text: String): File {
        val file = fileFor(synth, text)
        if (file.isFile && file.length() > 0) return file
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            synth.synthesizeTo(text.trim(), tmp)
            check(tmp.isFile && tmp.length() > 0) { "Synthesizer produced no audio" }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
        return file
    }

    fun clear() {
        root.deleteRecursively()
    }

    private companion object {
        fun sanitize(key: String) = key.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80) + "-" + sha256(key).take(8)

        fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

/** Google Cloud Text-to-Speech REST API (API-key auth), producing MP3. Voice names are settings; lineups change. */
class GoogleCloudSynthesizer(
    private val apiKey: String,
    private val voiceName: String,
    private val languageCode: String = voiceName.split('-').take(2).joinToString("-"),
    private val httpClient: OkHttpClient = OkHttpClient(),
    baseUrl: String = "https://texttospeech.googleapis.com",
    private val retry: RetryPolicy = RetryPolicy(),
) : SentenceSynthesizer {
    private val endpoint = baseUrl.trimEnd('/') + "/v1/text:synthesize"

    override val cacheKey: String get() = "google-$voiceName"
    override val fileExtension: String get() = "mp3"

    override suspend fun synthesizeTo(text: String, file: File) {
        file.writeBytes(synthesize(text))
    }

    suspend fun synthesize(text: String): ByteArray {
        val body = buildJsonObject {
            putJsonObject("input") { put("text", text) }
            putJsonObject("voice") {
                put("languageCode", languageCode)
                put("name", voiceName)
            }
            putJsonObject("audioConfig") { put("audioEncoding", "MP3") }
        }
        val request = Request.Builder()
            .url(endpoint)
            .header("X-Goog-Api-Key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = httpClient.executeWithRetry(request, retry)
        val audio = try {
            ((Json.parseToJsonElement(response) as? JsonObject)?.get("audioContent") as? JsonPrimitive)?.content
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: throw IOException("No audioContent in TTS response: ${response.take(200)}")
        return Base64.getDecoder().decode(audio)
    }
}
