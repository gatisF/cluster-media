package com.mediacluster.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random

class ReadAheadStreamTest {

    private val data = Random(1).nextBytes(16 * 1024 * 1024)

    /** Like an SMB1 file on a router: every read is a round trip and returns at most ~60 KB. */
    private inner class SlowSource(private val latencyMs: Long, start: Int = 0) {
        var pos = start
        var reads = 0
        fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            Thread.sleep(latencyMs)
            reads++
            val n = minOf(len, 60 * 1024, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    /** Reads like NanoHTTPD does (16 KB at a time) and spends a little time "sending" each piece. */
    private fun drain(read: (ByteArray, Int, Int) -> Int, sendNanosPerKb: Long): Pair<ByteArray, Long> {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        val t0 = System.nanoTime()
        var first = -1L
        while (true) {
            val n = read(buf, 0, buf.size)
            if (n < 0) break
            if (first < 0) first = System.nanoTime() - t0
            out.write(buf, 0, n)
            val until = System.nanoTime() + sendNanosPerKb * n / 1024
            while (System.nanoTime() < until) Thread.onSpinWait()
        }
        return out.toByteArray() to first
    }

    @Test
    fun deliversExactlyTheSourceBytes() {
        val src = SlowSource(0)
        val (bytes, _) = drain(ReadAheadStream(src::read, {})::read, 0)
        assertArrayEquals(data, bytes)
    }

    @Test
    fun streamsFasterThanReadingDirectly() {
        val sendNanosPerKb = 100_000L // about 10 MB/s to the player, like good Wi-Fi
        val direct = SlowSource(3)
        val t0 = System.nanoTime()
        val (directBytes, directFirst) = drain(direct::read, sendNanosPerKb)
        val directMs = (System.nanoTime() - t0) / 1_000_000

        val ahead = SlowSource(3)
        val t1 = System.nanoTime()
        val (aheadBytes, aheadFirst) = drain(ReadAheadStream(ahead::read, {})::read, sendNanosPerKb)
        val aheadMs = (System.nanoTime() - t1) / 1_000_000

        println("16 MB from a 3 ms-per-read drive: direct $directMs ms (${direct.reads} reads, first byte ${directFirst / 1_000_000} ms), " +
            "read-ahead $aheadMs ms (${ahead.reads} reads, first byte ${aheadFirst / 1_000_000} ms)")
        assertArrayEquals(data, aheadBytes)
        assertArrayEquals(data, directBytes)
        assertTrue("read-ahead should be clearly faster", aheadMs < directMs * 0.8)
        assertTrue("first byte should still come quickly", aheadFirst / 1_000_000 < 50)
    }

    @Test
    fun closeStopsReadingAndClosesTheSource() {
        var closed = false
        val src = SlowSource(1)
        val s = ReadAheadStream(src::read, { closed = true })
        s.read(ByteArray(1000))
        s.close()
        val readsAtClose = src.reads
        Thread.sleep(100)
        assertTrue(closed)
        assertTrue("background reads stop after close", src.reads <= readsAtClose + 1)
    }

    @Test(expected = IOException::class)
    fun sourceErrorsReachTheReader() {
        val s = ReadAheadStream({ _, _, _ -> throw IOException("router went away") }, {})
        s.read(ByteArray(10))
    }

    @Test
    fun emptySourceEndsCleanly() {
        assertEquals(-1, ReadAheadStream({ _, _, _ -> -1 }, {}).read(ByteArray(10)))
    }
}
