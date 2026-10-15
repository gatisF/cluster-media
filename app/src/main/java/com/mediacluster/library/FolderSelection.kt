package com.mediacluster.library

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A folder that holds media, e.g. "DCIM/Camera/", with how many media files are in it. */
data class MediaFolder(val path: String, val itemCount: Int)

/**
 * Which folders this phone shares with the cluster, kept in SharedPreferences.
 * [selected] is null when every folder is shared (the default, which also picks up new folders).
 */
class FolderSelection(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cluster", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val selected: StateFlow<Set<String>?> = state.asStateFlow()

    fun set(folders: Set<String>?) {
        prefs.edit().apply {
            if (folders == null) remove(KEY) else putStringSet(KEY, folders)
        }.apply()
        state.value = folders
    }

    private fun load(): Set<String>? = prefs.getStringSet(KEY, null)?.toSet()

    private companion object {
        const val KEY = "sharedFolders"
    }
}
