package net.awkay.spanishreader.core.gloss

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request

data class GlossOptions(
    /** Words sent per LLM call. */
    val maxBatchSize: Int = 20,
    /** Simultaneous LLM calls per glosser instance. */
    val maxConcurrency: Int = 2,
    val retry: RetryPolicy = RetryPolicy(),
    val temperature: Double = 0.2,
) {
    init {
        require(maxBatchSize > 0 && maxConcurrency > 0)
    }
}

/**
 * Shared batching, concurrency limiting and parse-retry for chat-style LLM providers.
 * Subclasses only build the HTTP request and pull the reply text out of the response body.
 */
private const val SENTENCE_BATCH = 15

abstract class LlmGlosser(
    protected val httpClient: OkHttpClient,
    val options: GlossOptions,
) : Glosser, SentenceAnalyzer {
    private val permits = Semaphore(options.maxConcurrency)

    protected abstract fun buildRequest(system: String, user: String, itemCount: Int): Request

    /** Extracts the assistant text from a successful response body. */
    protected abstract fun replyText(responseBody: String): String

    override suspend fun gloss(requests: List<GlossRequest>): List<GlossResult> {
        if (requests.isEmpty()) return emptyList()
        require(requests.map { it.id }.toSet().size == requests.size) { "GlossRequest ids must be unique" }
        val results = coroutineScope {
            requests.chunked(options.maxBatchSize)
                .map { batch -> async { permits.withPermit { glossBatch(batch) } } }
                .awaitAll()
                .flatten()
                .associateBy { it.id }
        }
        return requests.map { results.getValue(it.id) }
    }

    override suspend fun analyze(sentences: List<String>): List<SentenceAnalysis?> {
        if (sentences.isEmpty()) return emptyList()
        val indexed = sentences.mapIndexed { i, s -> i.toString() to s }
        val results = coroutineScope {
            indexed.chunked(SENTENCE_BATCH)
                .map { batch -> async { permits.withPermit { sentenceBatch(batch) } } }
                .awaitAll()
                .fold(HashMap<String, SentenceAnalysis>()) { acc, m -> acc.apply { putAll(m) } }
        }
        return indexed.map { (id, _) -> results[id] }
    }

    /** One call for up to [SENTENCE_BATCH] sentences; retries once on an unparseable reply, then gives up quietly. */
    private suspend fun sentenceBatch(batch: List<Pair<String, String>>): Map<String, SentenceAnalysis> {
        for (attempt in 0..1) {
            try {
                val body = httpClient.executeWithRetry(
                    buildRequest(GlossPrompt.SENTENCE_SYSTEM, GlossPrompt.sentenceUser(batch, isRetry = attempt > 0), batch.size),
                    options.retry,
                )
                return GlossParser.parseSentences(replyText(body), batch.map { it.first })
            } catch (e: CancellationException) {
                throw e
            } catch (_: MalformedGlossResponseException) {
                continue
            } catch (_: SerializationException) {
                continue
            } catch (_: Exception) {
                break
            }
        }
        return emptyMap()
    }

    private suspend fun glossBatch(batch: List<GlossRequest>): List<GlossResult> {
        val found = HashMap<String, Gloss>()
        var pending = batch
        var lastError = "Model reply did not include this word"
        var fatal: Exception? = null
        for (attempt in 0..1) {
            if (pending.isEmpty()) break
            try {
                val body = httpClient.executeWithRetry(
                    buildRequest(GlossPrompt.SYSTEM, GlossPrompt.user(pending, isRetry = attempt > 0), pending.size),
                    options.retry,
                )
                found += GlossParser.parse(replyText(body), pending)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MalformedGlossResponseException) {
                lastError = e.message ?: "Malformed model reply"
            } catch (e: SerializationException) {
                lastError = "Unparseable response body: ${e.message}"
            } catch (e: Exception) {
                // Transport or non-retryable HTTP failure; the retry policy already had its chance.
                fatal = e
                break
            }
            pending = pending.filter { it.id !in found }
        }
        return batch.map { r ->
            found[r.id]?.let { GlossResult.Success(r.id, it) }
                ?: GlossResult.Failure(r.id, fatal?.let { it.message ?: it.toString() } ?: lastError, fatal)
        }
    }
}
