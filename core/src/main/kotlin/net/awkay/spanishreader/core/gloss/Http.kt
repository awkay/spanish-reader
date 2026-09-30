package net.awkay.spanishreader.core.gloss

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpStatusException(val code: Int, val body: String) : IOException("HTTP $code: ${body.take(300)}")

/**
 * Retries on HTTP 429, 5xx and I/O errors with exponential backoff, preferring the server's Retry-After.
 * [sleep] is injectable so tests need not actually wait.
 */
data class RetryPolicy(
    val maxRetries: Int = 3,
    val initialBackoffMillis: Long = 1_000,
    val maxBackoffMillis: Long = 30_000,
    val maxRetryAfterMillis: Long = 120_000,
    val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    fun backoffMillis(retry: Int): Long =
        (initialBackoffMillis shl retry.coerceAtMost(20)).coerceAtMost(maxBackoffMillis)

    companion object {
        fun isRetryable(code: Int): Boolean = code == 429 || code in 500..599
    }
}

/** Parses Retry-After as delta-seconds or an HTTP date. */
internal fun parseRetryAfterMillis(value: String?, now: ZonedDateTime = ZonedDateTime.now()): Long? {
    val v = value?.trim().orEmpty()
    if (v.isEmpty()) return null
    v.toLongOrNull()?.let { return (it * 1000).coerceAtLeast(0) }
    return try {
        Duration.between(now, ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME)).toMillis().coerceAtLeast(0)
    } catch (_: Exception) {
        null
    }
}

private suspend fun OkHttpClient.await(request: Request): Pair<Response, String> =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

            override fun onResponse(call: Call, response: Response) {
                val body = try {
                    response.use { it.body.string() }
                } catch (e: IOException) {
                    cont.resumeWithException(e)
                    return
                }
                cont.resume(response to body)
            }
        })
    }

/** Executes [request] with [policy]; returns the body of a 2xx response or throws. */
internal suspend fun OkHttpClient.executeWithRetry(request: Request, policy: RetryPolicy): String {
    var retry = 0
    while (true) {
        val waitMillis: Long = try {
            val (response, body) = await(request)
            when {
                response.isSuccessful -> return body
                RetryPolicy.isRetryable(response.code) && retry < policy.maxRetries ->
                    parseRetryAfterMillis(response.header("Retry-After"))?.coerceAtMost(policy.maxRetryAfterMillis)
                        ?: policy.backoffMillis(retry)

                else -> throw HttpStatusException(response.code, body)
            }
        } catch (e: HttpStatusException) {
            throw e
        } catch (e: IOException) {
            if (retry >= policy.maxRetries) throw e
            policy.backoffMillis(retry)
        }
        policy.sleep(waitMillis)
        retry++
    }
}
