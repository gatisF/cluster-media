package com.mediacluster.server

import android.content.Context
import android.os.ParcelFileDescriptor
import com.mediacluster.dlna.ContentDirectory
import com.mediacluster.library.ClusterIndex
import com.mediacluster.library.MediaItem
import com.mediacluster.library.MediaWriter
import com.mediacluster.library.Uploader
import com.mediacluster.library.PeerInfo
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream

/**
 * Every phone runs this server. It answers three kinds of request:
 *  - /api/...   JSON used by other phones to build the shared library; POST /api/upload adds a file
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
    private val onUpload: (name: String, mime: String?, length: Long, input: InputStream) -> Unit,
    private val onRefresh: () -> Unit,
    private val uriFor: (MediaItem) -> android.net.Uri,
) : NanoHTTPD(port) {

    private val json = Json { ignoreUnknownKeys = true }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        return when {
            path == "/" && webPageEnabled() ->
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", WebPage.render(index.snapshot.value))
            path == "/api/upload" -> cors(session) ?: receiveUpload(session)
            path == "/api/refresh" -> cors(session) ?: run { onRefresh(); withCors(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")) }
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

    /**
     * Body is the raw file; ?name= and ?mime= describe it. Browsers on the web page upload to whichever
     * phone has the most room, which may be a different phone than the page came from, hence CORS.
     */
    private fun receiveUpload(session: IHTTPSession): Response {
        if (session.method != Method.POST) return error(Response.Status.METHOD_NOT_ALLOWED, "Use POST")
        val length = session.headers["content-length"]?.toLongOrNull()
            ?: return error(Response.Status.LENGTH_REQUIRED, "Content-Length required")
        val name = session.parameters["name"]?.firstOrNull() ?: "media"
        val mime = session.parameters["mime"]?.firstOrNull()?.takeIf { it.isNotBlank() } ?: session.headers["content-type"]
        if (length > selfInfo().freeBytes - Uploader.RESERVE_BYTES) return error(Response.Status.PAYLOAD_TOO_LARGE, "Not enough free space on this phone")
        return try {
            onUpload(name, mime, length, session.inputStream)
            withCors(newFixedLengthResponse(Response.Status.CREATED, MIME_PLAINTEXT, "Added $name"))
        } catch (e: MediaWriter.Rejected) {
            error(Response.Status.UNSUPPORTED_MEDIA_TYPE, e.message ?: "Not media")
        } catch (e: Exception) {
            error(Response.Status.INTERNAL_ERROR, "Could not save $name: ${e.message}")
        }
    }

    /** Answers a CORS preflight, or returns null for a normal request. */
    private fun cors(session: IHTTPSession): Response? =
        if (session.method != Method.OPTIONS) null
        else withCors(newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")).apply {
            addHeader("Access-Control-Allow-Methods", "POST, OPTIONS")
            addHeader("Access-Control-Allow-Headers", "Content-Type")
            addHeader("Access-Control-Max-Age", "600")
        }

    private fun withCors(r: Response) = r.apply { addHeader("Access-Control-Allow-Origin", "*") }

    // The request body may be unread, so close the connection rather than parse leftovers as the next request.
    private fun error(status: Response.Status, message: String) =
        withCors(newFixedLengthResponse(status, MIME_PLAINTEXT, message)).apply { addHeader("Connection", "close") }

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
