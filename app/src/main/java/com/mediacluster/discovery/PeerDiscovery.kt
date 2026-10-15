package com.mediacluster.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.ArrayDeque

/**
 * Finds other phones running the app on the same Wi-Fi using mDNS (Android NSD).
 * Each phone advertises "_mediacluster._tcp" with its peerId as the service name.
 */
class PeerDiscovery(
    context: Context,
    private val peerId: String,
    private val port: Int,
    private val onPeerFound: (peerId: String, host: String, port: Int) -> Unit,
    private val onPeerLost: (peerId: String) -> Unit,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private val registration = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) { Log.i(TAG, "Advertising as ${info.serviceName}") }
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { Log.w(TAG, "Register failed $code") }
        override fun onServiceUnregistered(info: NsdServiceInfo) {}
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
    }

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) {}
        override fun onDiscoveryStopped(type: String) {}
        override fun onStartDiscoveryFailed(type: String, code: Int) { Log.w(TAG, "Discovery failed $code") }
        override fun onStopDiscoveryFailed(type: String, code: Int) {}
        override fun onServiceFound(info: NsdServiceInfo) {
            if (info.serviceName != peerId) enqueueResolve(info)
        }
        override fun onServiceLost(info: NsdServiceInfo) = onPeerLost(info.serviceName)
    }

    fun start() {
        val info = NsdServiceInfo().apply {
            serviceName = peerId
            serviceType = SERVICE_TYPE
            port = this@PeerDiscovery.port
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    fun stop() {
        runCatching { nsd.stopServiceDiscovery(discovery) }
        runCatching { nsd.unregisterService(registration) }
    }

    // The classic resolve API handles one request at a time, so resolves are queued.
    @Synchronized
    private fun enqueueResolve(info: NsdServiceInfo) {
        resolveQueue.add(info)
        if (!resolving) resolveNext()
    }

    @Synchronized
    private fun resolveNext() {
        val next = resolveQueue.poll() ?: run { resolving = false; return }
        resolving = true
        @Suppress("DEPRECATION")
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) = resolveNext()
            override fun onServiceResolved(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val host = info.host?.hostAddress
                if (host != null) onPeerFound(info.serviceName, host, info.port)
                resolveNext()
            }
        })
    }

    companion object {
        const val SERVICE_TYPE = "_mediacluster._tcp."
        private const val TAG = "PeerDiscovery"
    }
}
