package net.awkay.spanishreader.chat

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Records one thing the learner says to a small m4a file (AAC, 16 kHz mono) for the server's speech recognizer. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    val recording: Boolean get() = recorder != null

    /** @throws Exception when the microphone can't be opened. */
    fun start() {
        cancel()
        val out = File(context.cacheDir, "speech-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16_000)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(32_000)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            out.delete()
            throw e
        }
        recorder = r
        file = out
    }

    /** 0..1, for a level meter. */
    fun level(): Float = runCatching { (recorder?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f).coerceIn(0f, 1f)

    /** The recording, or null when it was too short to keep. The caller deletes the file. */
    fun stop(): File? {
        val r = recorder ?: return null
        val out = file
        recorder = null
        file = null
        val ok = try {
            r.stop()
            true
        } catch (_: RuntimeException) {
            false // stopped before any audio was written
        } finally {
            r.release()
        }
        if (!ok || out == null || out.length() == 0L) {
            out?.delete()
            return null
        }
        return out
    }

    fun cancel() {
        stop()?.delete()
    }
}
