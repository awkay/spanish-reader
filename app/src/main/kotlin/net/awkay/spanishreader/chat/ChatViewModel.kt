package net.awkay.spanishreader.chat

import android.media.MediaPlayer
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.awkay.spanishreader.SpanishReaderApp
import net.awkay.spanishreader.core.chat.ChatAi
import net.awkay.spanishreader.core.chat.ChatLine
import net.awkay.spanishreader.core.chat.ChatRole
import net.awkay.spanishreader.core.chat.ChatTranscript
import net.awkay.spanishreader.core.chat.Correction
import net.awkay.spanishreader.core.chat.PartnerSentence
import net.awkay.spanishreader.gloss.PreGlossWorker
import java.io.File
import java.time.LocalDate
import kotlin.coroutines.resume

/** What the conversation needs from outside: the AI, speech output, playback and speech input (swapped in tests). */
interface ChatBackend {
    /** Null when glossing isn't configured. */
    suspend fun ai(): ChatAi?

    /** One sentence's audio (kept in the sentence audio cache, so the saved lesson's listen mode reuses it). */
    suspend fun speech(sentence: String): File

    /** Plays [file], returning when it ends. */
    suspend fun play(file: File)

    suspend fun transcribe(recording: File): String

    companion object {
        @VisibleForTesting
        var override: ((SpanishReaderApp) -> ChatBackend)? = null

        fun of(app: SpanishReaderApp): ChatBackend = override?.invoke(app) ?: AppChatBackend(app)
    }
}

private class AppChatBackend(private val app: SpanishReaderApp) : ChatBackend {
    override suspend fun ai(): ChatAi? = app.glossService.chatCompleters()?.let { (partner, checker) -> ChatAi(partner, checker) }

    override suspend fun speech(sentence: String): File =
        withContext(Dispatchers.IO) { app.audioCache.ensure(app.synthesizer(app.settings.current()), sentence) }

    override suspend fun play(file: File) {
        val speed = app.settings.current().playbackSpeed
        suspendCancellableCoroutine { cont ->
            val player = MediaPlayer()
            cont.invokeOnCancellation { runCatching { player.release() } }
            try {
                player.setDataSource(file.absolutePath)
                player.prepare()
                if (speed != 1f) player.playbackParams = player.playbackParams.setSpeed(speed)
                player.setOnCompletionListener { it.release(); if (cont.isActive) cont.resume(Unit) }
                player.start()
            } catch (e: Exception) {
                player.release()
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }
        }
    }

    override suspend fun transcribe(recording: File): String = app.webShare.transcribe(recording)
}

sealed interface ChatItem {
    val id: Int
}

/** Something the learner said; [correction] arrives in the background. */
data class LearnerItem(
    override val id: Int,
    val said: String,
    val voice: Boolean,
    val correction: Correction? = null,
    val checking: Boolean = true,
) : ChatItem {
    /** The text used from now on: the corrected one when known. */
    val text: String get() = correction?.corrected ?: said
}

/** The partner's reply: heard first; its Spanish shows on tap ([revealed]), then its English ([english]). */
data class PartnerItem(
    override val id: Int,
    val sentences: List<PartnerSentence>,
    val revealed: Boolean = false,
    val english: Boolean = false,
    val audioFailed: Boolean = false,
) : ChatItem {
    val spanish: String get() = sentences.joinToString(" ") { it.es }
    val translation: String get() = sentences.joinToString(" ") { it.en }.trim()
}

data class ChatUi(
    /** Null until the learner has said what they want to talk about. */
    val topic: String? = null,
    val items: List<ChatItem> = emptyList(),
    val draft: String = "",
    /** The draft came (at least partly) from speech recognition. */
    val draftVoice: Boolean = false,
    val recording: Boolean = false,
    val recordMillis: Long = 0,
    val level: Float = 0f,
    val transcribing: Boolean = false,
    /** Waiting for the partner's reply. */
    val thinking: Boolean = false,
    /** The partner reply whose audio is playing. */
    val playing: Int? = null,
    /** The learner text the partner hasn't answered (the reply failed): offered for retry. */
    val unanswered: String? = null,
    val error: String? = null,
    val lessonId: Long? = null,
) {
    val exchanges: Int get() = items.count { it is PartnerItem }
}

/**
 * Conversation mode: the learner chooses a topic and speaks first; each turn gets the partner's spoken reply and, in
 * the background, a check of what the learner said. The conversation (with the learner's corrected turns) is saved as
 * a reading lesson, with the AI's translations and the reply audio already in place.
 */
