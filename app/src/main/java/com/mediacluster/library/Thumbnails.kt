package com.mediacluster.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import android.util.Size
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream

/** Random access to a file that isn't on this phone (a router-drive file). */
interface RandomReader : Closeable {
    val length: Long
    fun readAt(position: Long, buf: ByteArray, off: Int, len: Int): Int
}

/**
 * Small JPEG previews (at most [MAX_SIDE] px) of photos and videos, for the web page and for TVs, so a
 * grid of camera photos costs tens of KB per tile instead of several MB. Kept in memory and on disk.
 */
class Thumbnails(private val context: Context) {
    private val resolver = context.contentResolver
    private val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
    private val memory = object : LruCache<String, ByteArray>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    /** Files we couldn't preview, so the page doesn't make us retry (and re-read them) on every view. */
    private val failed = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * The preview for [item], or null if it can't be made. [uri] is the phone file's content URI;
     * [remote] opens a file that isn't on this phone (a router-drive file).
     */
    fun get(item: MediaItem, uri: Uri?, remote: (() -> RandomReader)?): ByteArray? {
        val key = keyOf(item)
        memory.get(key)?.let { return it }
        if (key in failed) return null
        val file = File(dir, "$key.jpg")
        if (file.exists()) return file.readBytes().also { memory.put(key, it) }
        val bitmap = runCatching { make(item, uri, remote) }.onFailure { Log.w(TAG, "No thumbnail for ${item.title}", it) }.getOrNull()
        if (bitmap == null) {
            failed += key
            return null
        }
        val jpeg = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            bitmap.recycle()
            out.toByteArray()
        }
        runCatching { file.writeBytes(jpeg) }
        trimDisk()
        memory.put(key, jpeg)
        return jpeg
    }

    /** Changes when the file does, so an edited photo gets a new preview. */
    fun keyOf(item: MediaItem) = "${item.driveId ?: "phone"}_${item.localId}_${item.sizeBytes}".replace('-', 'm')

    private fun make(item: MediaItem, uri: Uri?, remote: (() -> RandomReader)?): Bitmap? {
        if (uri != null) {
            if (Build.VERSION.SDK_INT >= 29) {
                // The system's own thumbnailer: fast, cached by MediaStore, handles rotation and videos.
                runCatching { return scaleDown(resolver.loadThumbnail(uri, Size(MAX_SIDE, MAX_SIDE), null)) }
            } else if (item.localId > 0) {
                @Suppress("DEPRECATION")
                val thumb = when (item.kind) {
                    MediaKind.IMAGE -> MediaStore.Images.Thumbnails.getThumbnail(resolver, item.localId, MediaStore.Images.Thumbnails.MINI_KIND, null)
                    MediaKind.VIDEO -> MediaStore.Video.Thumbnails.getThumbnail(resolver, item.localId, MediaStore.Video.Thumbnails.MINI_KIND, null)
                    MediaKind.AUDIO -> null
                }
                if (thumb != null) return scaleDown(thumb)
            }
            return when (item.kind) {
                MediaKind.IMAGE -> decode { resolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open") }
                // E.g. a video in a picked folder, which MediaStore has never seen.
                MediaKind.VIDEO -> videoFrame { it.setDataSource(context, uri) }
                MediaKind.AUDIO -> null
            }
        }
        if (remote == null) return null
        return when (item.kind) {
            MediaKind.IMAGE -> {
                if (item.sizeBytes !in 1..MAX_REMOTE_BYTES) return null
                // Router-drive photo: read it once over the network, then decode a subsampled copy from memory.
                val bytes = remote().use { r -> readFully(r) }
                decode { bytes.inputStream() }
            }
            // Router-drive video: the decoder reads only the parts it needs (index and one key frame).
            MediaKind.VIDEO -> remote().use { r -> videoFrame { it.setDataSource(dataSourceOf(r)) } }
            MediaKind.AUDIO -> null
        }
    }

    /** A frame about 10% in (at most 5 s), which is rarely the black or fade-in first frame. */
    private fun videoFrame(setSource: (MediaMetadataRetriever) -> Unit): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            setSource(retriever)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val atUs = (durationMs * 1000 / 10).coerceAtMost(5_000_000L)
            val frame = if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MAX_SIDE, MAX_SIDE)
            } else {
                retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            return frame?.let { scaleDown(it) }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun dataSourceOf(r: RandomReader) = object : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
            if (position >= r.length) -1 else r.readAt(position, buffer, offset, size)
        override fun getSize() = r.length
        override fun close() {}
    }

    private fun readFully(r: RandomReader): ByteArray {
        val out = ByteArray(r.length.toInt())
        var pos = 0
        while (pos < out.size) {
            val n = r.readAt(pos.toLong(), out, pos, out.size - pos)
            if (n <= 0) break
            pos += n
        }
        return if (pos == out.size) out else out.copyOf(pos)
    }

    /** Decodes at the smallest power-of-two size still at least [MAX_SIDE], then fixes EXIF rotation. */
    private fun decode(open: () -> InputStream): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bitmap = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val degrees = runCatching {
            open().use { ExifInterface(it) }.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL).let {
            when (it) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }
        return scaleDown(bitmap, degrees)
    }

    private fun scaleDown(bitmap: Bitmap, degrees: Float = 0f): Bitmap {
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
        if (scale == 1f && degrees == 0f) return bitmap
        val m = Matrix().apply { postScale(scale, scale); postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    /** Keeps the disk cache under [DISK_BYTES] by dropping the least recently written previews. */
    private fun trimDisk() {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length()
            f.delete()
            if (total <= DISK_BYTES * 3 / 4) break
        }
    }

    companion object {
        private const val TAG = "Thumbnails"
        const val MAX_SIDE = 320
        private const val QUALITY = 80
        private const val MEMORY_BYTES = 8 * 1024 * 1024
        private const val DISK_BYTES = 64L * 1024 * 1024
        /** Router-drive photos bigger than this are not previewed: reading them would take too long. */
        private const val MAX_REMOTE_BYTES = 40L * 1024 * 1024
    }
}
