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
        val allItems: List<MediaItem> get() = items.values.flatten().sortedBy { it.title.lowercase() }
        val totalBytes: Long get() = peers.values.sumOf { it.totalBytes }
        val freeBytes: Long get() = peers.values.sumOf { it.freeBytes }

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
