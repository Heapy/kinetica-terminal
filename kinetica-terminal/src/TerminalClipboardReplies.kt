package io.heapy.kinetica.terminal

/** Each clipboard binding owns at most one response until its delivery acknowledgement. */
internal class TerminalClipboardReplies(
    private val send: (ByteArray, Boolean, (Boolean) -> Unit) -> Unit,
    private val failed: () -> Unit,
    private val ready: () -> Unit,
) {
    private class Reply(val owner: Any, val bytes: ByteArray, val requireTransport: Boolean, var complete: (() -> Unit)?)
    private val queue = ArrayDeque<Reply>()
    private var active: Reply? = null
    private var draining = false
    val busy: Boolean get() = active != null || queue.isNotEmpty()

    fun offer(owner: Any, packets: Sequence<String>, complete: () -> Unit, requireTransport: Boolean = true) {
        // One per live protocol binding, plus an already-transmitted response from a detached owner.
        check(queue.size + (if (active == null) 0 else 1) < 3) { "Clipboard response already pending" }
        var bytes = ByteArray(4096)
        var size = 0
        for (packet in packets) {
            check(packet.length <= TERMINAL_CLIPBOARD_REPLY_BYTES - size) { "Clipboard reply exceeds its wire budget" }
            val part = packet.encodeToByteArray()
            check(part.size <= TERMINAL_CLIPBOARD_REPLY_BYTES - size) { "Clipboard reply exceeds its wire budget" }
            if (part.size > bytes.size - size)
                bytes = bytes.copyOf(maxOf(size + part.size, minOf(TERMINAL_CLIPBOARD_REPLY_BYTES, bytes.size * 2)))
            part.copyInto(bytes, size); size += part.size
        }
        queue.addLast(Reply(owner, bytes.copyOf(size), requireTransport, complete))
        drain()
    }

    private fun drain() {
        if (draining) return
        draining = true
        try {
            while (active == null && queue.isNotEmpty()) {
                val reply = queue.removeFirst()
                active = reply
                send(reply.bytes, reply.requireTransport) { delivered ->
                    if (active === reply) {
                        active = null
                        val complete = reply.complete; reply.complete = null
                        if (delivered) complete?.invoke() else failed()
                        drain(); ready()
                    }
                }
            }
        } finally { draining = false }
    }

    /** Already queued transport bytes finish before a replacement binding may send its reply. */
    fun cancel() { queue.clear(); active?.complete = null }

    fun cancel(owner: Any) {
        queue.removeAll { it.owner === owner }
        active?.takeIf { it.owner === owner }?.complete = null
        if (!busy) ready()
    }
}
