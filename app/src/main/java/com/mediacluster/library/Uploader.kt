package com.mediacluster.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mediacluster.Cluster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Adds files the user picked to the cluster: each one is copied to the phone with the most free space
 * (which may be this one), where it lands in a "MediaCluster" folder and joins the shared library.
 */
class Uploader(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Mutex()
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val state = MutableStateFlow<String?>(null)

    /** A line for the UI about the current or last batch, or null when there is nothing to say. */
    val status: StateFlow<String?> = state.asStateFlow()

    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            queue.withLock {
                // Shared from another app right after launch, the service may not have found any phone yet.
                state.value = "Looking for phones…"
                withTimeoutOrNull(20_000) { Cluster.index.snapshot.first { it.peers.isNotEmpty() } }
                var added = 0
                val problems = mutableListOf<String>()
                uris.forEachIndexed { i, uri ->
                    val prefix = if (uris.size > 1) "${i + 1} of ${uris.size}: " else ""
                    runCatching { upload(uri, prefix) }
                        .onSuccess { added++ }
                        .onFailure { problems += it.message ?: "failed" }
                }
                Cluster.requestRescan()
                state.value = buildString {
                    append("Added $added file${if (added == 1) "" else "s"} to the cluster")
                    if (problems.isNotEmpty()) append(". Not added: ").append(problems.joinToString("; "))
                }
                delay(if (problems.isEmpty()) 6_000 else 15_000)
                state.value = null
            }
        }
    }

    private fun upload(uri: Uri, prefix: String) {
        val resolver = appContext.contentResolver
        var name = "media"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size < 0) size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (size < 0) throw IllegalStateException("$name: size unknown")
        val mime = resolver.getType(uri) ?: ""

        val peers = Cluster.index.snapshot.value.peers.values
        val target = peers.filter { it.freeBytes - RESERVE_BYTES > size }.maxByOrNull { it.freeBytes }
            ?: throw IllegalStateException("$name: no phone has room")

        val body = object : RequestBody() {
            override fun contentType() = mime.toMediaTypeOrNull()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                val input = resolver.openInputStream(uri) ?: throw IllegalStateException("$name: cannot read")
                input.use {
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    var shown = -1
                    while (true) {
                        val n = it.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        sent += n
                        val pct = if (size > 0) (sent * 100 / size).toInt() else 100
                        if (pct != shown) {
                            shown = pct
                            state.value = "Adding $prefix$name to ${target.name}… $pct%"
                        }
                    }
                }
            }
        }
        val url = "http://${target.host}:${target.port}/api/upload" +
            "?name=${URLEncoder.encode(name, "UTF-8")}&mime=${URLEncoder.encode(mime, "UTF-8")}"
        http.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("$name: ${r.body?.string()?.takeIf { it.isNotBlank() } ?: "HTTP ${r.code}"}")
        }
    }

    companion object {
        /** Space left free on every phone, so adding media never fills one up completely. */
        const val RESERVE_BYTES = 200L * 1024 * 1024
    }
}
