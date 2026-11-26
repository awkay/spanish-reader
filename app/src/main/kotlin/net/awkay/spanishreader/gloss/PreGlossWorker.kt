package net.awkay.spanishreader.gloss

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import net.awkay.spanishreader.SpanishReaderApp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.awkay.spanishreader.core.gloss.GlossResult
import net.awkay.spanishreader.core.gloss.PreGlossProgress
import net.awkay.spanishreader.core.gloss.PreGlossPlanner
import net.awkay.spanishreader.core.text.Paginator
import net.awkay.spanishreader.core.text.Tokenizer
import java.util.concurrent.TimeUnit

/**
 * Glosses the not-yet-known words of a window of pages in the background so taps are instant and work offline.
 * The reader enqueues the window around the current page as you read; runs for the same lesson are queued, and
 * each skips whatever an earlier run already cached.
 */
class PreGlossWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SpanishReaderApp
        val lessonId = inputData.getLong(KEY_LESSON, -1)
        val lesson = app.lessons.get(lessonId) ?: return Result.success()
        val glosser = app.glossService.cachingGlosser().getOrElse {
            return Result.failure(workDataOf(KEY_ERROR to (it.message ?: "Glossing not configured")))
        }
        val settings = app.settings.current()
        val text = Tokenizer.tokenize(lesson.text)
        val pages = Paginator.paginate(text, settings.wordsPerPage)
        val fromPage = inputData.getInt(KEY_FROM_PAGE, 0).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        val count = inputData.getInt(KEY_PAGE_COUNT, 0)
        val toPage = if (count <= 0) pages.size else (fromPage + count).coerceAtMost(pages.size)
        val window = PreGlossPlanner.window(pages, fromPage, count)
        val progress = Progress(fromPage, toPage)
        progress.start(PreGlossProgress.Phase.STARTING, 0)
        val windowSentences = window.map { it.sentenceIndex }.distinct().map(text::sentenceText)
        // What other devices already looked up (the web app's shared cache) is free; only the rest goes to the AI.
        if (settings.webAccessCode.isNotBlank()) runCatching { app.webShare.pullCacheSentences(windowSentences) }
        analyzeSentences(app, windowSentences, progress)

        val statuses = app.vocab.statuses(window.mapNotNull { it.normalized })
        val plan = PreGlossPlanner.plan(text, statuses, settings.preGlossSentencesPerWord, window)
            .filter { app.glossCache.get(it.form, it.sentence) == null }

        var failed = 0
        var lastError: String? = null
        progress.start(PreGlossProgress.Phase.WORDS, plan.size)
        // Two batches at a time (as the glosser itself would), counting each batch as it comes back.
        for (round in plan.chunked(BATCH * PARALLEL)) {
            val results = coroutineScope {
                round.chunked(BATCH).map { batch -> async { glosser.gloss(batch).also { progress.add(batch.size) } } }.awaitAll().flatten()
            }
            results.filterIsInstance<GlossResult.Failure>().let {
                failed += it.size
                if (it.isNotEmpty()) lastError = it.last().error
            }
            // Everything failing usually means no network or a bad key: back off instead of burning through the lesson.
            if (results.all { it is GlossResult.Failure }) {
                return if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
                else Result.failure(workDataOf(KEY_ERROR to (lastError ?: "Glossing failed")))
            }
        }
        return Result.success(workDataOf(KEY_DONE to plan.size, KEY_TOTAL to plan.size, KEY_FAILED to failed))
    }

    /** Publishes [PreGlossProgress] through WorkManager, for the lesson's card in the library. */
    private inner class Progress(private val fromPage: Int, private val toPage: Int) {
        private val lock = Mutex()
        private var phase = PreGlossProgress.Phase.STARTING
        private var done = 0
        private var total = 0
        private var started = 0L

        suspend fun start(phase: PreGlossProgress.Phase, total: Int) = lock.withLock {
            this.phase = phase
            this.total = total
            done = 0
            started = System.currentTimeMillis()
            publish()
        }

        suspend fun add(n: Int) = lock.withLock {
            done += n
            publish()
        }

        private suspend fun publish() = setProgress(
            workDataOf(
                KEY_PHASE to phase.id, KEY_DONE to done, KEY_TOTAL to total, KEY_STARTED to started,
                KEY_UPDATED to System.currentTimeMillis(), KEY_FROM_PAGE to fromPage, KEY_TO_PAGE to toPage,
            ),
        )
    }

    /**
     * Each sentence is analyzed once: its English translation, and its idioms (idioms made of words the learner
     * already knows never get glossed word by word). Sentences that already have a translation are skipped. Best
     * effort: failed batches are simply retried on a later run.
     */
    private suspend fun analyzeSentences(app: SpanishReaderApp, sentences: List<String>, progress: Progress) {
        val todo = app.phrases.untranslated(sentences.filter { s -> s.any { it.isLetter() } })
        if (todo.isEmpty()) return
        val analyzer = app.glossService.sentenceAnalyzer() ?: return
        progress.start(PreGlossProgress.Phase.SENTENCES, todo.size)
        // Batches of the analyzer's own size, at most two at a time (its limit), each saved and counted as it returns.
        coroutineScope {
            todo.chunked(SENTENCE_BATCH).map { batch ->
                async {
                    val results = analyzer.analyze(batch)
                    val scanned = ArrayList<String>()
                    batch.zip(results).forEach { (sentence, analysis) ->
                        if (analysis != null) {
                            app.phrases.save(sentence, analysis.phrases)
                            analysis.translation?.let { app.phrases.saveTranslation(sentence, it) }
                            scanned += sentence
                        }
                    }
                    app.phrases.markScanned(scanned)
                    progress.add(batch.size)
                }
            }.awaitAll()
        }
    }

    companion object {
        const val KEY_LESSON = "lessonId"
        const val KEY_FROM_PAGE = "fromPage"
        const val KEY_PAGE_COUNT = "pageCount"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FAILED = "failed"
        const val KEY_ERROR = "error"
        const val KEY_PHASE = "phase"
        const val KEY_STARTED = "started"
        const val KEY_UPDATED = "updated"
        const val KEY_TO_PAGE = "toPage"
        const val TAG = "pregloss"
        /** Words per AI call, and calls at a time (the glosser's defaults). */
        private const val BATCH = 20
        private const val PARALLEL = 2

        /** Sentences per AI call (the analyzer's own batch size). */
        private const val SENTENCE_BATCH = 15
        private const val MAX_ATTEMPTS = 4

        fun uniqueName(lessonId: Long) = "pregloss-$lessonId"

        fun lessonTag(lessonId: Long) = "pregloss-lesson-$lessonId"

        /** Pre-glosses [pageCount] pages from [fromPage] (0 = to the end of the lesson), after any queued run. */
        fun enqueue(context: Context, lessonId: Long, fromPage: Int = 0, pageCount: Int = 0) {
            val request = OneTimeWorkRequestBuilder<PreGlossWorker>()
                .setInputData(workDataOf(KEY_LESSON to lessonId, KEY_FROM_PAGE to fromPage, KEY_PAGE_COUNT to pageCount))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .addTag(lessonTag(lessonId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(lessonId), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun cancel(context: Context, lessonId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(lessonId))
        }
    }
}
