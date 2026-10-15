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
import android.os.StatFs
import android.util.Log
import com.mediacluster.discovery.PeerDiscovery
import com.mediacluster.dlna.ContentDirectory
import com.mediacluster.dlna.SsdpServer
import com.mediacluster.library.ClusterIndex
import com.mediacluster.library.FolderSelection
import com.mediacluster.library.MediaItem
import com.mediacluster.library.MediaScanner
import com.mediacluster.library.PeerInfo
import com.mediacluster.server.PeerHttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    private val index get() = Cluster.index

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        peerId = Cluster.peerId(this)
        scanner = MediaScanner(this, peerId)

        val udn = "uuid:${UUID.nameUUIDFromBytes("mediacluster".toByteArray())}" // same on every phone: TV sees one server
        val cds = ContentDirectory(udn) { "MediaCluster (${index.snapshot.value.peers.size} phones)" }
        val settings = Cluster.settings(this)
        server = PeerHttpServer(this, PORT, ::selfInfo, { localItems }, index, cds, { settings.webPage.value }) { scanner.uriFor(it) }
        server.start(5_000, false)

        ssdp = SsdpServer(udn, { "http://${localIp()}:$PORT/dlna/device.xml" }, wifiInterface())
        multicastLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("mediacluster").apply { setReferenceCounted(false); acquire() }

        discovery = PeerDiscovery(this, peerId, PORT,
            onPeerFound = { id, host, port -> knownPeers[id] = host to port; scope.launch { refreshPeer(id) } },
            onPeerLost = { id -> knownPeers.remove(id); index.remove(id) },
        )
        discovery.start()

        scope.launch {
            val folders = Cluster.folders(this@ClusterService).shared
            while (isActive) {
                val shared = folders.value
                localItems = runCatching { scanner.scan(shared) }.getOrElse { Log.w(TAG, "Scan failed", it); emptyList() }
                index.put(selfInfo(), localItems)
                knownPeers.keys.forEach { refreshPeer(it) }
                updateLeadership()
                // Rescan on the regular tick, or straight away when the user changes which folders are shared.
                withTimeoutOrNull(REFRESH_MS) { folders.first { it != shared } }
            }
        }
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
        private const val CHANNEL = "cluster"
        private const val TAG = "ClusterService"
    }
}

/** Process-wide state shared between the service and the UI. */
object Cluster {
    val index = ClusterIndex()

    @Volatile private var folderSelection: FolderSelection? = null
    @Volatile private var appSettings: AppSettings? = null

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
