package com.mediacluster.library

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A folder that holds media, e.g. "DCIM/Camera/", with how many media files are in it and below it. */
data class MediaFolder(val path: String, val itemCount: Int) {
    val depth: Int get() = path.trimEnd('/').count { it == '/' }
}

/**
 * What this phone shares with the cluster.
 * [mediaFolders] are MediaStore folders, each including its subfolders; null shares every folder.
 * [trees] are folders picked with the system folder picker (document tree URIs), shared in full,
 * which also reaches files MediaStore does not index, such as hidden or .nomedia folders.
 */
data class SharedFolders(val mediaFolders: Set<String>? = null, val trees: Set<String> = emptySet()) {
    fun includes(folder: String): Boolean = mediaFolders == null || mediaFolders.any { it.covers(folder) }
}

/** "DCIM/" covers "DCIM/Camera/". The storage root ("") only covers itself, not every folder. */
fun String.covers(folder: String): Boolean = this == folder || (isNotEmpty() && folder.startsWith(this))

/** Keeps [SharedFolders] in SharedPreferences. */
class FolderSelection(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cluster", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val shared: StateFlow<SharedFolders> = state.asStateFlow()

    fun set(value: SharedFolders) {
        prefs.edit().apply {
            if (value.mediaFolders == null) remove(KEY_FOLDERS) else putStringSet(KEY_FOLDERS, value.mediaFolders)
            putStringSet(KEY_TREES, value.trees)
        }.apply()
        state.value = value
    }

    private fun load() = SharedFolders(
        mediaFolders = prefs.getStringSet(KEY_FOLDERS, null)?.toSet(),
        trees = prefs.getStringSet(KEY_TREES, null)?.toSet().orEmpty(),
    )

    private companion object {
        const val KEY_FOLDERS = "sharedFolders"
        const val KEY_TREES = "sharedTrees"
    }
}
