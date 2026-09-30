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
import net.awkay.spanishreader.core.gloss.GlossResult
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
        val window = PreGlossPlanner.window(
            Paginator.paginate(text, settings.wordsPerPage),
            inputData.getInt(KEY_FROM_PAGE, 0),
            inputData.getInt(KEY_PAGE_COUNT, 0),
        )
        val statuses = app.vocab.statuses(window.mapNotNull { it.normalized })
        val plan = PreGlossPlanner.plan(text, statuses, settings.preGlossSentencesPerWord, window)
            .filter { app.glossCache.get(it.form, it.sentence) == null }

        var done = 0
        var failed = 0
        var lastError: String? = null
        setProgress(workDataOf(KEY_DONE to 0, KEY_TOTAL to plan.size))
        for (chunk in plan.chunked(CHUNK)) {
            val results = glosser.gloss(chunk)
            results.filterIsInstance<GlossResult.Failure>().let {
                failed += it.size
                if (it.isNotEmpty()) lastError = it.last().error
            }
            done += chunk.size
            setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to plan.size))
            // Everything failing usually means no network or a bad key: back off instead of burning through the lesson.
            if (results.all { it is GlossResult.Failure }) {
                return if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
                else Result.failure(workDataOf(KEY_ERROR to (lastError ?: "Glossing failed")))
            }
        }
        return Result.success(workDataOf(KEY_DONE to done, KEY_TOTAL to plan.size, KEY_FAILED to failed))
    }

    companion object {
        const val KEY_LESSON = "lessonId"
        const val KEY_FROM_PAGE = "fromPage"
        const val KEY_PAGE_COUNT = "pageCount"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FAILED = "failed"
        const val KEY_ERROR = "error"
        const val TAG = "pregloss"
        private const val CHUNK = 40
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
