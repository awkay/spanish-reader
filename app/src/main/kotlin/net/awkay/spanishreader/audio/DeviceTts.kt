package net.awkay.spanishreader.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import net.awkay.spanishreader.core.tts.SentenceSynthesizer
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Coroutine wrapper around the Android TextToSpeech engine: file synthesis for listen mode, and speaking single words. */
class DeviceTts(context: Context) {
    private val ready = CompletableDeferred<TextToSpeech>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val ids = AtomicLong()
    private val mutex = Mutex()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) ready.complete(engine)
        else ready.completeExceptionally(IOException("Text-to-speech engine failed to start ($status)"))
    }
    private val engine get() = tts

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit

            override fun onDone(utteranceId: String) {
                pending.remove(utteranceId)?.complete(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                pending.remove(utteranceId)?.completeExceptionally(IOException("Text-to-speech failed"))
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                pending.remove(utteranceId)?.completeExceptionally(IOException("Text-to-speech failed (code $errorCode)"))
            }
        })
    }

    private suspend fun await(): TextToSpeech = withTimeout(15_000) { ready.await() }

    /** Spanish voices installed on the device, best quality first. */
    suspend fun spanishVoices(): List<Voice> = await().voices.orEmpty()
        .filter { it.locale.language == "es" && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        .sortedWith(compareBy<Voice>({ it.locale.toLanguageTag() }, { it.isNetworkConnectionRequired }, { -it.quality }, { it.name }))

    private fun configure(t: TextToSpeech, localeTag: String, voiceName: String) {
        val voice = voiceName.takeIf { it.isNotBlank() }?.let { name -> t.voices.orEmpty().firstOrNull { it.name == name } }
        if (voice != null) t.voice = voice
        else {
            val result = t.setLanguage(Locale.forLanguageTag(localeTag))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.forLanguageTag("es"))
        }
    }

    suspend fun synthesizeToFile(text: String, file: File, localeTag: String, voiceName: String) = mutex.withLock {
        val t = await()
        configure(t, localeTag, voiceName)
        val id = "file-${ids.incrementAndGet()}"
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        val rc = t.synthesizeToFile(text, Bundle(), file, id)
        if (rc != TextToSpeech.SUCCESS) {
            pending.remove(id)
            throw IOException("Text-to-speech rejected the sentence")
        }
        withTimeout(120_000) { done.await() }
    }

    suspend fun speak(text: String, localeTag: String, voiceName: String) {
        val t = await()
        configure(t, localeTag, voiceName)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "speak-${ids.incrementAndGet()}")
    }

    fun shutdown() = tts.shutdown()
}

class DeviceSynthesizer(private val tts: DeviceTts, private val localeTag: String, private val voiceName: String) : SentenceSynthesizer {
    override val cacheKey: String get() = "device-$localeTag-${voiceName.ifBlank { "default" }}"
    override val fileExtension: String get() = "wav"

    override suspend fun synthesizeTo(text: String, file: File) = tts.synthesizeToFile(text, file, localeTag, voiceName)
}
