// OSC 52 parsing and replies follow Ghostty's osc/parsers/clipboard_operation.zig
// and stream_terminal.zig (MIT), commit 4da7523faba68ccb4042ea20585817098a51c015.
package io.heapy.kinetica.terminal

import kotlin.io.encoding.Base64

public enum class TerminalClipboardLocation(internal val selector: Char) {
    STANDARD('c'), SELECTION('s'), PRIMARY('p'),
}

/** A null [data] requests a read; an empty array requests clearing the clipboard. */
public data class TerminalClipboardRequest(
    public val location: TerminalClipboardLocation,
    public val data: ByteArray?,
)

/**
 * Host authorization, called before touching the clipboard. Complete once on the session's
 * thread, immediately or after a permission dialog. No response leaves the bounded queue waiting.
 */
public fun interface TerminalClipboardPolicy {
    public fun authorize(request: TerminalClipboardRequest, complete: (Boolean) -> Unit)

    public companion object {
        public val DENY: TerminalClipboardPolicy = TerminalClipboardPolicy { _, complete -> complete(false) }
        public val WRITE_ONLY: TerminalClipboardPolicy = TerminalClipboardPolicy { request, complete -> complete(request.data != null) }
        public val ALLOW: TerminalClipboardPolicy = TerminalClipboardPolicy { _, complete -> complete(true) }
    }
}

/**
 * Clipboard I/O supplied by a host; callbacks run on the session's thread. Null reads indicate
 * denial, unsupported destinations or I/O failure. A write completes after the effect finishes.
 * Hosts may reject data they cannot represent (for example invalid UTF-8 on a text clipboard).
 */
public interface TerminalClipboard {
    public fun read(location: TerminalClipboardLocation, complete: (ByteArray?) -> Unit)
    public fun write(location: TerminalClipboardLocation, data: ByteArray, complete: () -> Unit)
}

internal class TerminalClipboardBinding(
    private val clipboard: TerminalClipboard,
    private val policy: TerminalClipboardPolicy,
    private val ready: () -> Unit = {},
    private val reply: (String, () -> Unit) -> Unit,
) : TerminalDisposable {
    private data class Pending(val request: TerminalClipboardRequest, val terminator: String)
    private val queue = ArrayDeque<Pending>()
    private var bytes = 0
    private var generation = 0L
    private var active = false
    private var draining = false
    private var disposed = false
    val pendingRequests: Int get() = queue.size

    fun accept(payload: String, terminator: Int) {
        if (disposed) return
        val separator = payload.indexOf(';')
        if (separator !in 0..1) return
        if (separator == 1 && payload[0].code > 0x7f) return
        val location = when (if (separator == 0) 'c' else payload[0]) {
            's' -> TerminalClipboardLocation.SELECTION
            'p' -> TerminalClipboardLocation.PRIMARY
            else -> TerminalClipboardLocation.STANDARD
        }
        val encoded = payload.substring(separator + 1)
        val data = if (encoded == "?") null else try {
            Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(normalizeTail(encoded))
        } catch (_: IllegalArgumentException) { return }
        // The parser bounds each capture; also bound retained requests while a host is waiting.
        val size = data?.size ?: 0
        if (queue.size >= 16 || size > MAX_BYTES - bytes) return
        queue.addLast(Pending(TerminalClipboardRequest(location, data), if (terminator == 7) "\u0007" else "\u001b\\"))
        bytes += size
        drain()
    }

    private fun drain() {
        if (draining || disposed) return
        draining = true
        try {
            while (!disposed && !active && queue.isNotEmpty()) {
                val pending = queue.first()
                val epoch = generation
                active = true
                var authorized = false
                var completed = false
                fun current() = !disposed && generation == epoch && !completed
                fun finish(data: ByteArray? = null) {
                    if (!current()) return
                    completed = true
                    var delivered = false
                    fun release() {
                        if (delivered || disposed || generation != epoch) return
                        delivered = true
                        queue.removeFirst()
                        bytes -= pending.request.data?.size ?: 0
                        active = false
                        drain()
                        ready()
                    }
                    if (pending.request.data == null) {
                        val content = if (data != null && data.size <= MAX_BYTES) Base64.Default.encode(data) else ""
                        reply("\u001b]52;${pending.request.location.selector};$content${pending.terminator}", ::release)
                    } else release()
                }
                try {
                    policy.authorize(pending.request) { allowed ->
                        if (current() && !authorized) {
                            authorized = true
                            if (!allowed) finish() else try {
                                val data = pending.request.data
                                if (data == null) clipboard.read(pending.request.location) { finish(it) }
                                else clipboard.write(pending.request.location, data) { finish() }
                            } catch (_: Exception) { finish() }
                        }
                    }
                } catch (_: Exception) { finish() }
            }
        } finally { draining = false }
    }

    fun reset() { generation++; queue.clear(); bytes = 0; active = false }
    override fun dispose() { disposed = true; reset() }

    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        // Ghostty's SIMD decoder accepts nonzero unused tail bits. Kotlin's decoder
        // requires canonical bits, so normalize only those bits; alphabet/padding
        // validation still belongs to the strict decoder.
        private fun normalizeTail(encoded: String): String {
            val size = encoded.indexOf('=').let { if (it < 0) encoded.length else it }
            val mask = when (size % 4) { 2 -> 0x30; 3 -> 0x3c; else -> return encoded }
            val value = ALPHABET.indexOf(encoded[size - 1])
            if (value < 0 || value and mask == value) return encoded
            return encoded.substring(0, size - 1) + ALPHABET[value and mask] + encoded.substring(size)
        }
    }
}
