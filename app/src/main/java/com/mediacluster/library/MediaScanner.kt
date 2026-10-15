package com.mediacluster.library

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Reads this phone's media from MediaStore. Nothing is copied; files are served in place. */
class MediaScanner(private val context: Context, private val peerId: String) {

    /** Media in the [folders] being shared; null means every folder. */
    fun scan(folders: Set<String>? = null): List<MediaItem> =
        MediaKind.entries.flatMap { kind -> query(kind).filter { folders == null || it.first in folders }.map { it.second } }

    /** Every folder that holds media on this phone, for the folder picker. */
    fun folders(): List<MediaFolder> =
        MediaKind.entries.flatMap { query(it) }
            .groupingBy { it.first }.eachCount()
            .map { (path, count) -> MediaFolder(path, count) }
            .sortedBy { it.path.lowercase() }

    fun uriFor(kind: MediaKind, localId: Long): Uri = ContentUris.withAppendedId(collection(kind), localId)

    private fun collection(kind: MediaKind) = when (kind) {
        MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        MediaKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    /** Each item paired with the folder it lives in. */
    private fun query(kind: MediaKind): List<Pair<String, MediaItem>> {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(FOLDER_COLUMN)
            if (kind != MediaKind.IMAGE) add(MediaStore.MediaColumns.DURATION)
        }.toTypedArray()
        val out = mutableListOf<Pair<String, MediaItem>>()
        context.contentResolver.query(collection(kind), projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val folderCol = c.getColumnIndexOrThrow(FOLDER_COLUMN)
            val durCol = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
            while (c.moveToNext()) {
                val localId = c.getLong(idCol)
                out += folderOf(c, folderCol) to MediaItem(
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

    /** Folder relative to the storage root with a trailing slash, e.g. "DCIM/Camera/". */
    private fun folderOf(c: Cursor, col: Int): String {
        val raw = c.getString(col) ?: return ""
        if (Build.VERSION.SDK_INT >= 29) return raw
        // Before Android 10 there is no RELATIVE_PATH, so derive it from the absolute file path.
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory().path.trimEnd('/') + "/"
        val parent = File(raw).parent ?: return ""
        return "$parent/".removePrefix(root)
    }

    private companion object {
        @Suppress("DEPRECATION")
        val FOLDER_COLUMN: String =
            if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
    }
}
