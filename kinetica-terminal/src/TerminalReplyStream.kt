package io.heapy.kinetica.terminal

/** A bounded stream of protocol packets. Only one chunk may await transport delivery. */
internal class TerminalReplyStream(
    private val send: (ByteArray, (Boolean) -> Unit) -> Unit,
    private val ready: () -> Unit,
    private val chunkBytes: Int = 32 * 1024,
    private val failed: () -> Unit = {},
) {
    init { require(chunkBytes > 0) }
    private var packets: Iterator<ByteArray>? = null
    private var next: ByteArray? = null
    private var active = false
    private var draining = false
    val busy: Boolean get() = active || packets != null

    fun offer(values: Sequence<String>) {
        offerBytes(values.map { it.encodeToByteArray() })
    }

    fun offerBytes(values: Sequence<ByteArray>) {
        check(!busy)
        packets = values.iterator()
        drain()
    }

    private fun drain() {
        if (draining) return
        draining = true
        try {
            while (!active) {
                val source = packets ?: break
                if (next == null && !source.hasNext()) { packets = null; break }
                var chunk = ByteArray(minOf(chunkBytes, 1024))
                var size = 0
                while (next != null || source.hasNext()) {
                    val packet = next ?: source.next()
                    check(packet.size <= chunkBytes)
                    if (packet.size > chunkBytes - size) { next = packet; break }
                    if (packet.size > chunk.size - size)
                        chunk = chunk.copyOf(maxOf(size + packet.size, minOf(chunkBytes, chunk.size * 2)))
                    next = null; packet.copyInto(chunk, size); size += packet.size
                }
                if (size == 0) { packets = null; break }
                active = true
                send(if (size == chunk.size) chunk else chunk.copyOf(size)) { delivered ->
                    active = false
                    if (!delivered) { packets = null; next = null; failed() }
                    drain()
                }
            }
        } finally { draining = false }
        if (!busy) ready()
    }

    fun cancel() {
        packets = null; next = null
        // A chunk already in the transport must finish before a new stream can start.
        if (!active) ready()
    }
}
