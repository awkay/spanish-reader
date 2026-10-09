package net.awkay.spanishreader.share

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import org.schabi.newpipe.extractor.downloader.Request as NpRequest

/**
 * Downloads a YouTube video's audio on the phone. YouTube blocks the web server's datacenter IP, so the Android app
 * fetches the audio over its own (residential / mobile) connection and uploads it; the server transcribes it.
 */
interface VideoAudioSource {
    /**
     * Downloads the audio of video [videoId] into a new file in [dir]. Refuses videos over [maxMinutes] before
     * downloading. [onProgress] gets 0..100 (or -1 when the size is unknown).
     */
    suspend fun fetch(videoId: String, dir: File, maxMinutes: Int, onProgress: (Int) -> Unit): VideoAudio
}

/** A downloaded audio stream: [file] is a temp file the caller deletes. */
data class VideoAudio(val file: File, val title: String, val durationSec: Int, val mimeType: String, val fileName: String)

/** An audio-only stream on offer, reduced to what [pickAudioStream] needs. */
data class AudioOption(
    val url: String,
    val mimeType: String,
    val suffix: String,
    val kbps: Int, // -1 if unknown
    val opus: Boolean,
    val original: Boolean, // not a dubbed / descriptive track
    val progressive: Boolean,
    val contentLength: Long = -1,
)

/**
 * The smallest stream that still sounds fine for speech: progressive (plain HTTP) only, original-language track,
 * the lowest bitrate at or above [MIN_SPEECH_KBPS] (opus preferred at equal size, it sounds better per bit);
 * if all are below that, the best of those. Null when nothing is downloadable.
 */
fun pickAudioStream(options: List<AudioOption>): AudioOption? {
    val usable = options.filter { it.progressive && it.url.isNotBlank() }
    val pool = usable.filter { it.original }.ifEmpty { usable }
    if (pool.isEmpty()) return null
    val known = pool.filter { it.kbps > 0 }
    val decent = known.filter { it.kbps >= MIN_SPEECH_KBPS }
    return decent.minWithOrNull(compareBy<AudioOption> { it.kbps }.thenBy { if (it.opus) 0 else 1 })
        ?: known.maxByOrNull { it.kbps }
        ?: pool.first()
}

const val MIN_SPEECH_KBPS = 48

class VideoAudioException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** [VideoAudioSource] backed by NewPipe Extractor, with OkHttp doing the HTTP. */
class NewPipeAudioSource(http: OkHttpClient) : VideoAudioSource {
    private val http = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()

    override suspend fun fetch(videoId: String, dir: File, maxMinutes: Int, onProgress: (Int) -> Unit): VideoAudio =
        withContext(Dispatchers.IO) {
            ensureInit(http)
            val info = try {
                StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            } catch (e: Exception) {
                throw explain(e)
            }
            if (info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM) {
                throw VideoAudioException("Live streams can't be imported.")
            }
            val durationSec = info.duration.toInt()
            if (durationSec <= 0) throw VideoAudioException("This video has no known length, so it can't be imported.")
            val minutes = (durationSec + 59) / 60
            if (maxMinutes > 0 && minutes > maxMinutes) {
                throw VideoAudioException("The video is $minutes minutes long; the limit is $maxMinutes.")
            }
            val options = info.audioStreams.map { s ->
                val format = s.format
                AudioOption(
                    url = if (s.isUrl) s.content else "",
                    mimeType = format?.mimeType ?: "audio/webm",
                    suffix = format?.suffix ?: "webm",
                    kbps = s.averageBitrate,
                    opus = format?.name?.contains("OPUS") == true || s.codec?.contains("opus") == true,
                    original = s.audioTrackType == null || s.audioTrackType == AudioTrackType.ORIGINAL,
                    progressive = s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP,
                    contentLength = s.itagItem?.contentLength ?: -1,
                )
            }
            val pick = pickAudioStream(options) ?: throw VideoAudioException(
                "YouTube offered no downloadable audio for this video. If this happens for every video, " +
                    "YouTube changed something: update the app (its YouTube extractor).",
            )
            dir.mkdirs()
            val file = File.createTempFile("yt-$videoId-", ".${pick.suffix}", dir)
            try {
                download(pick, file, onProgress)
            } catch (e: Exception) {
                file.delete()
                throw e
            }
            VideoAudio(file, info.name?.takeIf { it.isNotBlank() } ?: "YouTube $videoId", durationSec, pick.mimeType,
                "$videoId.${pick.suffix}")
        }

