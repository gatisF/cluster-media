package com.mediacluster

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User options kept in SharedPreferences. */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cluster", Context.MODE_PRIVATE)
    private val webPageState = MutableStateFlow(prefs.getBoolean(KEY_WEB_PAGE, false))

    /** Serve the library as a web page at "/" so browsers without the app can watch. Off by default. */
    val webPage: StateFlow<Boolean> = webPageState.asStateFlow()

    fun setWebPage(on: Boolean) {
        prefs.edit().putBoolean(KEY_WEB_PAGE, on).apply()
        webPageState.value = on
    }

    private companion object {
        const val KEY_WEB_PAGE = "webPage"
    }
}
