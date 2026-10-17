package com.mediacluster.server

import com.mediacluster.dlna.ContentDirectory
import com.mediacluster.library.ClusterIndex
import com.mediacluster.library.MediaItem
import com.mediacluster.library.MediaSource
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
 *  - /thumb/ID  a small JPEG preview, for the web page's photo grid and TVs
 *  - /dlna/...  UPnP MediaServer endpoints (only used on the leader phone)
 *  - /          a web page of the whole library for browsers, when the user has turned it on
 */
class PeerHttpServer(
    port: Int,
    private val selfInfo: () -> PeerInfo,
    private val localItems: () -> List<MediaItem>,
    private val index: ClusterIndex,
    private val contentDirectory: ContentDirectory,
    private val webPageEnabled: () -> Boolean,
    private val onUpload: (name: String, mime: String?, length: Long, input: InputStream, driveId: String?) -> Unit,
    private val onRefresh: () -> Unit,
    private val open: (MediaItem) -> MediaSource?,
    private val thumbnail: (MediaItem) -> ByteArray?,
) : NanoHTTPD(port) {

    private val json = Json { ignoreUnknownKeys = true }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        return when {
            path == "/" && webPageEnabled() ->
                // The library changes as phones come and go: always ask again (NanoHTTPD gzips text for us).
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", WebPage.render(index.snapshot.value))
                    .apply { addHeader("Cache-Control", "no-cache") }
            path == "/api/upload" -> cors(session) ?: receiveUpload(session)
            path == "/api/refresh" -> cors(session) ?: run { onRefresh(); withCors(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")) }
            path == "/api/info" -> jsonResponse(json.encodeToString(selfInfo()))
            path == "/api/library" -> jsonResponse(json.encodeToString(localItems()))
            path.startsWith("/media/") -> serveMedia(session, path.removePrefix("/media/").toLongOrNull())
            path.startsWith("/thumb/") -> serveThumbnail(session, path.removePrefix("/thumb/").toLongOrNull())
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
        val driveId = session.parameters["drive"]?.firstOrNull()
        val self = selfInfo()
        val free = if (driveId == null) self.freeBytes
        else self.drives.firstOrNull { it.id == driveId }?.freeBytes ?: return error(Response.Status.NOT_FOUND, "That drive is not connected through this phone")
        if (length > free - Uploader.RESERVE_BYTES) return error(Response.Status.PAYLOAD_TOO_LARGE, "Not enough free space")
        return try {
            onUpload(name, mime, length, session.inputStream, driveId)
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

    private fun serveThumbnail(session: IHTTPSession, localId: Long?): Response {
        val item = localItems().firstOrNull { it.localId == localId }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No such file")
        val tag = "\"t${item.localId}-${item.sizeBytes}\""
        notModified(session, tag)?.let { return it }
        val jpeg = runCatching { thumbnail(item) }.getOrNull()
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No preview")
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", jpeg.inputStream(), jpeg.size.toLong())
            .apply { cacheFor(this, tag, THUMB_MAX_AGE) }
    }

    /**
     * Files are named by id and checked by size, so browsers may keep them: a revisit reuses the copy
     * (or gets a tiny 304) instead of downloading every photo again.
     */
    private fun cacheFor(r: Response, tag: String, maxAge: Int) {
        r.addHeader("Cache-Control", "private, max-age=$maxAge")
        r.addHeader("ETag", tag)
    }

    private fun notModified(session: IHTTPSession, tag: String): Response? =
        if (session.headers["if-none-match"]?.split(',')?.any { it.trim() == tag } == true)
            newFixedLengthResponse(Response.Status.NOT_MODIFIED, MIME_PLAINTEXT, "").apply { addHeader("ETag", tag) }
        else null

    private fun serveMedia(session: IHTTPSession, localId: Long?): Response {
        val item = localItems().firstOrNull { it.localId == localId }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No such file")
        val tag = "\"m${item.localId}-${item.sizeBytes}\""
        notModified(session, tag)?.let { return it }
        // A phone file or a file on the network drive; either way NanoHTTPD closes the stream when done.
        val source = runCatching { open(item) }.getOrNull()
            ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Cannot open")
        val total = source.length

        val range = session.headers["range"]?.let(::parseRange)
        val response = if (range == null) {
            newFixedLengthResponse(Response.Status.OK, item.mimeType, source.openAt(0), total)
        } else {
            val start = range.first
            val end = (range.second ?: (total - 1)).coerceAtMost(total - 1)
            if (start >= total || start > end) {
                source.openAt(0).close()
                return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                    .apply { addHeader("Content-Range", "bytes */$total") }
            }
            newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, item.mimeType, source.openAt(start), end - start + 1)
                .apply { addHeader("Content-Range", "bytes $start-$end/$total") }
        }
        return response.apply {
            cacheFor(this, tag, MEDIA_MAX_AGE)
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

    private companion object {
        const val MEDIA_MAX_AGE = 24 * 3600
        const val THUMB_MAX_AGE = 7 * 24 * 3600
    }

    private fun jsonResponse(body: String) = newFixedLengthResponse(Response.Status.OK, "application/json", body)
    private fun xml(body: String) = newFixedLengthResponse(Response.Status.OK, "text/xml; charset=\"utf-8\"", body)
}
