package com.mediacluster.dlna

import com.mediacluster.library.ClusterIndex
import com.mediacluster.library.MediaItem
import com.mediacluster.library.MediaKind
import com.mediacluster.library.mediaUrl

/**
 * A minimal UPnP/DLNA MediaServer. Smart TVs (Samsung, LG, Sony, Android TV with VLC/Kodi)
 * browse it and stream each file straight from the phone that holds it.
 *
 * Tree: 0 (root) -> video | audio | image | phones, and phones -> one folder per phone.
 */
class ContentDirectory(
    private val udn: String,
    private val friendlyName: () -> String,
) {
    @Volatile var systemUpdateId = 1

    fun deviceDescription() = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
    <friendlyName>${esc(friendlyName())}</friendlyName>
    <manufacturer>MediaCluster</manufacturer>
    <modelName>MediaCluster</modelName>
    <UDN>$udn</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
        <SCPDURL>/dlna/cds.xml</SCPDURL>
        <controlURL>/dlna/control/cds</controlURL>
        <eventSubURL>/dlna/event/cds</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>/dlna/cm.xml</SCPDURL>
        <controlURL>/dlna/control/cm</controlURL>
        <eventSubURL>/dlna/event/cm</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>"""

    fun handleControl(soap: String, snap: ClusterIndex.Snapshot): String {
        val action = Regex("<u:(\\w+)").find(soap)?.groupValues?.get(1)
            ?: Regex(":(Browse|GetSystemUpdateID|GetSearchCapabilities|GetSortCapabilities)\\b").find(soap)?.groupValues?.get(1)
        return when (action) {
            "Browse" -> browse(soap, snap)
            "GetSystemUpdateID" -> envelope("GetSystemUpdateID", "<Id>$systemUpdateId</Id>")
            "GetSearchCapabilities" -> envelope("GetSearchCapabilities", "<SearchCaps></SearchCaps>")
            "GetSortCapabilities" -> envelope("GetSortCapabilities", "<SortCaps></SortCaps>")
            else -> envelope("Error", "")
        }
    }

    private fun browse(soap: String, snap: ClusterIndex.Snapshot): String {
        val objectId = tag(soap, "ObjectID") ?: "0"
        val flag = tag(soap, "BrowseFlag") ?: "BrowseDirectChildren"
        val start = tag(soap, "StartingIndex")?.toIntOrNull() ?: 0
        val count = tag(soap, "RequestedCount")?.toIntOrNull()?.takeIf { it > 0 } ?: Int.MAX_VALUE

        val entries: List<String>
        val total: Int
        if (flag == "BrowseMetadata") {
            entries = listOfNotNull(metadata(objectId, snap))
            total = entries.size
        } else {
            val all = children(objectId, snap)
            total = all.size
            entries = all.drop(start).take(count)
        }
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
            entries.joinToString("") + "</DIDL-Lite>"
        return envelope(
            "Browse",
            "<Result>${esc(didl)}</Result><NumberReturned>${entries.size}</NumberReturned>" +
                "<TotalMatches>$total</TotalMatches><UpdateID>$systemUpdateId</UpdateID>",
        )
    }

    private fun children(id: String, snap: ClusterIndex.Snapshot): List<String> = when {
        id == "0" -> listOf(
            container("video", "0", "Videos", snap.itemsOfKind(MediaKind.VIDEO).size),
            container("audio", "0", "Music", snap.itemsOfKind(MediaKind.AUDIO).size),
            container("image", "0", "Photos", snap.itemsOfKind(MediaKind.IMAGE).size),
            container("phones", "0", "By phone", snap.peers.size),
        )
        id == "video" -> snap.itemsOfKind(MediaKind.VIDEO).mapNotNull { item(it, id, snap) }
        id == "audio" -> snap.itemsOfKind(MediaKind.AUDIO).mapNotNull { item(it, id, snap) }
        id == "image" -> snap.itemsOfKind(MediaKind.IMAGE).mapNotNull { item(it, id, snap) }
        id == "phones" -> snap.peers.values.map { container("phone/${it.peerId}", "phones", it.name, snap.items[it.peerId]?.size ?: 0) }
        id.startsWith("phone/") -> snap.items[id.removePrefix("phone/")].orEmpty().mapNotNull { item(it, id, snap) }
        else -> emptyList()
    }

    private fun metadata(id: String, snap: ClusterIndex.Snapshot): String? = when (id) {
        "0" -> container("0", "-1", friendlyName(), 4)
        else -> snap.find(id)?.let { item(it, it.kind.name.lowercase(), snap) }
            ?: container(id, "0", id, children(id, snap).size)
    }

    private fun container(id: String, parent: String, title: String, childCount: Int) =
        """<container id="${esc(id)}" parentID="${esc(parent)}" restricted="1" childCount="$childCount">""" +
            "<dc:title>${esc(title)}</dc:title><upnp:class>object.container.storageFolder</upnp:class></container>"

    private fun item(m: MediaItem, parent: String, snap: ClusterIndex.Snapshot): String? {
        val peer = snap.peers[m.peerId] ?: return null
        val upnpClass = when (m.kind) {
            MediaKind.VIDEO -> "object.item.videoItem"
            MediaKind.AUDIO -> "object.item.audioItem.musicTrack"
            MediaKind.IMAGE -> "object.item.imageItem.photo"
        }
        val duration = if (m.durationMs > 0) """ duration="${formatDuration(m.durationMs)}"""" else ""
        return """<item id="${esc(m.id)}" parentID="${esc(parent)}" restricted="1">""" +
            "<dc:title>${esc(m.title)}</dc:title><upnp:class>$upnpClass</upnp:class>" +
            """<res protocolInfo="http-get:*:${m.mimeType}:$DLNA_FEATURES" size="${m.sizeBytes}"$duration>""" +
            esc(peer.mediaUrl(m)) + "</res></item>"
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d.%03d".format(s / 3600, (s / 60) % 60, s % 60, ms % 1000)
    }

    companion object {
        const val DLNA_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

        private fun tag(xml: String, name: String) =
            Regex("<(?:\\w+:)?$name[^>]*>([^<]*)</(?:\\w+:)?$name>").find(xml)?.groupValues?.get(1)?.trim()

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        private fun envelope(action: String, body: String, service: String = "ContentDirectory") =
            """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:${action}Response xmlns:u="urn:schemas-upnp-org:service:$service:1">$body</u:${action}Response></s:Body>
</s:Envelope>"""

        fun connectionManagerResponse(soap: String): String = when {
            "GetProtocolInfo" in soap -> envelope(
                "GetProtocolInfo",
                "<Source>http-get:*:video/*:*,http-get:*:audio/*:*,http-get:*:image/*:*</Source><Sink></Sink>",
                "ConnectionManager",
            )
            "GetCurrentConnectionIDs" in soap -> envelope("GetCurrentConnectionIDs", "<ConnectionIDs>0</ConnectionIDs>", "ConnectionManager")
            else -> envelope("Error", "", "ConnectionManager")
        }

        val SCPD = """<?xml version="1.0"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>Browse</name><argumentList>
      <argument><name>ObjectID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ObjectID</relatedStateVariable></argument>
      <argument><name>BrowseFlag</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_BrowseFlag</relatedStateVariable></argument>
      <argument><name>Filter</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Filter</relatedStateVariable></argument>
      <argument><name>StartingIndex</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Index</relatedStateVariable></argument>
      <argument><name>RequestedCount</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
      <argument><name>SortCriteria</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SortCriteria</relatedStateVariable></argument>
      <argument><name>Result</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Result</relatedStateVariable></argument>
      <argument><name>NumberReturned</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
      <argument><name>TotalMatches</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
      <argument><name>UpdateID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_UpdateID</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>GetSystemUpdateID</name><argumentList>
      <argument><name>Id</name><direction>out</direction><relatedStateVariable>SystemUpdateID</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>GetSearchCapabilities</name><argumentList>
      <argument><name>SearchCaps</name><direction>out</direction><relatedStateVariable>SearchCapabilities</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>GetSortCapabilities</name><argumentList>
      <argument><name>SortCaps</name><direction>out</direction><relatedStateVariable>SortCapabilities</relatedStateVariable></argument>
    </argumentList></action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_ObjectID</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_BrowseFlag</name><dataType>string</dataType>
      <allowedValueList><allowedValue>BrowseMetadata</allowedValue><allowedValue>BrowseDirectChildren</allowedValue></allowedValueList></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Filter</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Index</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Count</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SortCriteria</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Result</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_UpdateID</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>SystemUpdateID</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>SearchCapabilities</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>SortCapabilities</name><dataType>string</dataType></stateVariable>
  </serviceStateTable>
</scpd>"""

        val CM_SCPD = """<?xml version="1.0"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>GetProtocolInfo</name><argumentList>
      <argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>
      <argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>GetCurrentConnectionIDs</name><argumentList>
      <argument><name>ConnectionIDs</name><direction>out</direction><relatedStateVariable>CurrentConnectionIDs</relatedStateVariable></argument>
    </argumentList></action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="yes"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>CurrentConnectionIDs</name><dataType>string</dataType></stateVariable>
  </serviceStateTable>
</scpd>"""
    }
}
