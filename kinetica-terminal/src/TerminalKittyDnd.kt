// Kitty OSC 72 core adapted from Ghostty dnd_command/drop/response.zig (MIT),
// 83fb95624b0439ee660c2679b43b38c8b63769cd. See third-party/GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

import kotlin.io.encoding.Base64

internal data class TerminalDndMetadata(
    val type: Char? = null, val more: Boolean = false, val clientId: Long = 0, val operation: Long = 0,
    val x: Int = 0, val y: Int = 0, val pixelX: Int = 0, val pixelY: Int = 0,
) {
    companion object {
        fun parse(raw: String): TerminalDndMetadata? {
            var result = TerminalDndMetadata(); var pos = 0
            while (pos < raw.length) {
                val key = raw[pos++]
                if (key !in "tmioxyXY" || raw.getOrNull(pos++) != '=' || pos >= raw.length) return null
                if (key == 't') {
                    val type = raw[pos++]
                    if (type !in "aAmMrRopPeEkq") return null
                    result = result.copy(type = type)
                } else {
                    val signed = key in "xyXY"
                    val negative = signed && raw[pos] == '-'
                    if (negative) pos++
                    val start = pos; var number = 0L
                    while (pos < raw.length && pos - start < 10 && raw[pos] in '0'..'9')
                        number = number * 10 + (raw[pos++] - '0')
                    if (pos == start || number > 0xffffffffL) return null
                    val coordinate = if (negative) -number.toInt() else number.toInt()
                    result = when (key) {
                        'm' -> result.copy(more = number != 0L)
                        'i' -> result.copy(clientId = number)
                        'o' -> result.copy(operation = number)
                        'x' -> result.copy(x = coordinate)
                        'y' -> result.copy(y = coordinate)
                        'X' -> result.copy(pixelX = coordinate)
                        else -> result.copy(pixelY = coordinate)
                    }
                }
                if (pos < raw.length && raw[pos++] != ':') return null
            }
            return result
        }
    }
}

internal enum class TerminalDndEvent { REGISTRATION, ACCEPTANCE, CONCLUDED_NONE, CONCLUDED_COPY, CONCLUDED_MOVE }
internal data class TerminalDndResult(val event: TerminalDndEvent? = null, val packets: Sequence<ByteArray> = emptySequence())
internal data class TerminalDndItem(val mime: ByteArray, val data: ByteArray)
internal data class TerminalDndPosition(val x: Long, val y: Long, val pixelX: Int, val pixelY: Int, val operations: Int) {
    init { require(x in 0..0xffffffffL && y in 0..0xffffffffL && operations in 0..3) }
}

/** Host-neutral state. Protocol replies are lazy packets, never a concatenated data response.
 * Host events own their supplied bytes; drop copies are bounded before allocation. */
internal class TerminalKittyDnd(private val maxDropBytes: Int = 16 * 1024 * 1024) {
    init { require(maxDropBytes >= 0) }
    class State {
        var chunk: TerminalDndMetadata? = null
        var clientId = 0L
        val registered = TerminalDndBytes()
        val acceptedMimes = TerminalDndBytes()
        var hovered = false
        var dropped = false
        var accepted: Int? = null
        var accepting = false
        var offered: List<ByteArray>? = null
        var offeredPayload = byteArrayOf()
        var items: List<TerminalDndItem>? = null
        val clientAccepted: Int? get() = if (accepting) null else accepted
        fun resetDrop() {
            hovered = false; dropped = false; accepted = null; accepting = false
            offered = null; offeredPayload = byteArrayOf(); items = null; acceptedMimes.clear(release = true)
        }
    }
    var state: State? = null
        private set
    fun reset() { state?.chunk = null }
    fun dispose() { state = null }

    fun command(metadata: String, payload: ByteArray = byteArrayOf(), terminator: Int = 27): TerminalDndResult {
        val raw = TerminalDndMetadata.parse(metadata) ?: return TerminalDndResult()
        val continuation = state?.chunk != null
        val meta = state?.let { current ->
            val effective = current.chunk?.copy(more = raw.more) ?: raw
            current.chunk = if (effective.more) effective else null
            effective
        } ?: raw
        when (meta.type) {
            'a' -> {
                if (meta.x == 1) return TerminalDndResult()
                val current = state ?: State().also { state = it; it.chunk = raw.takeIf { it.more } }
                current.clientId = meta.clientId
                if (!continuation) current.registered.clear()
                if (!current.registered.append(payload, MIME_LIMIT)) return TerminalDndResult()
                return TerminalDndResult(if (meta.more) null else TerminalDndEvent.REGISTRATION)
            }
            'A' -> {
                if (state == null) return TerminalDndResult()
                state = null; return TerminalDndResult(TerminalDndEvent.REGISTRATION)
            }
            'm' -> {
                val current = state ?: return TerminalDndResult()
                if (!current.accepting) {
                    current.acceptedMimes.clear(); current.accepting = true
                    current.accepted = operation(meta.operation)
                }
                if (payload.isNotEmpty() && !current.acceptedMimes.append(payload, MIME_LIMIT)) return TerminalDndResult()
                if (meta.more) return TerminalDndResult()
                current.accepting = false
                current.acceptedMimes.finishMimes()
                return TerminalDndResult(TerminalDndEvent.ACCEPTANCE)
            }
            'r' -> return request(meta, terminator)
            'o' -> if (meta.x != 0) return TerminalDndResult()
            'p', 'P' -> Unit
            'q' -> return TerminalDndResult(packets = packets("t=q", meta.clientId, byteArrayOf(), false, terminator))
            else -> return TerminalDndResult()
        }
        return error("t=E", meta.clientId, "EPERM:drag out is not supported by this terminal", terminator)
    }

