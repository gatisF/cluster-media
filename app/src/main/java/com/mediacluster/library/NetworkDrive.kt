package com.mediacluster.library

import android.util.Log
import android.webkit.MimeTypeMap
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.internal.AllocInfo
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbFile
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.util.Properties
import java.util.UUID

/** Where a network drive is and how to log in. Blank [user] means guest access. */
@Serializable
data class DriveConfig(
    val host: String,
    val port: Int = 445,
    val share: String,
    val folder: String = "",
    val user: String = "",
    val password: String = "",
    val name: String = "Router drive",
) {
    /** Same drive and folder give the same id on every phone, so the cluster can spot duplicates. */
    val id: String get() = "drive-" + UUID.nameUUIDFromBytes("${host.lowercase()}:$port/${share.lowercase()}/${folder.lowercase()}".toByteArray()).toString().take(8)

    /** "\\192.168.1.1\usb\Media", the way Windows writes it. */
    val address: String get() = "\\\\" + host + (if (port != 445) ":$port" else "") + "\\" + share + (if (folder.isEmpty()) "" else "\\" + folder.replace('/', '\\'))

    /** smb:// URL of the shared folder, ending in "/". */
    val url: String get() = "smb://$host" + (if (port != 445) ":$port" else "") + "/$share/" + (if (folder.isEmpty()) "" else "${folder.trim('/')}/")

    companion object {
        /** Accepts \\host\share\folder, smb://host/share/folder or host/share/folder; host may carry :port. */
        fun parse(address: String, user: String, password: String, name: String): DriveConfig? {
            val (host, port, parts) = split(address) ?: return null
            if (parts.isEmpty()) return null
            return DriveConfig(host, port, parts[0], parts.drop(1).joinToString("/"), user.trim(), password, name.trim().ifEmpty { "Router drive" })
        }

        /** Host, port and the path parts after it, or null when there is no host. */
        fun split(address: String): Triple<String, Int, List<String>>? {
            val parts = address.trim().removePrefix("smb:").replace('\\', '/').trim('/').split('/').filter { it.isNotBlank() }
            val host = parts.firstOrNull()?.substringBefore(':')?.takeIf { it.isNotBlank() } ?: return null
            val port = parts[0].substringAfter(':', "").toIntOrNull() ?: 445
            return Triple(host, port, parts.drop(1))
        }
    }
}

/** SMB failures in words a user can act on. */
fun explainDriveError(e: Throwable, guest: Boolean, host: String, share: String? = null, folder: String = ""): String {
    val text = generateSequence(e) { it.cause }.joinToString(" ") { "${it.javaClass.simpleName} ${it.message}" }.lowercase()
    return when {
        guest && ("logon failure" in text || "access is denied" in text || "access denied" in text) ->
            "the router wants a username and password (often the router's own login)"
        "plain text password" in text -> "the router asks for plain-text passwords, which is not supported"
        "logon failure" in text || "bad password" in text ->
            "wrong username or password. Use an account from the router's USB sharing page, not the Wi-Fi password"
        "access is denied" in text || "access denied" in text -> "this user may not open it"
        "network name cannot be found" in text || "bad_network_name" in text -> "no shared folder called \"$share\" on $host"
        "cannot find the" in text || "not found" in text -> "folder \"$folder\" not found on the drive"
        "failed to connect" in text || "connectexception" in text || "timeout" in text || "timed out" in text || "unknownhost" in text ->
            "can't reach $host. Is the drive plugged in and file sharing (SMB/Samba) on in the router?"
        else -> e.message ?: e.javaClass.simpleName
    }
}

/** A file on the drive opened for streaming: its length, and a way to read from any offset. */
class MediaSource(val length: Long, val openAt: (offset: Long) -> InputStream)

/**
 * A disk shared over SMB, usually a USB drive plugged into the router. This phone indexes it,
 * streams its files to the cluster and stores added media on it (in a "MediaCluster" folder).
 * Speaks SMB1 as well as SMB2/3, since many routers still only offer SMB1.
 */
class NetworkDrive(val config: DriveConfig, private val peerId: String) : AutoCloseable {

    @Volatile private var ctx: CIFSContext? = null

