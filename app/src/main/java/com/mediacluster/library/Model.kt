package com.mediacluster.library

import kotlinx.serialization.Serializable

enum class MediaKind { VIDEO, AUDIO, IMAGE }

/** One file on one phone. [id] is "peerId:localId" so it is unique across the cluster. */
@Serializable
data class MediaItem(
    val id: String,
    val peerId: String,
    val localId: Long,
    val title: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val kind: MediaKind,
)

/** What a phone says about itself on GET /api/info. */
@Serializable
data class PeerInfo(
    val peerId: String,
    val name: String,
    val host: String,
    val port: Int,
    val freeBytes: Long,
    val totalBytes: Long,
    val itemCount: Int,
)

fun PeerInfo.mediaUrl(item: MediaItem) = "http://$host:$port/media/${item.localId}"