    /**
     * Fetches the stream in ranges (YouTube throttles or cuts long single requests), the way NewPipe's player
     * does: POST with body "x\0", a request number, the same desktop user agent the extractor used.
     */
    private suspend fun download(pick: AudioOption, file: File, onProgress: (Int) -> Unit) {
        var total = pick.contentLength
        var pos = 0L
        var rn = 0
        file.outputStream().buffered().use { out ->
            while (total < 0 || pos < total) {
                coroutineContext.ensureActive()
                val end = if (total > 0) minOf(pos + CHUNK, total) - 1 else pos + CHUNK - 1
                val req = Request.Builder().url(pick.url + "&rn=${rn++}")
                    .header("User-Agent", userAgentFor(pick.url))
                    .header("Range", "bytes=$pos-$end")
                    .header("Accept-Encoding", "identity")
                    .apply {
                        if (YoutubeParsingHelper.isWebStreamingUrl(pick.url)) {
                            header("Origin", "https://www.youtube.com")
                            header("Referer", "https://www.youtube.com/")
                        }
                    }
                    .post(byteArrayOf(0x78, 0).toRequestBody())
                    .build()
                val (code, n) = http.newCall(req).execute().use { resp ->
                    when (resp.code) {
                        200, 206 -> {}
                        416 -> return@use 416 to 0L // asked past the end (length was unknown)
                        403 -> throw VideoAudioException(
                            "YouTube refused the audio download (HTTP 403). Try again; if it keeps happening, " +
                                "YouTube changed something: update the app.",
                        )
                        else -> throw IOException("Audio download failed (HTTP ${resp.code})")
                    }
                    if (resp.code == 200 && pos > 0) throw IOException("Audio download failed (the server ignored the range)")
                    if (total < 0) total = resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1
                    resp.code to resp.body.byteStream().copyTo(out)
                }
                if (n == 0L) break
                pos += n
                if (code == 200) break // the whole file in one answer
                onProgress(if (total > 0) ((pos * 100) / total).toInt().coerceIn(0, 100) else -1)
            }
        }
        if (pos == 0L) throw IOException("The audio download came back empty")
    }

    private fun userAgentFor(url: String): String = when {
        YoutubeParsingHelper.isVisionOsStreamingUrl(url) -> YoutubeParsingHelper.getVisionOsUserAgent(null)
        YoutubeParsingHelper.isAndroidStreamingUrl(url) -> YoutubeParsingHelper.getAndroidUserAgent(null)
        YoutubeParsingHelper.isIosStreamingUrl(url) -> YoutubeParsingHelper.getIosUserAgent(null)
        else -> USER_AGENT
    }

    companion object {
        private const val CHUNK = 2L shl 20
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        @Volatile private var initialized = false

        @Synchronized
        private fun ensureInit(http: OkHttpClient) {
            if (!initialized) {
                NewPipe.init(OkHttpDownloader(http))
                initialized = true
            }
        }

        /** Turns extractor failures into messages Tony can act on. */
        fun explain(e: Exception): Exception = when (e) {
            is AgeRestrictedContentException ->
                VideoAudioException("This video is age-restricted; YouTube only plays it to signed-in users, so it can't be imported.", e)
            is PrivateContentException -> VideoAudioException("This video is private.", e)
            is GeographicRestrictionException -> VideoAudioException("This video isn't available in this country.", e)
            is PaidContentException, is YoutubeMusicPremiumContentException ->
                VideoAudioException("This video is for paying members only.", e)
            is SignInConfirmNotBotException, is ReCaptchaException ->
                VideoAudioException("YouTube asked to confirm this isn't a bot. Try again later or on another network (Wi-Fi / mobile data).", e)
            is ContentNotAvailableException ->
                VideoAudioException("This video isn't available: ${e.message ?: "removed, private or members-only"}", e)
            is IOException -> e // network trouble: the caller words it
            else -> VideoAudioException(
                "Couldn't read this YouTube video (${e.message ?: e.javaClass.simpleName}). YouTube probably changed " +
                    "something: update the app to get a newer YouTube extractor.",
                e,
            )
        }
    }
}

/** NewPipe's HTTP layer on OkHttp (as in NewPipe's own DownloaderImpl). */
class OkHttpDownloader(private val http: OkHttpClient) : Downloader() {
    override fun execute(request: NpRequest): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = Request.Builder().method(request.httpMethod(), body).url(request.url())
            .header("User-Agent", NewPipeAudioSource.USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        http.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            return Response(resp.code, resp.message, resp.headers.toMultimap(), resp.body.string(), resp.request.url.toString())
        }
    }
}
