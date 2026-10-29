package io.heapy.kinetica.terminal

/** A bounded stream of protocol packets. Only one chunk may await transport delivery. */
internal class TerminalReplyStream(
    private val send: (ByteArray, (Boolean) -> Unit) -> Unit,
    private val ready: () -> Unit,
    private val chunkBytes: Int = 32 * 1024,
) {
    init { require(chunkBytes > 0) }
    private var packets: Iterator<String>? = null
    private var next: String? = null
    private var active = false
    private var draining = false
    val busy: Boolean get() = active || packets != null

    fun offer(values: Sequence<String>) {
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
                val chunk = StringBuilder()
                while (next != null || source.hasNext()) {
                    val packet = next ?: source.next()
                    // These protocol responses are ASCII (capability bytes are hex encoded).
                    check(packet.length <= chunkBytes)
                    if (packet.length > chunkBytes - chunk.length) { next = packet; break }
                    next = null; chunk.append(packet)
                }
                if (chunk.isEmpty()) { packets = null; break }
                active = true
                send(chunk.toString().encodeToByteArray()) { delivered ->
                    active = false
                    if (!delivered) { packets = null; next = null }
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
