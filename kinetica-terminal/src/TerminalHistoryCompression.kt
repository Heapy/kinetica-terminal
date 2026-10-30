package io.heapy.kinetica.terminal

/** One idle worker per session, excluding the live history ranges of every attached view. */
internal class TerminalHistoryCompactor(private val session: TerminalSession, private val empty: () -> Unit) {
    private class View(val scheduler: TerminalScheduler?, val range: () -> IntRange?)
    private val views = mutableListOf<View>()
    private var timer: TerminalDisposable? = null
    private var wake: Any? = null
    private val observation = session.observe { request() }

    fun attach(scheduler: TerminalScheduler?, range: () -> IntRange?): TerminalDisposable {
        val view = View(scheduler, range)
        views += view
        request()
        return TerminalDisposable {
            if (!views.remove(view)) return@TerminalDisposable
            if (views.isEmpty()) { cancel(); observation.dispose(); empty() } else request()
        }
    }

    private fun cancel() { wake = null; timer?.dispose(); timer = null }

    fun request() {
        cancel()
        if (!session.scrollbackCompression || session.historyStorageBytes == 0L) return
        schedule(1000, 0)
    }

    private fun schedule(delay: Int, start: Int) {
        val scheduler = views.firstNotNullOfOrNull { it.scheduler } ?: return
        val token = Any(); wake = token
        timer = scheduler.schedule(delay) {
            if (wake === token) {
                wake = null; timer = null
                if (session.scrollbackCompression) {
                    val next = session.compressHistoryBatch(start, views.mapNotNull { it.range() })
                    if (next != null) schedule(1, next)
                }
            }
        }
    }
}

// Encode the four interleaved cell planes independently. Colors/styles form long runs,
// and adjacent Unicode codepoints have small signed deltas. This is an internal lossless
// format, not a persisted or wire format. Optional grapheme/link objects retain identity.
internal fun packTerminalCells(cells: IntArray, columns: Int): ByteArray? {
    // Keep the original if compression cannot save at least 32 payload bytes.
    val capacity = cells.size * 4 - 32
    if (capacity <= 0) return null
    val bytes = ByteArray(capacity)
    var size = 0
    fun unsigned(value: Int): Boolean {
        var remaining = value
        do {
            if (size == bytes.size) return false
            val next = remaining ushr 7
            bytes[size++] = ((remaining and 127) or if (next != 0) 128 else 0).toByte()
            remaining = next
        } while (remaining != 0)
        return true
    }
    for (plane in 0..3) {
        var column = 0
        var previous = 0
        while (column < columns) {
            val value = cells[column * 4 + plane]
            var end = column + 1
            while (end < columns && cells[end * 4 + plane] == value) end++
            val delta = value - previous
            if (!unsigned(end - column - 1) || !unsigned((delta shl 1) xor (delta shr 31))) return null
            previous = value; column = end
        }
    }
    return bytes.copyOf(size)
}

internal fun unpackTerminalCells(bytes: ByteArray, columns: Int): IntArray {
    val cells = IntArray(columns * 4)
    var offset = 0
    fun unsigned(): Int {
        var result = 0
        var shift = 0
        do {
            val byte = bytes[offset++].toInt() and 255
            result = result or ((byte and 127) shl shift)
            shift += 7
        } while (byte and 128 != 0)
        return result
    }
    for (plane in 0..3) {
        var column = 0
        var previous = 0
        while (column < columns) {
            val count = unsigned() + 1
            val encoded = unsigned()
            val value = previous + ((encoded ushr 1) xor -(encoded and 1))
            repeat(count) { cells[column++ * 4 + plane] = value }
            previous = value
        }
    }
    return cells
}
