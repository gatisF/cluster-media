package com.mediacluster.library

import kotlinx.serialization.Serializable

enum class MediaKind { VIDEO, AUDIO, IMAGE }

/**
 * One file in the cluster. [id] is "peerId:localId" so it is unique across the cluster.
 * Files on a network drive carry the drive's [driveId]; [peerId] is then the phone that serves the drive.
 */
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
    val driveId: String? = null,
)

/** A network drive (e.g. a USB disk on the router) that a phone has joined to the cluster. */
@Serializable
data class DriveInfo(
    val id: String,
    val name: String,
    val freeBytes: Long,
    val totalBytes: Long,
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
    val drives: List<DriveInfo> = emptyList(),
)

/** A place that can take new media: a phone, or a network drive reached through [host]. */
data class StorageTarget(val name: String, val host: String, val port: Int, val freeBytes: Long, val driveId: String?) {
    val uploadUrl: String get() = "http://$host:$port/api/upload" + (driveId?.let { "?drive=$it&" } ?: "?")
}

fun PeerInfo.mediaUrl(item: MediaItem) = "http://$host:$port/media/${item.localId}"
