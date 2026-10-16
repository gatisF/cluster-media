package com.mediacluster.library

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.InputStream

/**
 * Saves media added to the cluster onto this phone, in a "MediaCluster" folder inside
 * Movies, Music or Pictures, so it shows up in the phone's own gallery and players too.
 */
class MediaWriter(private val context: Context) {

    class Rejected(message: String) : Exception(message)

    /** The folder new files of [kind] go to, as a MediaStore relative path. */
    fun folderFor(kind: MediaKind): String = when (kind) {
        MediaKind.VIDEO -> Environment.DIRECTORY_MOVIES
        MediaKind.AUDIO -> Environment.DIRECTORY_MUSIC
        MediaKind.IMAGE -> Environment.DIRECTORY_PICTURES
    } + "/$FOLDER/"

    /** An incoming file after its name is cleaned up and its type worked out. */
    data class Incoming(val name: String, val mime: String, val kind: MediaKind)

    /** Checks an incoming file; throws [Rejected] unless it is a photo, video or song. */
    fun prepare(rawName: String, rawMime: String?): Incoming {
        val name = sanitize(rawName)
        // Senders often give a generic type (octet-stream, form data); then trust the file extension.
        val mime = rawMime?.substringBefore(';')?.trim()?.takeIf { kindOf(it) != null }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: ""
        val kind = kindOf(mime) ?: throw Rejected("Only photos, videos and music can be added")
        return Incoming(name, mime, kind)
    }

    /** Reads exactly [length] bytes from [input] into a new media file on this phone. */
    fun save(file: Incoming, length: Long, input: InputStream) {
        if (Build.VERSION.SDK_INT >= 29) saveViaMediaStore(file.name, file.mime, file.kind, length, input)
        else saveAsFile(file.name, file.mime, file.kind, length, input)
    }

    private fun saveViaMediaStore(name: String, mime: String, kind: MediaKind, length: Long, input: InputStream) {
        val collection = when (kind) {
            MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folderFor(kind))
            put(MediaStore.MediaColumns.IS_PENDING, 1) // hidden from other apps until fully written
        }) ?: throw IllegalStateException("Cannot create $name")
        try {
            resolver.openOutputStream(uri)?.use { copyExactly(input, it, length) } ?: throw IllegalStateException("Cannot write $name")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Android 8 and 9 have no RELATIVE_PATH: write the file directly and ask the media scanner to index it. */
    @Suppress("DEPRECATION")
    private fun saveAsFile(name: String, mime: String, kind: MediaKind, length: Long, input: InputStream) {
        val dir = File(Environment.getExternalStorageDirectory(), folderFor(kind)).apply { mkdirs() }
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var file = File(dir, name)
        var n = 1
        while (file.exists()) file = File(dir, "$base (${n++})$ext")
        try {
            file.outputStream().use { copyExactly(input, it, length) }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf(mime), null)
    }

    private fun copyExactly(input: InputStream, output: java.io.OutputStream, length: Long) {
        val buf = ByteArray(64 * 1024)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw java.io.EOFException("Upload ended early")
            output.write(buf, 0, n)
            left -= n
        }
    }

    private fun kindOf(mime: String): MediaKind? = when {
        mime.startsWith("video/") -> MediaKind.VIDEO
        mime.startsWith("audio/") -> MediaKind.AUDIO
        mime.startsWith("image/") -> MediaKind.IMAGE
        else -> null
    }

    private fun sanitize(raw: String): String =
        raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("""[\x00-\x1f:*?"<>|]"""), "_")
            .trim().trimStart('.')
            .take(150)
            .ifEmpty { "media" }

    companion object {
        const val FOLDER = "MediaCluster"
    }
}