class ChatViewModel(
    private val app: SpanishReaderApp,
    private val backend: ChatBackend = ChatBackend.of(app),
) : ViewModel() {
    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui
    private val recorder = VoiceRecorder(app)
    private var recordJob: Job? = null
    private var playJob: Job? = null
    private var nextId = 0
    private var ai: ChatAi? = null
    private val saveLock = Mutex()
    private var savedText: String? = null
    private val audio = HashMap<String, Deferred<File>>()

    fun start(topic: String) {
        if (topic.isBlank()) return
        _ui.update { it.copy(topic = topic.trim()) }
    }

    fun setDraft(text: String) = _ui.update { it.copy(draft = text, draftVoice = it.draftVoice && text.isNotBlank()) }

    fun dismissError() = _ui.update { it.copy(error = null) }

    fun showError(message: String) = _ui.update { it.copy(error = message) }

    fun send() {
        val s = _ui.value
        val said = s.draft.trim()
        if (said.isEmpty() || s.thinking || s.topic == null) return
        val item = LearnerItem(nextId++, said, s.draftVoice)
        val history = lines(s.items)
        _ui.update { it.copy(items = it.items + item, draft = "", draftVoice = false, error = null) }
        check(item, s.items.lastOrNull { it is PartnerItem }?.let { (it as PartnerItem).spanish })
        answer(said, history)
    }

    fun retryReply() {
        val said = _ui.value.unanswered ?: return
        val history = lines(_ui.value.items.dropLast(1))
        answer(said, history)
    }

    fun retryCheck(id: Int) {
        val items = _ui.value.items
        val i = items.indexOfFirst { it.id == id }
        val item = items.getOrNull(i) as? LearnerItem ?: return
        replace(id) { (it as LearnerItem).copy(checking = true) }
        check(item, (items.take(i).lastOrNull { it is PartnerItem } as PartnerItem?)?.spanish)
    }

    fun reveal(id: Int) = replace(id) { (it as PartnerItem).copy(revealed = true) }

    fun toggleEnglish(id: Int) = replace(id) { (it as PartnerItem).let { p -> p.copy(english = !p.english) } }

    fun replay(id: Int) {
        val item = _ui.value.items.firstOrNull { it.id == id } as? PartnerItem ?: return
        speak(item)
    }

    fun stopAudio() {
        playJob?.cancel()
        _ui.update { it.copy(playing = null) }
    }

    /** Tap to start, tap to stop; stops by itself after a minute. */
    fun toggleRecording() {
        if (_ui.value.recording) {
            finishRecording()
            return
        }
        if (_ui.value.transcribing) return
        stopAudio()
        try {
            recorder.start()
        } catch (e: Exception) {
            _ui.update { it.copy(error = "Couldn't use the microphone: ${e.message ?: e}") }
            return
        }
        val started = System.currentTimeMillis()
        _ui.update { it.copy(recording = true, recordMillis = 0, level = 0f, error = null) }
        recordJob = viewModelScope.launch {
            while (true) {
                delay(100)
                val elapsed = System.currentTimeMillis() - started
                _ui.update { it.copy(recordMillis = elapsed, level = recorder.level()) }
                if (elapsed >= MAX_RECORDING_MS) {
                    finishRecording()
                    break
                }
            }
        }
    }

    fun cancelRecording() {
        recordJob?.cancel()
        recorder.cancel()
        _ui.update { it.copy(recording = false) }
    }

    private fun finishRecording() {
        recordJob?.cancel()
        val file = recorder.stop()
        _ui.update { it.copy(recording = false) }
        if (file == null) {
            _ui.update { it.copy(error = "Nothing was recorded. Tap the microphone, speak, then tap it again.") }
            return
        }
        transcribe(file)
    }

    @VisibleForTesting
    internal fun transcribe(file: File) {
        _ui.update { it.copy(transcribing = true) }
        viewModelScope.launch {
            try {
                val text = backend.transcribe(file)
                if (text.isBlank()) {
                    _ui.update { it.copy(error = "No speech was recognized. Try again, or type.") }
                } else {
                    _ui.update { it.copy(draft = (it.draft.trim() + " " + text).trim(), draftVoice = true) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(error = "Speech recognition failed: ${e.message ?: e}. You can type instead.") }
            } finally {
                file.delete()
                _ui.update { it.copy(transcribing = false) }
            }
        }
    }

    /**
     * Saves the conversation as a lesson (or updates the one saved before) once there is a reply; returns its id.
     * Runs to the end even if the screen is closing.
     */
    suspend fun save(): Long? = withContext(NonCancellable) {
        saveLock.withLock {
            val s = _ui.value
            val topic = s.topic ?: return@withLock null
            if (s.exchanges == 0) return@withLock s.lessonId
            val text = ChatTranscript.text(s.items.map { if (it is LearnerItem) it.text else (it as PartnerItem).spanish })
            if (text == savedText) return@withLock s.lessonId
            try {
                val id = s.lessonId?.also { app.lessons.replaceText(it, text) } ?: run {
                    val today = LocalDate.now()
                    app.lessons.import(ChatTranscript.title(topic, today.dayOfMonth, today.monthValue), text, joinWrappedLines = false)
                }
                savedText = text
                _ui.update { it.copy(lessonId = id) }
                storeTranslations(s.items)
                val settings = app.settings.current()
                if (settings.preGlossOnImport) PreGlossWorker.enqueue(app, id, 0, settings.preGlossPagesAhead)
                id
            } catch (e: Exception) {
                _ui.update { it.copy(error = "Couldn't save the conversation: ${e.message ?: e}") }
                s.lessonId
            }
        }
    }

    fun saveInBackground() {
        viewModelScope.launch { save() }
    }

    /** Saves, then calls [then] on the main thread with the lesson id (null when there was nothing to save). */
    fun saveThen(then: (Long?) -> Unit) {
        viewModelScope.launch { then(save()) }
    }

    private suspend fun storeTranslations(items: List<ChatItem>) {
        for (item in items) {
            val pairs = when (item) {
                is PartnerItem -> ChatTranscript.translations(item.spanish, item.sentences)
                is LearnerItem -> item.correction?.let { ChatTranscript.translations(item.text, emptyList(), whole = it.english) }.orEmpty()
            }
            for ((sentence, en) in pairs) app.phrases.saveTranslation(sentence, en)
        }
    }

    private fun lines(items: List<ChatItem>): List<ChatLine> = items.map {
        when (it) {
            is LearnerItem -> ChatLine(ChatRole.LEARNER, it.text)
            is PartnerItem -> ChatLine(ChatRole.PARTNER, it.spanish)
        }
    }

    private suspend fun chatAi(): ChatAi? = ai ?: backend.ai()?.also { ai = it }

    private fun answer(said: String, history: List<ChatLine>) {
        val topic = _ui.value.topic ?: return
        _ui.update { it.copy(thinking = true, unanswered = null) }
        viewModelScope.launch {
            try {
                val chat = chatAi() ?: run {
                    _ui.update { it.copy(thinking = false, unanswered = said, error = "Glossing is not configured. Set it up in Settings.") }
                    return@launch
                }
                val reply = PartnerItem(nextId++, chat.reply(topic, history, said))
                _ui.update { it.copy(items = it.items + reply, thinking = false) }
                speak(reply)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(thinking = false, unanswered = said, error = "No reply: ${e.message ?: e}") }
            }
        }
    }

    private fun check(item: LearnerItem, partnerSaid: String?) {
        viewModelScope.launch {
            val correction = try {
                chatAi()?.correct(item.said, partnerSaid, item.voice)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            replace(item.id) { (it as LearnerItem).copy(correction = correction, checking = false) }
        }
    }

    /** Plays a reply sentence by sentence (each fetched while the one before plays); shows its text if there is no audio. */
    private fun speak(item: PartnerItem) {
        playJob?.cancel()
        if (_ui.value.recording) return
        val sentences = ChatTranscript.sentences(item.spanish)
        playJob = viewModelScope.launch {
            _ui.update { it.copy(playing = item.id) }
            try {
                var previous: Deferred<File>? = null
                val files = sentences.map { audioFor(it, previous).also { d -> previous = d } }
                for (f in files) backend.play(f.await())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                replace(item.id) { (it as PartnerItem).copy(revealed = true, audioFailed = true) }
            } finally {
                _ui.update { if (it.playing == item.id) it.copy(playing = null) else it }
            }
        }
    }

    /** One synthesis at a time (after [after]), each cached per sentence; a failed one is asked again next time. */
    private fun audioFor(sentence: String, after: Deferred<File>?): Deferred<File> = audio.getOrPut(sentence) {
        viewModelScope.async {
            try {
                runCatching { after?.await() }
                backend.speech(sentence)
            } catch (e: Exception) {
                audio.remove(sentence)
                throw e
            }
        }
    }

    private fun replace(id: Int, f: (ChatItem) -> ChatItem) =
        _ui.update { s -> s.copy(items = s.items.map { if (it.id == id) f(it) else it }) }

    override fun onCleared() {
        recordJob?.cancel()
        recorder.cancel()
    }

    companion object {
        const val MAX_RECORDING_MS = 60_000L
    }
}