    private fun request(meta: TerminalDndMetadata, terminator: Int): TerminalDndResult {
        val current = state
        val id = current?.clientId ?: 0
        val keys = buildString {
            if (meta.x != 0) append(":x=${meta.x}")
            if (meta.pixelY != 0) append(":Y=${meta.pixelY}")
            else if (meta.y != 0) append(":y=${meta.y}")
        }
        if (meta.pixelY != 0 || meta.y != 0)
            return error("t=R$keys", id, "EINVAL:remote drop data is not supported", terminator)
        if (meta.x == 0) {
            val dropped = current?.dropped == true
            current?.resetDrop()
            return TerminalDndResult(if (dropped) TerminalDndEvent.entries[2 + operation(meta.operation)] else null)
        }
        val items = current?.items ?: return error("t=R$keys", id, "ENOENT:no drop data available", terminator)
        if (meta.x !in 1..items.size)
            return error("t=R$keys", id, "ENOENT:drop data request index out of bounds", terminator)
        val data = items[meta.x - 1].data
        val response = sequence {
            if (data.isNotEmpty()) yieldAll(packets("t=r$keys", id, data, true, terminator))
            yieldAll(packets("t=r$keys", id, byteArrayOf(), true, terminator))
        }
        return TerminalDndResult(packets = response)
    }

    fun move(position: TerminalDndPosition, mimes: List<ByteArray>): TerminalDndResult {
        val current = state ?: return TerminalDndResult()
        require(mimes.sumOf { it.size.toLong() + 1 } <= MIME_LIMIT)
        return moveEvent(current, position, mimes, drop = false)
    }

    /** Null means no registered client or a host budget rejection, without changing protocol state. */
    fun drop(position: TerminalDndPosition, items: List<TerminalDndItem>): TerminalDndResult? {
        val current = state ?: return null
        val accepted = items.take(16)
        if (accepted.sumOf { it.mime.size.toLong() + 1 } > MIME_LIMIT ||
            accepted.sumOf { it.data.size.toLong() } > maxDropBytes) return null
        val copies = accepted.map { TerminalDndItem(it.mime.copyOf(), it.data.copyOf()) }
        val response = moveEvent(current, position, copies.map { it.mime }, drop = true)
        current.items = copies
        return response
    }

    private fun moveEvent(current: State, position: TerminalDndPosition, mimes: List<ByteArray>, drop: Boolean): TerminalDndResult {
        if (!current.hovered) { current.resetDrop(); current.hovered = true }
        if (drop) { current.dropped = true; current.hovered = false }
        val previous = current.offered
        if (previous == null || previous.size != mimes.size || previous.indices.any { !previous[it].contentEquals(mimes[it]) }) {
            current.offered = mimes.map { it.copyOf() }
            val payload = ByteArray(mimes.sumOf { it.size + 1 })
            var offset = 0
            for (mime in checkNotNull(current.offered)) {
                mime.copyInto(payload, offset); offset += mime.size; payload[offset++] = 32
            }
            current.offeredPayload = payload
        }
        val header = "t=${if (drop) 'M' else 'm'}:x=${position.x}:y=${position.y}:X=${position.pixelX}:Y=${position.pixelY}:o=${position.operations}"
        return TerminalDndResult(packets = packets(header, current.clientId, current.offeredPayload, false, 27))
    }

    fun leave(): TerminalDndResult {
        val current = state ?: return TerminalDndResult()
        if (current.dropped) return TerminalDndResult()
        val hovered = current.hovered
        current.hovered = false; current.offered = null; current.offeredPayload = byteArrayOf()
        return TerminalDndResult(packets = if (hovered) packets("t=m:x=-1:y=-1", current.clientId, byteArrayOf(), false, 27) else emptySequence())
    }

    private fun error(header: String, id: Long, message: String, terminator: Int) =
        TerminalDndResult(packets = packets(header, id, message.encodeToByteArray(), false, terminator))
    companion object {
        const val MIME_LIMIT = 1024 * 1024
        private fun operation(value: Long): Int = if (value in 1..2) value.toInt() else 0
        private fun packets(header: String, id: Long, data: ByteArray, base64: Boolean, terminator: Int): Sequence<ByteArray> = sequence {
            val start = "\u001b]72;" + header + if (id == 0L) "" else ":i=$id"
            val end = if (terminator == 7) byteArrayOf(7) else byteArrayOf(27, 92)
            if (data.isEmpty()) { yield(start.encodeToByteArray() + end); return@sequence }
            val limit = if (base64) 3072 else 4096
            var offset = 0
            while (offset < data.size) {
                val next = minOf(data.size, offset + limit)
                val prefix = (start + ":m=${if (next == data.size) 0 else 1};").encodeToByteArray()
                val payload = if (base64) Base64.encode(data, offset, next).encodeToByteArray() else data.copyOfRange(offset, next)
                yield(prefix + payload + end); offset = next
            }
        }
    }
}

/** Geometric growth keeps tiny continuation chunks linear in total received bytes. */
internal class TerminalDndBytes {
    private var data = byteArrayOf()
    var size = 0
        private set
    fun append(value: ByteArray, limit: Int): Boolean {
        if (value.size > limit - size) return false
        val required = size + value.size
        if (required > data.size) data = data.copyOf(maxOf(required, maxOf(64, data.size * 2)).coerceAtMost(limit))
        value.copyInto(data, size); size = required
        return true
    }
    fun snapshot(): ByteArray = data.copyOf(size)
    fun clear(release: Boolean = false) { size = 0; if (release) data = byteArrayOf() }
    fun finishMimes() {
        if (size == 0) return
        for (i in 0 until size) if (data[i] == 32.toByte()) data[i] = 0
        check(append(byteArrayOf(0), TerminalKittyDnd.MIME_LIMIT + 1))
    }
}
