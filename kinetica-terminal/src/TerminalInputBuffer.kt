package io.heapy.kinetica.terminal

/** A bounded byte ring; clipboard replies can use a separate, lazy protocol allowance. */
internal class TerminalInputBuffer(private val capacity: Int = 1024 * 1024) {
    init { require(capacity > 0) }
    var buffer: ByteArray? = null
        private set
    var offset: Int = 0
        private set
    var size: Int = 0
        private set
    val contiguousSize: Int get() = minOf(size, (buffer?.size ?: capacity) - offset)
    val remaining: Int get() = (capacity - size).coerceAtLeast(0)

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
        return append(bytes, sourceOffset, length)
    }

    fun tryAddProtocolReply(bytes: ByteArray): Boolean {
        // Keep an entire OSC response together, even with a stalled writer. Normal input
        // and further output parsing stay backpressured until this reserve drains.
        val maximum = minOf(Int.MAX_VALUE.toLong(), capacity.toLong() + TERMINAL_CLIPBOARD_REPLY_BYTES).toInt()
        if (bytes.size > TERMINAL_CLIPBOARD_REPLY_BYTES || bytes.size > maximum - size) return false
        val needed = size + bytes.size
        val storage = buffer
        if (needed > (storage?.size ?: capacity)) {
            val grown = ByteArray(maxOf(needed, minOf(maximum.toLong(), (storage?.size ?: capacity).toLong() * 2).toInt()))
            if (storage != null) {
                val first = contiguousSize
                storage.copyInto(grown, 0, offset, offset + first)
                storage.copyInto(grown, first, 0, size - first)
            }
            buffer = grown; offset = 0
        }
        return append(bytes, 0, bytes.size)
    }

    private fun append(bytes: ByteArray, sourceOffset: Int, length: Int): Boolean {
        if (length == 0) return true
        val storage = buffer ?: ByteArray(capacity).also { buffer = it }
        val end = if (size >= storage.size - offset) size - (storage.size - offset) else offset + size
        val first = minOf(length, storage.size - end)
        bytes.copyInto(storage, end, sourceOffset, sourceOffset + first)
        bytes.copyInto(storage, 0, sourceOffset + first, sourceOffset + length)
        size += length
        return true
    }

    fun consume(count: Int) {
        require(count in 0..size)
        val storageSize = buffer?.size ?: capacity
        offset = if (count >= storageSize - offset) count - (storageSize - offset) else offset + count
        size -= count
        if (size == 0) {
            offset = 0
            if (storageSize > capacity) buffer = null
        }
    }

    fun clear() { buffer = null; offset = 0; size = 0 }
}

// Keep enough room for a pending 4096-unit OSC palette request to dispatch its complete
// response, plus the other commands in one read slice. TerminalBackpressureTest pins this
// against all implemented query families; new response-generating protocols must extend it.
internal const val TERMINAL_REPLY_RESERVE: Int = 64 * 1024
// Base64 of the maximum clipboard read, plus OSC 52 header and ST. Allocated only on demand.
internal const val TERMINAL_CLIPBOARD_REPLY_BYTES: Int = ((TerminalClipboardBinding.MAX_BYTES + 2) / 3) * 4 + 9
internal const val TERMINAL_READ_SLICE: Int = 64
