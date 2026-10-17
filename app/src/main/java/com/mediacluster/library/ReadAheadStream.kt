package com.mediacluster.library

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Reads a slow source (a file on a router drive, one network round trip per read) on a background
 * thread, keeping up to [chunks] x [chunkSize] bytes ready. The server can then send data to the
 * player while the next part is already on its way, instead of waiting for the network on every 16 KB.
 * The first reads are small so playback (or a seek) starts quickly; later ones grow to [chunkSize].
 */
class ReadAheadStream(
    private val readSource: (buf: ByteArray, off: Int, len: Int) -> Int,
    private val closeSource: () -> Unit,
    chunkSize: Int = 512 * 1024,
    chunks: Int = 8,
) : InputStream() {

    private val queue = ArrayBlockingQueue<ByteArray>(chunks)
    @Volatile private var failure: IOException? = null
    @Volatile private var closed = false
    private var current: ByteArray = EMPTY
    private var pos = 0
    private var done = false

    private val reader = Thread({
        var size = FIRST_CHUNK
        try {
            while (!closed) {
                val buf = ByteArray(size)
                var filled = 0
                while (filled < size) {
                    val n = readSource(buf, filled, size - filled)
                    if (n <= 0) break
                    filled += n
                }
                if (filled > 0) put(if (filled == size) buf else buf.copyOf(filled))
                if (filled < size) break // end of file
                size = minOf(size * 4, chunkSize)
            }
        } catch (e: Exception) {
            if (!closed) failure = e as? IOException ?: IOException(e)
        } finally {
            if (!closed) put(END)
        }
    }, "read-ahead").apply { isDaemon = true; start() }

    private fun put(chunk: ByteArray) {
        while (!closed) if (queue.offer(chunk, 200, TimeUnit.MILLISECONDS)) return
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (pos >= current.size) {
            if (done) return -1
            val next = queue.take()
            if (next === END) {
                done = true
                failure?.let { throw it }
                return -1
            }
            current = next
            pos = 0
        }
        val n = minOf(len, current.size - pos)
        System.arraycopy(current, pos, b, off, n)
        pos += n
        return n
    }

    override fun available(): Int = current.size - pos

    override fun close() {
        if (closed) return
        closed = true
        queue.clear()
        reader.interrupt()
        runCatching { closeSource() }
    }

    private companion object {
        val EMPTY = ByteArray(0)
        val END = ByteArray(0)
        const val FIRST_CHUNK = 64 * 1024
    }
}
