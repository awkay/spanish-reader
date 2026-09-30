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
import net.awkay.spanishreader.core.text.Tokenizer
import java.util.concurrent.TimeUnit

/** Glosses every not-yet-known word of a lesson in the background so taps are instant and work offline. */
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
        val statuses = app.vocab.statuses(text.tokens.mapNotNull { it.normalized })
        val plan = PreGlossPlanner.plan(text, statuses, settings.preGlossSentencesPerWord)
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
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FAILED = "failed"
        const val KEY_ERROR = "error"
        const val TAG = "pregloss"
        private const val CHUNK = 40
        private const val MAX_ATTEMPTS = 4

        fun uniqueName(lessonId: Long) = "pregloss-$lessonId"

        fun lessonTag(lessonId: Long) = "pregloss-lesson-$lessonId"

        fun enqueue(context: Context, lessonId: Long) {
            val request = OneTimeWorkRequestBuilder<PreGlossWorker>()
                .setInputData(workDataOf(KEY_LESSON to lessonId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .addTag(lessonTag(lessonId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(lessonId), ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context, lessonId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(lessonId))
        }
    }
}
