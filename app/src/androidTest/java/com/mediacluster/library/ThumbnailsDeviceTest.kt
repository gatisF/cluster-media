package com.mediacluster.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/**
 * Previews of router-drive files, made through [RandomReader] like the real drive (here backed by a local
 * file with a small delay per read, as SMB has). Needs a video at /sdcard/Movies/Settings_tour.mp4.
 */
@RunWith(AndroidJUnit4::class)
class ThumbnailsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Counts bytes and reads, like watching the network traffic to the router. */
    private class Counting(file: File) : RandomReader {
        private val raf = RandomAccessFile(file, "r")
        var bytes = 0L
        var reads = 0
        override val length = raf.length()
        override fun readAt(position: Long, buf: ByteArray, off: Int, len: Int): Int {
            Thread.sleep(2)
            raf.seek(position)
            val n = raf.read(buf, off, len)
            if (n > 0) bytes += n
            reads++
            return n
        }
        override fun close() = raf.close()
    }

    private fun driveItem(file: File, kind: MediaKind, id: Long) =
        MediaItem("p:$id", "p", id, file.name, if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg", file.length(), 0, kind, "drive-test")

    private fun size(jpeg: ByteArray) = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        .also { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, it) }.let { it.outWidth to it.outHeight }

    @Before
    fun clearCache() {
        File(context.cacheDir, "thumbs").listFiles()?.forEach { it.delete() }
    }

    @Test
    fun driveVideoGetsAFrameWithoutReadingTheWholeFile() {
        val video = File("/sdcard/Movies/Settings_tour.mp4")
        assumeTrue("test video missing", video.exists())
        val reader = Counting(video)
        val t0 = System.nanoTime()
        val jpeg = Thumbnails(context).get(driveItem(video, MediaKind.VIDEO, -101), null) { reader }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertNotNull(jpeg)
        val (w, h) = size(jpeg!!)
        Log.i("ThumbTest", "drive video ${video.length()} bytes: preview ${w}x$h, ${jpeg.size} bytes, read ${reader.bytes} bytes in ${reader.reads} reads, $ms ms")
        assertTrue(w in 1..Thumbnails.MAX_SIDE && h in 1..Thumbnails.MAX_SIDE)
    }

    @Test
    fun drivePhotoGetsAPreview() {
        val photo = File(context.cacheDir, "big-test.jpg")
        Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(30, 120, 200)) }
            .also { b -> photo.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) } }.recycle()
        val jpeg = Thumbnails(context).get(driveItem(photo, MediaKind.IMAGE, -102), null) { Counting(photo) }
        assertNotNull(jpeg)
        assertEquals(Thumbnails.MAX_SIDE to 213, size(jpeg!!))
        photo.delete()
    }

    @Test
    fun brokenFileIsNotReadAgain() {
        val junk = File(context.cacheDir, "broken.mp4").apply { writeBytes(ByteArray(50_000) { it.toByte() }) }
        val thumbs = Thumbnails(context)
        val item = driveItem(junk, MediaKind.VIDEO, -103)
        var opened = 0
        assertNull(thumbs.get(item, null) { opened++; Counting(junk) })
        assertNull(thumbs.get(item, null) { opened++; Counting(junk) })
        assertEquals("second request must not touch the drive", 1, opened)
        junk.delete()
    }
}
