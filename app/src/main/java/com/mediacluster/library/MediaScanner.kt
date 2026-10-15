package com.mediacluster.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** Reads this phone's media from MediaStore. Nothing is copied; files are served in place. */
class MediaScanner(private val context: Context, private val peerId: String) {

    fun scan(): List<MediaItem> =
        query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaKind.VIDEO) +
            query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaKind.AUDIO) +
            query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaKind.IMAGE)

    fun uriFor(kind: MediaKind, localId: Long): Uri = ContentUris.withAppendedId(collection(kind), localId)

    private fun collection(kind: MediaKind) = when (kind) {
        MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        MediaKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    private fun query(uri: Uri, kind: MediaKind): List<MediaItem> {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            if (kind != MediaKind.IMAGE) add(MediaStore.MediaColumns.DURATION)
        }.toTypedArray()
        val out = mutableListOf<MediaItem>()
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val durCol = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
            while (c.moveToNext()) {
                val localId = c.getLong(idCol)
                out += MediaItem(
                    id = "$peerId:$localId",
                    peerId = peerId,
                    localId = localId,
                    title = c.getString(nameCol) ?: "Untitled",
                    mimeType = c.getString(mimeCol) ?: "application/octet-stream",
                    sizeBytes = c.getLong(sizeCol),
                    durationMs = if (durCol >= 0) c.getLong(durCol) else 0L,
                    kind = kind,
                )
            }
        }
        return out
    }
}
