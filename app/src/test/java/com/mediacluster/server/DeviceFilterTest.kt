package com.mediacluster.server

import com.mediacluster.library.ClusterIndex
import com.mediacluster.library.DriveInfo
import com.mediacluster.library.MediaItem
import com.mediacluster.library.MediaKind
import com.mediacluster.library.PeerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeviceFilterTest {

    private fun peer(id: String, name: String, host: String, drives: List<DriveInfo> = emptyList()) =
        PeerInfo(id, name, host, 8765, 10_000_000_000, 64_000_000_000, 0, drives)

    private fun item(peer: String, localId: Long, title: String, kind: MediaKind, drive: String? = null) =
        MediaItem("$peer:$localId", peer, localId, title, if (kind == MediaKind.IMAGE) "image/png" else "video/mp4", 1000, 0, kind, drive)

    private val drive = DriveInfo("drive-1", "Router drive", 900_000_000_000, 1_000_000_000_000)

    // Two phones; phone "b" also serves the router drive, and so does phone "c" (a duplicate that must be hidden).
    private val snap = ClusterIndex().apply {
        put(peer("a", "Pixel 7", "192.168.8.10"), listOf(item("a", 1, "Beach.mp4", MediaKind.VIDEO), item("a", 2, "Sunset.png", MediaKind.IMAGE)))
        put(peer("b", "Galaxy S21", "192.168.8.11", listOf(drive)), listOf(
            item("b", 3, "Party.mp4", MediaKind.VIDEO),
            item("b", -4, "Movie.mkv", MediaKind.VIDEO, "drive-1"),
        ))
        put(peer("c", "Old phone", "192.168.8.12", listOf(drive)), listOf(item("c", -4, "Movie.mkv", MediaKind.VIDEO, "drive-1")))
    }.snapshot.value

    @Test
    fun devicesListPhonesThenDrivesWithCounts() {
        assertEquals(
            listOf("Galaxy S21 (1)", "Old phone (0)", "Pixel 7 (2)", "Router drive (1)"),
            snap.devices.map { "${it.name} (${it.itemCount})" },
        )
    }

    @Test
    fun driveFilesBelongToTheDriveNotThePhone() {
        val movie = snap.allItems.single { it.title == "Movie.mkv" }
        assertEquals("drive-1", snap.deviceOf(movie))
        assertEquals("b", movie.peerId) // served once, by the phone with the smallest id
    }

    @Test
    fun webPageTagsEveryFileWithItsDevice() {
        val html = WebPage.render(snap)
        assertTrue(html.contains("""<select id="dev""""))
        assertTrue(html.contains("""<option value="drive-1">Router drive (1)</option>"""))
        assertTrue(html.contains("""data-dev="drive-1" data-src="http://192.168.8.11:8765/media/-4""""))
        assertTrue(html.contains("""<a href="http://192.168.8.10:8765/media/2" target="_blank" data-dev="a"><img loading="lazy" decoding="async" src="http://192.168.8.10:8765/thumb/2""""))
        assertFalse(html.contains("192.168.8.12:8765/media")) // the duplicate drive server is not used
        // Videos, including drive videos, show a preview frame from the phone that serves them.
        assertTrue(html.contains("""data-src="http://192.168.8.11:8765/media/-4"><img class="vt" loading="lazy" decoding="async" alt="" src="http://192.168.8.11:8765/thumb/-4""""))
        File("build/device-filter-page.html").writeText(html) // for checking the page by eye
    }

    @Test
    fun bigLibraryRendersQuicklyAndCompressesWell() {
        val big = ClusterIndex().apply {
            for (p in 0 until 3) put(peer("p$p", "Phone $p", "192.168.8.${20 + p}"), (0 until 3_400).map {
                item("p$p", it.toLong(), "IMG_2026_${p}_$it.jpg", if (it % 4 == 0) MediaKind.VIDEO else MediaKind.IMAGE)
            })
        }.snapshot.value
        WebPage.render(big) // warm up
        val t0 = System.nanoTime()
        val html = WebPage.render(big)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val gz = java.io.ByteArrayOutputStream().also { java.util.zip.GZIPOutputStream(it).use { z -> z.write(html.toByteArray()) } }.size()
        println("10,200 files: page renders in $ms ms, ${html.length / 1024} KB, ${gz / 1024} KB gzipped")
        assertTrue(ms < 1_000)
    }

    @Test
    fun noPickerWithASingleDevice() {
        val one = ClusterIndex().apply { put(peer("a", "Pixel 7", "192.168.8.10"), emptyList()) }.snapshot.value
        assertFalse(WebPage.render(one).contains("""id="dev""""))
    }
}
