package com.mediacluster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.text.format.Formatter
import android.util.Log
import com.mediacluster.discovery.PeerDiscovery
import com.mediacluster.dlna.ContentDirectory
import com.mediacluster.dlna.SsdpServer
import com.mediacluster.library.ClusterIndex
import com.mediacluster.library.DriveConfig
import com.mediacluster.library.DriveInfo
import com.mediacluster.library.FolderSelection
import com.mediacluster.library.MediaItem
import com.mediacluster.library.MediaScanner
import com.mediacluster.library.MediaSource
import com.mediacluster.library.MediaWriter
import com.mediacluster.library.NetworkDrive
import com.mediacluster.library.Thumbnails
import com.mediacluster.library.explainDriveError
import com.mediacluster.library.PeerInfo
import com.mediacluster.library.Uploader
import com.mediacluster.server.PeerHttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Keeps this phone in the cluster while the app is in the background:
 * scans local media, serves it over HTTP, finds other phones, merges their libraries,
 * and (on the leader phone only) advertises the merged library to TVs over DLNA.
 */
class ClusterService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val knownPeers = ConcurrentHashMap<String, Pair<String, Int>>() // peerId -> host, port

    private lateinit var peerId: String
    private lateinit var scanner: MediaScanner
    private lateinit var server: PeerHttpServer
    private lateinit var discovery: PeerDiscovery
    private lateinit var ssdp: SsdpServer
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var localItems: List<MediaItem> = emptyList()

    // The network drive this phone serves to the cluster, if the user set one up here.
    @Volatile private var drive: NetworkDrive? = null
    @Volatile private var driveItems: List<MediaItem> = emptyList()
    @Volatile private var driveInfo: DriveInfo? = null
    private val driveRescan = Channel<Unit>(Channel.CONFLATED)

    private val index get() = Cluster.index

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        peerId = Cluster.peerId(this)
        scanner = MediaScanner(this, peerId)

        val udn = "uuid:${UUID.nameUUIDFromBytes("mediacluster".toByteArray())}" // same on every phone: TV sees one server
        val cds = ContentDirectory(udn) { "MediaCluster (${index.snapshot.value.peers.size} phones)" }
        val settings = Cluster.settings(this)
        val writer = MediaWriter(this)
        val thumbnails = Thumbnails(this)
        server = PeerHttpServer(
            PORT, ::selfInfo, { localItems }, index, cds,
            webPageEnabled = { settings.webPage.value },
            onUpload = { name, mime, length, input, driveId -> receive(writer, name, mime, length, input, driveId) },
            onRefresh = { Cluster.requestRescan(); driveRescan.trySend(Unit) },
            open = { item -> if (item.driveId != null) drive?.takeIf { it.id == item.driveId }?.open(item) else phoneSource(item) },
            thumbnail = { item ->
                if (item.driveId == null) thumbnails.get(item, scanner.uriFor(item), null)
                else drive?.takeIf { it.id == item.driveId }?.let { d -> thumbnails.get(item, null) { d.open(item)!!.openAt(0) } }
            },
        )
        server.start(5_000, false)

        ssdp = SsdpServer(udn, { "http://${localIp()}:$PORT/dlna/device.xml" }, wifiInterface())
        multicastLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("mediacluster").apply { setReferenceCounted(false); acquire() }

        discovery = PeerDiscovery(this, peerId, PORT,
            onPeerFound = { id, host, port -> knownPeers[id] = host to port; scope.launch { refreshPeer(id) } },
            onPeerLost = { id -> knownPeers.remove(id); index.remove(id) },
        )
        discovery.start()

        val folders = Cluster.folders(this).shared
        scope.launch { folders.drop(1).collect { Cluster.requestRescan() } }
        scope.launch {
            while (isActive) {
                val shared = folders.value
                localItems = runCatching { scanner.scan(shared) }.getOrElse { Log.w(TAG, "Scan failed", it); emptyList() } + driveItems
                index.put(selfInfo(), localItems)
                knownPeers.keys.forEach { refreshPeer(it) }
                updateLeadership()
                // Rescan on the regular tick, or straight away when shared folders change or media is added.
                withTimeoutOrNull(REFRESH_MS) { Cluster.rescanRequests.receive() }
            }
        }
        scope.launch { settings.drive.collectLatest { config -> runDrive(config) } }
    }

    /** Keeps the configured network drive indexed: on start, every few minutes, and after media is added to it. */
    private suspend fun runDrive(config: DriveConfig?) {
        drive?.close()
        drive = null; driveItems = emptyList(); driveInfo = null
        Cluster.requestRescan()
        if (config == null) {
            Cluster.driveStatus.value = null
            return
        }
        val d = NetworkDrive(config, peerId).also { drive = it }
        try {
            while (true) {
                if (driveInfo == null) Cluster.driveStatus.value = "Connecting to ${config.address}…"
                try {
                    val items = runInterruptible { d.scan() }
                    val info = runInterruptible { d.info() }
                    driveItems = items; driveInfo = info
                    Cluster.driveStatus.value = "Connected · ${items.size} files · " +
                        "${Formatter.formatFileSize(this, info.freeBytes)} free of ${Formatter.formatFileSize(this, info.totalBytes)}"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Network drive failed", e)
                    driveItems = emptyList(); driveInfo = null
                    Cluster.driveStatus.value = "Can't use the drive: ${explain(e, config)}"
                }
                Cluster.requestRescan()
                withTimeoutOrNull(if (driveInfo == null) DRIVE_RETRY_MS else DRIVE_REFRESH_MS) { driveRescan.receive() }
            }
        } finally {
            d.close()
        }
    }

    /** Turns the common SMB failures into advice. */
    private fun explain(e: Exception, config: DriveConfig): String = explainDriveError(e, config.user.isEmpty(), config.host, config.share, config.folder)

    /** A file on this phone, opened for streaming. */
    private fun phoneSource(item: MediaItem): MediaSource? {
        val pfd = contentResolver.openFileDescriptor(scanner.uriFor(item), "r") ?: return null
        // Closing the stream also closes the descriptor once the server finishes sending.
        return MediaSource(pfd.statSize) { offset -> ParcelFileDescriptor.AutoCloseInputStream(pfd).also { it.channel.position(offset) } }
    }

    /** Stores media another phone or a browser added, on this phone or on its network drive. */
    private fun receive(writer: MediaWriter, name: String, mime: String?, length: Long, input: java.io.InputStream, driveId: String?) {
        val file = writer.prepare(name, mime)
        if (driveId != null) {
            val d = drive?.takeIf { it.id == driveId } ?: throw IllegalStateException("That drive is not connected through this phone")
            d.save(file, length, input)
            driveRescan.trySend(Unit)
            return
        }
        writer.save(file, length, input)
        // Make sure the folder it landed in is shared, or the new file would stay invisible.
        val selection = Cluster.folders(this)
        val current = selection.shared.value
        val folder = writer.folderFor(file.kind)
        if (!current.includes(folder)) selection.set(current.copy(mediaFolders = current.mediaFolders.orEmpty() + folder))
        Cluster.requestRescan()
    }

    private fun refreshPeer(id: String) {
        val (host, port) = knownPeers[id] ?: return
        try {
            val info = json.decodeFromString<PeerInfo>(get("http://$host:$port/api/info"))
            val items = json.decodeFromString<List<MediaItem>>(get("http://$host:$port/api/library"))
            // Trust the address we actually reached, not what the peer thinks its IP is.
            index.put(info.copy(host = host, port = port), items)
        } catch (e: Exception) {
            Log.i(TAG, "Peer $id unreachable, dropping: ${e.message}")
            index.remove(id)
        }
        updateLeadership()
    }

    @Synchronized
    private fun updateLeadership() {
        if (index.snapshot.value.leaderId == peerId) ssdp.start(scope) else ssdp.stop()
    }

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            r.body!!.string()
        }

    private fun selfInfo(): PeerInfo {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        return PeerInfo(
            peerId = peerId,
            name = Build.MODEL,
            host = localIp() ?: "127.0.0.1",
            port = PORT,
            freeBytes = stat.availableBytes,
            totalBytes = stat.totalBytes,
            itemCount = localItems.size,
            drives = listOfNotNull(driveInfo),
        )
    }

    private fun localIp(): String? {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    private fun wifiInterface(): NetworkInterface? {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.getLinkProperties(cm.activeNetwork)?.interfaceName?.let { NetworkInterface.getByName(it) }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Media cluster", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Sharing media with nearby phones")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(1, n)
    }

    override fun onDestroy() {
        ssdp.stop()
        discovery.stop()
        server.stop()
        multicastLock?.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PORT = 8765
        private const val REFRESH_MS = 30_000L
        private const val DRIVE_REFRESH_MS = 10 * 60_000L
        private const val DRIVE_RETRY_MS = 60_000L
        private const val CHANNEL = "cluster"
        private const val TAG = "ClusterService"
    }
}

