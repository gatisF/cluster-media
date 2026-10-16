package com.mediacluster

import android.content.Context
import com.mediacluster.library.DriveConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** User options kept in SharedPreferences (private to the app). */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cluster", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val webPageState = MutableStateFlow(prefs.getBoolean(KEY_WEB_PAGE, false))
    private val driveState = MutableStateFlow(
        prefs.getString(KEY_DRIVE, null)?.let { runCatching { json.decodeFromString<DriveConfig>(it) }.getOrNull() }
    )

    /** Serve the library as a web page at "/" so browsers without the app can watch. Off by default. */
    val webPage: StateFlow<Boolean> = webPageState.asStateFlow()

    /** The network drive (e.g. the router's USB disk) this phone adds to the cluster, if any. */
    val drive: StateFlow<DriveConfig?> = driveState.asStateFlow()

    fun setWebPage(on: Boolean) {
        prefs.edit().putBoolean(KEY_WEB_PAGE, on).apply()
        webPageState.value = on
    }

    fun setDrive(config: DriveConfig?) {
        prefs.edit().apply {
            if (config == null) remove(KEY_DRIVE) else putString(KEY_DRIVE, json.encodeToString(config))
        }.apply()
        driveState.value = config
    }

    private companion object {
        const val KEY_WEB_PAGE = "webPage"
        const val KEY_DRIVE = "networkDrive"
    }
}
