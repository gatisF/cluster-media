package com.mediacluster.library

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.util.UUID

/** Reads this phone's media from MediaStore and from picked folders. Nothing is copied; files are served in place. */
class MediaScanner(private val context: Context, private val peerId: String) {

    /** localId -> document URI for files found in picked folders during the last [scan]. */
    @Volatile private var treeFiles: Map<Long, Uri> = emptyMap()

    fun scan(shared: SharedFolders = SharedFolders()): List<MediaItem> {
        val fromStore = MediaKind.entries.flatMap { kind ->
            query(kind).filter { (folder, _) -> shared.includes(folder) }.map { it.second }
        }
        // A picked folder may overlap a shared MediaStore folder or another picked folder: list each file once.
        val seen = fromStore.mapTo(HashSet()) { it.title to it.sizeBytes }
        val files = HashMap<Long, Uri>()
        val fromTrees = shared.trees.flatMap { tree ->
            runCatching { walkTree(Uri.parse(tree), files) }
                .getOrElse { Log.w(TAG, "Cannot read picked folder $tree", it); emptyList() }
        }.filter { seen.add(it.title to it.sizeBytes) }
        treeFiles = files
        return fromStore + fromTrees
    }

    /**
     * Every MediaStore folder that holds media, plus its parent folders, for the folder picker.
     * Counts include subfolders, and the list is sorted so each folder comes right after its parent.
     */
    fun folders(): List<MediaFolder> {
        val counts = HashMap<String, Int>()
        MediaKind.entries.flatMap { query(it) }.forEach { (folder, _) ->
            ancestorsAndSelf(folder).forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        return counts.map { (path, count) -> MediaFolder(path, count) }.sortedBy { it.path.lowercase() }
    }

    fun uriFor(item: MediaItem): Uri =
        treeFiles[item.localId] ?: ContentUris.withAppendedId(collection(item.kind), item.localId)

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

    /** All media files under a picked folder, recursively. Registers each file's URI in [files]. */
    private fun walkTree(tree: Uri, files: MutableMap<Long, Uri>): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree)))
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
        while (pending.isNotEmpty()) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, pending.removeFirst())
            context.contentResolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0)
                    val name = c.getString(1) ?: "Untitled"
                    val mime = c.getString(2).orEmpty()
                    if (mime == Document.MIME_TYPE_DIR) {
                        pending += docId
                    } else {
                        val type = if (mime.isEmpty() || mime == "application/octet-stream") mimeFromName(name) else mime
                        val kind = kindOf(type)
                        if (kind != null) {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                            val localId = treeLocalId(uri)
                            files[localId] = uri
                            out += MediaItem(
                                id = "$peerId:$localId",
                                peerId = peerId,
                                localId = localId,
                                title = name,
                                mimeType = type,
                                sizeBytes = if (c.isNull(3)) 0L else c.getLong(3),
                                durationMs = 0L,
                                kind = kind,
                            )
                        }
                    }
                }
            }
        }
        return out
    }

    private fun mimeFromName(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: ""

    private fun kindOf(mime: String): MediaKind? = when {
        mime.startsWith("video/") -> MediaKind.VIDEO
        mime.startsWith("audio/") -> MediaKind.AUDIO
        mime.startsWith("image/") -> MediaKind.IMAGE
        else -> null
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

    /** "DCIM/Camera/" -> ["DCIM/", "DCIM/Camera/"]. The storage root stays on its own. */
    private fun ancestorsAndSelf(folder: String): List<String> {
        if (folder.isEmpty()) return listOf("")
        val parts = folder.trimEnd('/').split('/')
        return parts.indices.map { i -> parts.take(i + 1).joinToString("/") + "/" }
    }

    companion object {
        private const val TAG = "MediaScanner"

        @Suppress("DEPRECATION")
        private val FOLDER_COLUMN: String =
            if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA

        /** Stable negative id for a picked-folder file, so it never clashes with MediaStore's positive ids. */
        fun treeLocalId(uri: Uri): Long =
            -(UUID.nameUUIDFromBytes(uri.toString().toByteArray()).mostSignificantBits and Long.MAX_VALUE) - 1

        /** Readable name for a picked folder, e.g. "primary:Movies/Kids" -> "Movies/Kids". */
        fun treeLabel(tree: String): String = runCatching {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(tree))
            val path = id.substringAfter(':', id)
            val volume = id.substringBefore(':', "")
            when {
                volume == "primary" || volume.isEmpty() -> path.ifEmpty { "Internal storage" }
                else -> "SD card: ${path.ifEmpty { "/" }}"
            }
        }.getOrDefault(tree)
    }
}