/** Process-wide state shared between the service and the UI. */
object Cluster {
    val index = ClusterIndex()

    @Volatile private var folderSelection: FolderSelection? = null
    @Volatile private var appSettings: AppSettings? = null
    @Volatile private var uploader: Uploader? = null

    /** What the network drive set up on this phone is doing, for the UI; null when there is none. */
    val driveStatus = MutableStateFlow<String?>(null)

    /** Wakes the service's scan loop early: rescan this phone and refresh every peer. */
    val rescanRequests = Channel<Unit>(Channel.CONFLATED)

    fun requestRescan() {
        rescanRequests.trySend(Unit)
    }

    fun uploader(context: Context): Uploader =
        uploader ?: synchronized(this) {
            uploader ?: Uploader(context).also { uploader = it }
        }

    fun folders(context: Context): FolderSelection =
        folderSelection ?: synchronized(this) {
            folderSelection ?: FolderSelection(context).also { folderSelection = it }
        }

    fun settings(context: Context): AppSettings =
        appSettings ?: synchronized(this) {
            appSettings ?: AppSettings(context).also { appSettings = it }
        }

    fun peerId(context: Context): String {
        val prefs = context.getSharedPreferences("cluster", Context.MODE_PRIVATE)
        return prefs.getString("peerId", null) ?: UUID.randomUUID().toString().take(8).also {
            prefs.edit().putString("peerId", it).apply()
        }
    }
}
