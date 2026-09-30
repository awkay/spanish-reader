package net.awkay.spanishreader

import android.app.Application
import net.awkay.spanishreader.audio.DeviceSynthesizer
import net.awkay.spanishreader.audio.DeviceTts
import net.awkay.spanishreader.audio.LessonAudio
import net.awkay.spanishreader.core.tts.GoogleCloudSynthesizer
import net.awkay.spanishreader.core.tts.SentenceAudioCache
import net.awkay.spanishreader.core.tts.SentenceSynthesizer
import net.awkay.spanishreader.data.AppDatabase
import net.awkay.spanishreader.data.AppSettings
import net.awkay.spanishreader.data.BackupService
import net.awkay.spanishreader.data.LessonRepository
import net.awkay.spanishreader.data.RoomGlossCache
import net.awkay.spanishreader.data.SettingsRepository
import net.awkay.spanishreader.data.TtsEngine
import net.awkay.spanishreader.data.VocabRepository
import net.awkay.spanishreader.gloss.GlossService
import java.io.File

/** Manual dependency container; every screen reaches its collaborators through here. */
class SpanishReaderApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.create(this) }
    val lessons: LessonRepository by lazy { LessonRepository(database) }
    val vocab: VocabRepository by lazy { VocabRepository(database) }
    val glossCache: RoomGlossCache by lazy { RoomGlossCache(database.glosses()) }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val glossService: GlossService by lazy { GlossService(settings, glossCache) }
    val backup: BackupService by lazy { BackupService(database) }
    val deviceTts: DeviceTts by lazy { DeviceTts(this) }
    val audioCache: SentenceAudioCache by lazy { SentenceAudioCache(File(filesDir, "tts")) }
    val audio: LessonAudio by lazy { LessonAudio(this) }

    /** The sentence synthesizer selected in settings; falls back to on-device TTS when cloud TTS lacks a key. */
    fun synthesizer(s: AppSettings): SentenceSynthesizer =
        if (s.ttsEngine == TtsEngine.GOOGLE_CLOUD && s.googleTtsApiKey.isNotBlank() && s.googleTtsVoice.isNotBlank()) {
            GoogleCloudSynthesizer(s.googleTtsApiKey.trim(), s.googleTtsVoice.trim(), httpClient = GlossService.sharedHttp)
        } else {
            DeviceSynthesizer(deviceTts, s.deviceTtsLocale, s.deviceTtsVoice)
        }
}
