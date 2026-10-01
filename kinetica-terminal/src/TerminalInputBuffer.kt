package io.heapy.kinetica.terminal

/** A fixed-capacity byte ring; tiny writes cannot accumulate an unbounded number of arrays. */
internal class TerminalInputBuffer(private val capacity: Int = 1024 * 1024) {
    init { require(capacity > 0) }
    var buffer: ByteArray? = null
        private set
    var offset: Int = 0
        private set
    var size: Int = 0
        private set
    val contiguousSize: Int get() = minOf(size, capacity - offset)
    val remaining: Int get() = capacity - size

    fun add(text: String) { require(tryAdd(text)) { "Terminal input buffer is full" } }

    fun tryAdd(data: TerminalInputData): Boolean = data.enqueueTo(this)

    /** False leaves the queue unchanged, including when UTF-8 expansion exceeds the capacity. */
    fun tryAdd(text: String): Boolean {
        if (text.isEmpty()) return true
        // UTF-8 uses at least as many bytes as UTF-16 code units, including replacement encoding.
        // Reject huge pastes before encoding; the temporary allocation is at most 3 * capacity.
        if (text.length > remaining) return false
        val bytes = text.encodeToByteArray()
        return tryAdd(bytes, 0, bytes.size)
    }

    fun tryAdd(bytes: ByteArray, sourceOffset: Int = 0, length: Int = bytes.size - sourceOffset): Boolean {
        require(sourceOffset >= 0 && length >= 0 && sourceOffset <= bytes.size - length)
        if (length > remaining) return false
        if (length == 0) return true
        val storage = buffer ?: ByteArray(capacity).also { buffer = it }
        val end = if (size >= capacity - offset) size - (capacity - offset) else offset + size
        val first = minOf(length, capacity - end)
        bytes.copyInto(storage, end, sourceOffset, sourceOffset + first)
        bytes.copyInto(storage, 0, sourceOffset + first, sourceOffset + length)
        size += length
        return true
    }

    fun consume(count: Int) {
        require(count in 0..size)
        offset = if (count >= capacity - offset) count - (capacity - offset) else offset + count
        size -= count
        if (size == 0) offset = 0
    }

    fun clear() { buffer = null; offset = 0; size = 0 }
}

// Keep enough room for a pending 4096-unit OSC palette request to dispatch its complete
// response, plus the other commands in one read slice. TerminalBackpressureTest pins this
// against all implemented query families; new response-generating protocols must extend it.
internal const val TERMINAL_REPLY_RESERVE: Int = 64 * 1024
internal const val TERMINAL_READ_SLICE: Int = 64
