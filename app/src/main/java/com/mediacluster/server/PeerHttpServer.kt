package com.mediacluster.server

import android.content.Context
import android.os.ParcelFileDescriptor
import com.mediacluster.dlna.ContentDirectory
import com.mediacluster.library.ClusterIndex
import com.mediacluster.library.MediaItem
import com.mediacluster.library.PeerInfo
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Every phone runs this server. It answers three kinds of request:
 *  - /api/...   JSON used by other phones to build the shared library
 *  - /media/ID  the file itself, with HTTP Range support so TVs can seek
 *  - /dlna/...  UPnP MediaServer endpoints (only used on the leader phone)
 *  - /          a web page of the whole library for browsers, when the user has turned it on
 */
class PeerHttpServer(
    private val context: Context,
    port: Int,
    private val selfInfo: () -> PeerInfo,
    private val localItems: () -> List<MediaItem>,
    private val index: ClusterIndex,
    private val contentDirectory: ContentDirectory,
    private val webPageEnabled: () -> Boolean,
    private val uriFor: (MediaItem) -> android.net.Uri,
) : NanoHTTPD(port) {

    private val json = Json { ignoreUnknownKeys = true }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        return when {
            path == "/" && webPageEnabled() ->
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", WebPage.render(index.snapshot.value))
            path == "/api/info" -> jsonResponse(json.encodeToString(selfInfo()))
            path == "/api/library" -> jsonResponse(json.encodeToString(localItems()))
            path.startsWith("/media/") -> serveMedia(session, path.removePrefix("/media/").toLongOrNull())
            path == "/dlna/device.xml" -> xml(contentDirectory.deviceDescription())
            path == "/dlna/cds.xml" -> xml(ContentDirectory.SCPD)
            path == "/dlna/cm.xml" -> xml(ContentDirectory.CM_SCPD)
            path == "/dlna/control/cds" -> {
                val body = readBody(session)
                xml(contentDirectory.handleControl(body, index.snapshot.value))
            }
            path == "/dlna/control/cm" -> xml(ContentDirectory.connectionManagerResponse(readBody(session)))
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveMedia(session: IHTTPSession, localId: Long?): Response {
        val item = localItems().firstOrNull { it.localId == localId }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No such file")
        val pfd = context.contentResolver.openFileDescriptor(uriFor(item), "r")
            ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Cannot open")
        val total = pfd.statSize
        // Closing the stream also closes the descriptor once NanoHTTPD finishes sending.
        val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)

        val range = session.headers["range"]?.let(::parseRange)
        val response = if (range == null) {
            newFixedLengthResponse(Response.Status.OK, item.mimeType, stream, total)
        } else {
            val start = range.first
            val end = (range.second ?: (total - 1)).coerceAtMost(total - 1)
            if (start >= total || start > end) {
                stream.close()
                return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                    .apply { addHeader("Content-Range", "bytes */$total") }
            }
            stream.channel.position(start)
            newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, item.mimeType, stream, end - start + 1)
                .apply { addHeader("Content-Range", "bytes $start-$end/$total") }
        }
        return response.apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader("transferMode.dlna.org", "Streaming")
            addHeader("contentFeatures.dlna.org", ContentDirectory.DLNA_FEATURES)
        }
    }

    /** "bytes=START-" or "bytes=START-END". Suffix ranges ("bytes=-N") are treated as unsupported. */
    private fun parseRange(header: String): Pair<Long, Long?>? {
        val spec = header.removePrefix("bytes=").split(",").first().trim()
        val (a, b) = spec.split("-", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val start = a.toLongOrNull() ?: return null
        return start to b.toLongOrNull()
    }

    private fun readBody(session: IHTTPSession): String {
        val len = session.headers["content-length"]?.toIntOrNull() ?: return ""
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = session.inputStream.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun jsonResponse(body: String) = newFixedLengthResponse(Response.Status.OK, "application/json", body)
    private fun xml(body: String) = newFixedLengthResponse(Response.Status.OK, "text/xml; charset=\"utf-8\"", body)
}
