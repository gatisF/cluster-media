package com.mediacluster.dlna

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * SSDP: how TVs find the media server. Answers M-SEARCH and sends periodic NOTIFY alive.
 * Requires a WifiManager.MulticastLock held by the caller.
 */
class SsdpServer(
    private val udn: String,
    private val locationUrl: () -> String,
    private val iface: NetworkInterface?,
) {
    private var socket: MulticastSocket? = null
    private var jobs = listOf<Job>()

    private val types = listOf(
        "upnp:rootdevice",
        udn,
        "urn:schemas-upnp-org:device:MediaServer:1",
        "urn:schemas-upnp-org:service:ContentDirectory:1",
        "urn:schemas-upnp-org:service:ConnectionManager:1",
    )

    fun start(scope: CoroutineScope) {
        if (socket != null) return
        val s = MulticastSocket(PORT).apply {
            reuseAddress = true
            timeToLive = 4
            joinGroup(InetSocketAddress(GROUP, PORT), iface)
        }
        socket = s
        jobs = listOf(
            scope.launch(Dispatchers.IO) { listen(s) },
            scope.launch(Dispatchers.IO) {
                while (isActive) { notify("ssdp:alive"); delay(30_000) }
            },
        )
    }

    fun stop() {
        runCatching { notify("ssdp:byebye") }
        jobs.forEach { it.cancel() }
        socket?.close()
        socket = null
    }

    private fun listen(s: MulticastSocket) {
        val buf = ByteArray(2048)
        while (!s.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try { s.receive(packet) } catch (e: Exception) { break }
            val msg = String(packet.data, 0, packet.length)
            if (!msg.startsWith("M-SEARCH")) continue
            val st = Regex("(?im)^ST:\\s*(.+)$").find(msg)?.groupValues?.get(1)?.trim() ?: continue
            val matches = if (st == "ssdp:all") types else types.filter { it == st }
            for (t in matches) {
                val reply = "HTTP/1.1 200 OK\r\n" +
                    "CACHE-CONTROL: max-age=1800\r\n" +
                    "EXT:\r\n" +
                    "LOCATION: ${locationUrl()}\r\n" +
                    "SERVER: Android/1 UPnP/1.0 MediaCluster/0.1\r\n" +
                    "ST: $t\r\n" +
                    "USN: ${usn(t)}\r\n\r\n"
                val bytes = reply.toByteArray()
                runCatching { s.send(DatagramPacket(bytes, bytes.size, packet.socketAddress)) }
                    .onFailure { Log.w(TAG, "M-SEARCH reply failed", it) }
            }
        }
    }

    private fun notify(nts: String) {
        val s = socket ?: return
        for (t in types) {
            val msg = "NOTIFY * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "LOCATION: ${locationUrl()}\r\n" +
                "NT: $t\r\n" +
                "NTS: $nts\r\n" +
                "SERVER: Android/1 UPnP/1.0 MediaCluster/0.1\r\n" +
                "USN: ${usn(t)}\r\n\r\n"
            val bytes = msg.toByteArray()
            runCatching { s.send(DatagramPacket(bytes, bytes.size, GROUP, PORT)) }
        }
    }

    private fun usn(type: String) = if (type == udn) udn else "$udn::$type"

    companion object {
        private const val TAG = "SsdpServer"
        private const val PORT = 1900
        private val GROUP: InetAddress = InetAddress.getByName("239.255.255.250")
    }
}
