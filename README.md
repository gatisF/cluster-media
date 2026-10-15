# MediaCluster

An Emby-style media library that lives on a group of Android phones instead of a server.
Every phone on the same Wi-Fi runs the app; together they form one library, and the TV
plays from it over DLNA.

## How it works

```
 Phone A ──┐  mDNS (_mediacluster._tcp)  ┌── Phone B
  :8765    │◄──────── discovery ────────►│   :8765
  /api/library  ◄── each phone pulls ──► /api/library
           │                              │
           │  leader (smallest id) only:  │
           │  SSDP + UPnP ContentDirectory│
           ▼                              │
          TV ── browses library on A ─────┘
             └─ streams file from whichever phone holds it (HTTP Range)
```

1. **Discovery** (`discovery/PeerDiscovery.kt`): each phone advertises itself with Android NSD (mDNS) and
   watches for others. No setup, no pairing.
2. **Library** (`library/`): each phone indexes its own videos, music and photos from MediaStore.
   Nothing is copied. Every 30 s each phone fetches the others' `/api/info` and `/api/library`
   and merges them into `ClusterIndex`. Pooled free/total storage is the sum over phones.
3. **Serving** (`server/PeerHttpServer.kt`): NanoHTTPD on port 8765 serves files with byte-range
   support, so TVs can seek.
4. **TV** (`dlna/`): the phone with the smallest id is the leader and answers SSDP searches as a UPnP
   MediaServer called "MediaCluster". All phones share one UDN, so if the leader leaves and another
   takes over, the TV still sees a single server. Each item's URL points straight at the phone that
   has the file, so the leader doesn't relay video. Folders: Videos, Music, Photos, By phone.
5. **ClusterService** keeps all of this running as a foreground service.

## Build

Open the folder in Android Studio (Ladybug or newer) and run on two or more phones on the same Wi-Fi.
Then on the TV open its media player / DLNA source (Samsung "Connected devices", LG "Media Player",
Sony "Media Player", Android TV with VLC or Kodi) and pick **MediaCluster**.

Status: not yet built against the Android SDK (it wasn't available where this was written). The
DLNA/library logic was compiled and run on the JVM and produces well-formed DIDL-Lite.

## Next steps

- **Push to TV** from the phone: act as a UPnP control point (AVTransport `SetAVTransportURI` + `Play`)
  and/or Google Cast via the Cast SDK for Chromecast/Google TV.
- **Use pooled storage for new files**: "add to cluster" picks the phone with the most free space and
  uploads there (`POST /api/upload`), so the library can grow past one phone.
- **Replication**: keep popular or chosen files on 2 phones so they stay playable when one phone leaves.
- **Security**: a shared cluster PIN, with peers signing `/api/*` requests (HMAC) so random devices on
  the Wi-Fi can't join; the DLNA side stays open because TVs can't authenticate.
- **Transcoding**: files the TV can't play would need on-phone transcoding (MediaCodec) — heavy on battery.
- Thumbnails, metadata (TMDB posters), search, and resume positions shared across phones.
