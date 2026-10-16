package com.mediacluster.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The merged library: every online phone and the files it holds. */
class ClusterIndex {
    data class Snapshot(
        val peers: Map<String, PeerInfo> = emptyMap(),
        val items: Map<String, List<MediaItem>> = emptyMap(),
    ) {
        /**
         * Each network drive with the phone that serves it. If several phones joined the same drive,
         * the one with the smallest id serves it, so its files and space are counted once.
         */
        val drives: Map<String, Pair<DriveInfo, PeerInfo>> get() =
            peers.values.sortedByDescending { it.peerId }.flatMap { p -> p.drives.map { it.id to (it to p) } }.toMap()

        val allItems: List<MediaItem> get() {
            val servers = drives.mapValues { it.value.second.peerId }
            return items.values.flatten()
                .filter { it.driveId == null || servers[it.driveId] == it.peerId }
                .sortedBy { it.title.lowercase() }
        }
        val totalBytes: Long get() = peers.values.sumOf { it.totalBytes } + drives.values.sumOf { it.first.totalBytes }
        val freeBytes: Long get() = peers.values.sumOf { it.freeBytes } + drives.values.sumOf { it.first.freeBytes }

        /** Everywhere new media can be stored: every phone, and every network drive through the phone serving it. */
        val storageTargets: List<StorageTarget> get() =
            peers.values.map { StorageTarget(it.name, it.host, it.port, it.freeBytes, null) } +
                drives.values.map { (d, p) -> StorageTarget(d.name, p.host, p.port, d.freeBytes, d.id) }

        /** Where a file lives, for display: the drive's name or the phone's. */
        fun locationOf(item: MediaItem): String? =
            item.driveId?.let { drives[it]?.first?.name } ?: peers[item.peerId]?.name

        /** The phone with the smallest id hosts the DLNA server, so the TV sees one library, not one per phone. */
        val leaderId: String? get() = peers.keys.minOrNull()

        fun itemsOfKind(kind: MediaKind) = allItems.filter { it.kind == kind }
        fun find(id: String) = items.values.asSequence().flatten().firstOrNull { it.id == id }
    }

    private val state = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = state.asStateFlow()

    fun put(peer: PeerInfo, items: List<MediaItem>) = state.update {
        it.copy(peers = it.peers + (peer.peerId to peer), items = it.items + (peer.peerId to items))
    }

    fun remove(peerId: String) = state.update {
        it.copy(peers = it.peers - peerId, items = it.items - peerId)
    }
}