    /** Logs in on first use, with the first login method the server accepts. */
    private val context: CIFSContext
        get() = ctx ?: synchronized(this) {
            ctx ?: firstWorkingLogin(config.user, config.password) { SmbFile(config.url, it).use { f -> f.listFiles() } }.also { ctx = it }
        }

    /** localId -> smb:// URL, for files found by the last [scan]. */
    @Volatile private var urls: Map<Long, String> = emptyMap()
    @Volatile private var indexedBytes = 0L

    val id: String get() = config.id

    fun info(): DriveInfo = SmbFile(config.url, context).use { root ->
        val free = root.diskFreeSpace
        DriveInfo(id, config.name, free, capacityOf(root) ?: (free + indexedBytes))
    }

    /** Every photo, video and song in the shared folder and below. */
    fun scan(): List<MediaItem> = try {
        scanAll()
    } catch (e: SmbAuthException) {
        // The password may have changed on the router: log in again next time.
        synchronized(this) { ctx?.let { runCatching { it.close() } }; ctx = null }
        throw e
    }

    private fun scanAll(): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val found = HashMap<Long, String>()
        val pending = ArrayDeque(listOf(config.url))
        SmbFile(config.url, context).use { it.listFiles() } // fails loudly if the share itself is unreachable
        while (pending.isNotEmpty() && out.size < MAX_FILES) {
            val dir = pending.removeFirst()
            val entries = runCatching { SmbFile(dir, context).use { it.listFiles() } }.getOrNull() ?: continue
            for (f in entries) f.use {
                val name = f.name.trimEnd('/')
                if (f.name.endsWith("/")) {
                    if (!name.startsWith("$") && !name.startsWith(".")) pending += f.url.toString()
                } else {
                    val mime = mimeOf(name)
                    val kind = kindOf(mime)
                    if (kind != null) {
                        val url = f.url.toString()
                        val localId = localIdOf(url)
                        found[localId] = url
                        out += MediaItem(
                            id = "$peerId:$localId",
                            peerId = peerId,
                            localId = localId,
                            title = name,
                            mimeType = mime,
                            sizeBytes = f.length(),
                            durationMs = 0L,
                            kind = kind,
                            driveId = id,
                        )
                    }
                }
            }
        }
        urls = found
        indexedBytes = out.sumOf { it.sizeBytes }
        return out
    }

    fun open(item: MediaItem): MediaSource? {
        val url = urls[item.localId] ?: return null
        val length = SmbFile(url, context).use { it.length() }
        return MediaSource(length) { offset ->
            // Random access, so seeking in a video just starts reading at a different offset.
            val file = SmbFile(url, context)
            val raf = file.openRandomAccess("r")
            raf.seek(offset)
            // Each SMB read is a network round trip: read ahead in big pieces on a background thread.
            ReadAheadStream(
                readSource = { b, off, len -> raf.read(b, off, len) },
                closeSource = {
                    runCatching { raf.close() }
                    file.close()
                },
            )
        }
    }

    /** Stores added media in MediaCluster/Videos, /Music or /Photos on the drive. */
    fun save(incoming: MediaWriter.Incoming, length: Long, input: InputStream) {
        val dirUrl = config.url + "${MediaWriter.FOLDER}/${subfolderFor(incoming.kind)}/"
        SmbFile(dirUrl, context).use { if (!it.exists()) it.mkdirs() }
        val base = incoming.name.substringBeforeLast('.')
        val ext = incoming.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 0
        var file: SmbFile
        while (true) {
            file = SmbFile(dirUrl + (if (n == 0) incoming.name else "$base ($n)$ext"), context)
            if (!file.exists()) break
            file.close()
            n++
        }
        file.use { f ->
            try {
                f.openOutputStream().use { out ->
                    val buf = ByteArray(CHUNK)
                    var left = length
                    while (left > 0) {
                        val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (r < 0) throw java.io.EOFException("Upload ended early")
                        out.write(buf, 0, r)
                        left -= r
                    }
                }
            } catch (e: Exception) {
                runCatching { f.delete() }
                throw e
            }
        }
    }

    override fun close() {
        ctx?.let { runCatching { it.close() } }
    }

    /** jcifs-ng only exposes free space; ask for the full allocation info, which includes capacity. */
    private fun capacityOf(root: SmbFile): Long? = runCatching {
        val ensure = SmbFile::class.java.getDeclaredMethod("ensureTreeConnected").apply { isAccessible = true }
        val handle = ensure.invoke(root)
        try {
            val fetch = SmbFile::class.java.getDeclaredMethod("fetchAllocationInfo", handle.javaClass).apply { isAccessible = true }
            (fetch.invoke(root, handle) as AllocInfo).capacity.takeIf { it > 0 }
        } finally {
            handle.javaClass.getMethod("release").invoke(handle)
        }
    }.getOrNull()

    private fun subfolderFor(kind: MediaKind) = when (kind) {
        MediaKind.VIDEO -> "Videos"
        MediaKind.AUDIO -> "Music"
        MediaKind.IMAGE -> "Photos"
    }

    private fun localIdOf(url: String): Long =
        -(UUID.nameUUIDFromBytes("$id:${url.lowercase()}".toByteArray()).mostSignificantBits and Long.MAX_VALUE) - 1

    companion object {
        private const val TAG = "NetworkDrive"
        private const val MAX_FILES = 50_000
        private const val CHUNK = 256 * 1024

        /**
         * Ways to send the password, newest first. Router Samba builds (e.g. Huawei ONTs) often
         * refuse NTLMv2 and answer "logon failure" even when the password is right.
         */
        private enum class Login(val lmCompatibility: Int, val extendedSecurity: Boolean) {
            NTLMV2(3, true),
            NTLMV1(1, true),
            LEGACY(0, false),
        }

        private fun contextFor(user: String, password: String, login: Login): CIFSContext {
            val props = Properties().apply {
                setProperty("jcifs.smb.client.minVersion", "SMB1") // routers often only speak SMB1
                setProperty("jcifs.smb.client.maxVersion", "SMB210")
                setProperty("jcifs.smb.client.responseTimeout", "30000")
                setProperty("jcifs.smb.client.soTimeout", "60000")
                setProperty("jcifs.resolveOrder", "DNS")
                setProperty("jcifs.smb.lmCompatibility", login.lmCompatibility.toString())
                setProperty("jcifs.smb.client.useExtendedSecurity", login.extendedSecurity.toString())
            }
            val base = BaseContext(PropertyConfiguration(props))
            if (user.isEmpty()) return base.withGuestCrendentials()
            val domain = user.substringBefore('\\', "")
            return base.withCredentials(NtlmPasswordAuthenticator(domain, user.substringAfter('\\'), password))
        }

        /** Tries each login method until [probe] gets past the login; returns that method's context. */
        private fun firstWorkingLogin(user: String, password: String, probe: (CIFSContext) -> Unit): CIFSContext {
            val methods = if (user.isEmpty()) listOf(Login.NTLMV2) else Login.entries
            var refused: SmbAuthException? = null
            for (method in methods) {
                val c = contextFor(user, password, method)
                try {
                    probe(c)
                    Log.i(TAG, "Logged in to the drive with $method")
                    return c
                } catch (e: SmbAuthException) {
                    refused = e
                    runCatching { c.close() }
                } catch (e: Exception) {
                    runCatching { c.close() }
                    throw e
                }
            }
            throw refused!!
        }

        /** Names of the disk shares on [host], to help fill in the address. */
        fun listShares(host: String, port: Int, user: String, password: String): List<String> {
            val url = "smb://$host" + (if (port != 445) ":$port" else "") + "/"
            var names = emptyList<String>()
            val c = firstWorkingLogin(user, password) { ctx ->
                names = SmbFile(url, ctx).use { root -> root.listFiles().map { it.name.trimEnd('/') } }
            }
            runCatching { c.close() }
            return names.filterNot { it.endsWith("$") }
        }

        fun mimeOf(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: ""

        fun kindOf(mime: String): MediaKind? = when {
            mime.startsWith("video/") -> MediaKind.VIDEO
            mime.startsWith("audio/") -> MediaKind.AUDIO
            mime.startsWith("image/") -> MediaKind.IMAGE
            else -> null
        }
    }
}
