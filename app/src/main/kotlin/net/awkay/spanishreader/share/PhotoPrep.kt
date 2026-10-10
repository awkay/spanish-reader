package net.awkay.spanishreader.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shrinks photos before they're uploaded for reading: a phone photo is 3–8 MB, while 2048 px on the long edge keeps
 * small print legible at a few hundred KB. ImageDecoder also applies the camera's EXIF rotation.
 */
object PhotoPrep {
    const val MAX_EDGE = 2048
    private const val QUALITY = 85

    /** [width]×[height] scaled down (never up) so the longer side is at most [maxEdge]. */
    fun scaledSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= maxEdge) return width to height
        val f = maxEdge.toDouble() / longest
        return max(1, (width * f).roundToInt()) to max(1, (height * f).roundToInt())
    }

    /** The photo at [uri] as JPEG bytes, at most [maxEdge] px on its long side. */
    fun jpeg(context: Context, uri: Uri, maxEdge: Int = MAX_EDGE): ByteArray {
        val bitmap = decode(context, uri, maxEdge)
        try {
            return ByteArrayOutputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) throw IOException("Couldn't compress the photo")
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** A small version of the photo for previews. */
    fun thumbnail(context: Context, uri: Uri, maxEdge: Int): Bitmap? = runCatching { decode(context, uri, maxEdge) }.getOrNull()

    private fun decode(context: Context, uri: Uri, maxEdge: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val (w, h) = scaledSize(info.size.width, info.size.height, maxEdge)
                decoder.setTargetSize(w, h)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // compress() needs a software bitmap
            }
        }
        // Android 8: no ImageDecoder. Subsample while decoding, then scale; EXIF rotation isn't applied (the model
        // reads sideways text anyway).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) throw IOException("Not an image")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val raw = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Couldn't read the photo")
        val (w, h) = scaledSize(raw.width, raw.height, maxEdge)
        if (w == raw.width && h == raw.height) return raw
        return raw.scale(w, h).also { if (it !== raw) raw.recycle() }
    }
}
