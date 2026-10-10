package net.awkay.spanishreader.share

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import net.awkay.spanishreader.ShareInbox
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhotoImportTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun photosShrinkToTheLongEdgeAndNeverGrow() {
        assertEquals(2048 to 1536, PhotoPrep.scaledSize(4000, 3000))
        assertEquals(1152 to 2048, PhotoPrep.scaledSize(2250, 4000))
        assertEquals(800 to 600, PhotoPrep.scaledSize(800, 600))
        assertEquals(2048 to 1, PhotoPrep.scaledSize(9000, 2))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // ImageDecoder needs Robolectric's real graphics
    fun aPhotoBecomesASmallerJpeg() {
        val file = File(context.cacheDir, "big.png")
        Bitmap.createBitmap(3000, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF336699.toInt()) }
            .compress(Bitmap.CompressFormat.PNG, 100, file.outputStream())
        val jpeg = PhotoPrep.jpeg(context, Uri.fromFile(file), maxEdge = 600)
        assertTrue(jpeg.size > 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte(), "JPEG bytes")
        val back = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertEquals(600 to 200, back.width to back.height)
    }

    @Test
    fun sharedImagesReachTheImportScreenAsPhotos() {
        val a = Uri.parse("content://media/external/images/1")
        val b = Uri.parse("content://media/external/images/2")
        val many = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg").putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b))
        assertTrue(ShareInbox.offer(context, many))
        assertEquals(listOf(a, b), ShareInbox.take()!!.photos)

        val one = Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, a)
        assertTrue(ShareInbox.offer(context, one))
        val shared = ShareInbox.take()!!
        assertEquals(listOf(a), shared.photos)
        assertEquals("", shared.text)
        assertNull(ShareInbox.take())

        val text = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Hola")
        assertTrue(ShareInbox.offer(context, text))
        assertEquals(emptyList(), ShareInbox.take()!!.photos)
    }
}
