// OSC 5522 dispatch follows Ghostty 1.4.0 terminal/stream_terminal.zig (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Host callbacks and reply delivery are asynchronous in this embedding.
package io.heapy.kinetica.terminal

internal data class KittyClipboardAccess(
    val location: TerminalClipboardLocation,
    val direction: KittyClipboardDirection,
    val name: String,
    val mimeTypes: List<String>,
    val dataBytes: Int,
    val targets: Boolean,
    val granted: Boolean,
    val canRemember: Boolean,
) {
    val promptExempt: Boolean get() = direction == KittyClipboardDirection.READ && mimeTypes.isEmpty()
}
internal data class KittyClipboardDecision(val allowed: Boolean, val remember: Boolean = false)
internal data class KittyClipboardReadResult(
    val status: KittyClipboardStatus,
    val available: List<String> = emptyList(),
    val contents: List<KittyClipboardContent> = emptyList(),
)

/** All callbacks run on the session thread; host-owned returned arrays are copied before use. */
internal interface KittyClipboardHost {
    fun authorize(access: KittyClipboardAccess, complete: (KittyClipboardDecision) -> Unit)
    fun read(location: TerminalClipboardLocation, mimes: List<String>, list: Boolean, complete: (KittyClipboardReadResult) -> Unit)
    fun write(location: TerminalClipboardLocation, contents: List<KittyClipboardContent>, complete: (KittyClipboardStatus) -> Unit)
}

/**
 * Bounded dispatcher. A false accept must pause parsing and retry the same packet after
 * readiness; it has no effects. The reply sink must consume packets in order and acknowledge
 * the whole response before another queued operation may access the host.
 */
internal class TerminalKittyClipboardBinding(
    private val host: KittyClipboardHost?,
    private val reply: (Sequence<String>, () -> Unit) -> Unit,
    private val ready: () -> Unit = {},
    private val writeLimit: Int = 64 * 1024 * 1024,
    private val queuedBytesLimit: Int = 64 * 1024 * 1024,
    private val readLimit: Int = 8 * 1024 * 1024,
    private val replyLimit: Int = 16 * 1024 * 1024,
    private val queueLimit: Int = 16,
) : TerminalDisposable {
    init { require(writeLimit in 0..queuedBytesLimit && readLimit >= 0 && replyLimit >= 1024 && queueLimit > 0) }
    private data class Pending(
        val metadata: KittyClipboardMetadata,
        val terminator: Int,
        val contents: List<KittyClipboardContent>? = null,
        val mimes: List<String> = emptyList(),
        val list: Boolean = false,
        val bytes: Int = 0,
        val status: KittyClipboardStatus? = null,
    )
    private val queue = ArrayDeque<Pending>()
    private val grants = KittyClipboardGrants()
    private var transaction: KittyClipboardWrite? = null
    private var retained = 0
    private var active = false
    private var draining = false
    private var generation = 0L
    private var disposed = false
    val pendingRequests: Int get() = queue.size
    val pendingBytes: Int get() = retained

    fun accept(body: String, terminator: Int): Boolean {
        if (disposed) return true
        if (queue.size >= queueLimit) return false
        val separator = body.indexOf(';')
        val metadata = when (val parsed = parseKittyClipboardMetadata(if (separator < 0) body else body.substring(0, separator))) {
            KittyClipboardParse.Dropped -> return true
            is KittyClipboardParse.Invalid -> {
                if (parsed.operation == KittyClipboardOperation.WDATA || parsed.operation == KittyClipboardOperation.WALIAS)
                    failWrite(KittyClipboardStatus.EINVAL, terminator)
                return true
            }
            is KittyClipboardParse.Valid -> parsed.metadata
        }
        val payload = if (separator < 0) "" else body.substring(separator + 1)
        when (metadata.operation) {
            KittyClipboardOperation.READ -> {
                val requested = mutableListOf<String>()
                var list = false
                try {
                    for (mime in kittyClipboardMimeList(decodeKittyClipboardPayload(payload))) {
                        if (mime == ".") list = true else if (requested.size < 4) requested.add(mime)
                    }
                } catch (_: KittyClipboardException) { return true }
                if (host == null) enqueue(Pending(metadata, terminator, status = KittyClipboardStatus.EPERM))
                else {
                    val bytes = requested.sumOf { it.encodeToByteArray().size.toLong() }
                    if (bytes > queuedBytesLimit) enqueue(Pending(metadata, terminator, status = KittyClipboardStatus.EIO))
                    else {
                        if (bytes > queuedBytesLimit - retained) return false
                        enqueue(Pending(metadata, terminator, mimes = requested, list = list, bytes = bytes.toInt()))
                    }
                }
            }
            KittyClipboardOperation.WRITE -> {
                transaction = null
                if (host == null) enqueue(Pending(metadata, terminator, status = KittyClipboardStatus.ENOSYS))
                else transaction = KittyClipboardWrite(metadata, writeLimit)
            }
            KittyClipboardOperation.WDATA, KittyClipboardOperation.WALIAS -> {
                val state = transaction ?: return true
                if (metadata.operation == KittyClipboardOperation.WDATA && metadata.mime.isEmpty() && state.accumulatedBytes > queuedBytesLimit - retained)
                    return false
                try {
                    when {
                        metadata.operation == KittyClipboardOperation.WALIAS -> state.alias(metadata.mime, payload)
                        metadata.mime.isNotEmpty() -> state.data(metadata.mime, payload)
                        else -> {
                            val contents = state.commit()
                            transaction = null
                            enqueue(Pending(state.metadata, terminator, contents = contents, bytes = state.accumulatedBytes))
                        }
                    }
                } catch (error: KittyClipboardException) {
                    failWrite(KittyClipboardStatus.valueOf(error.failure.name), terminator)
                }
            }
        }
        return true
    }

    private fun failWrite(status: KittyClipboardStatus, terminator: Int) {
        val state = transaction ?: return
        transaction = null
        enqueue(Pending(state.metadata, terminator, status = status))
    }

    private fun enqueue(pending: Pending) {
        retained += pending.bytes; queue.addLast(pending); drain()
    }

    private fun status(pending: Pending, value: KittyClipboardStatus): Sequence<String> = sequenceOf(
        KittyClipboardResponse(pending.metadata.operation, value, id = pending.metadata.id, terminator = pending.terminator).encode())

    private fun drain() {
        if (disposed || draining) return
        draining = true
        try {
            while (!disposed && !active && queue.isNotEmpty()) {
                val pending = queue.first()
                val epoch = generation
                active = true
                var decided = false
                var responded = false
                var completed = false
                fun current() = !disposed && epoch == generation && !completed
                fun finish(packets: Sequence<String>) {
                    if (!current() || responded) return
                    responded = true
                    // Old sequences yield no new packets after disposal/reset. Already-delivered
                    // bytes cannot be recalled; the owner cancels its transport on detachment.
                    try {
                        reply(Sequence {
                            val source = packets.iterator()
                            object : Iterator<String> {
                                override fun hasNext(): Boolean = current() && source.hasNext()
                                override fun next(): String {
                                    if (!current()) throw NoSuchElementException("Clipboard response canceled")
                                    return source.next()
                                }
                            }
                        }) {
                            if (current()) {
                                completed = true
                                queue.removeFirst(); retained -= pending.bytes; active = false
                                drain(); ready()
                            }
                        }
                    } catch (error: Exception) { dispose(); throw error }
                }
                if (pending.status != null) { finish(status(pending, pending.status)); continue }
                val direction = if (pending.contents == null) KittyClipboardDirection.READ else KittyClipboardDirection.WRITE
                val meta = pending.metadata
                val password = meta.password.takeIf { meta.name.isNotEmpty() }.orEmpty()
                val exempt = direction == KittyClipboardDirection.READ && pending.mimes.isEmpty()
                val access = KittyClipboardAccess(meta.location, direction, meta.name,
                    pending.contents?.map { it.mime } ?: pending.mimes, pending.bytes, pending.list,
                    granted = !exempt && grants.use(password, direction), canRemember = password.isNotEmpty())
                try {
                    host!!.authorize(access) { decision ->
                        if (current() && !decided && !responded) {
                            decided = true
                            if (!decision.allowed) finish(status(pending, KittyClipboardStatus.EPERM))
                            else try {
                                fun remember() {
                                    if (decision.remember && password.isNotEmpty()) grants.grant(password, direction, oneTime = false)
                                }
                                if (pending.contents != null) host.write(meta.location, pending.contents) { result ->
                                    if (current() && !responded) {
                                        val value = if (result in WRITE_RESULTS) result else KittyClipboardStatus.EIO
                                        if (value == KittyClipboardStatus.DONE) remember()
                                        finish(status(pending, value))
                                    }
                                } else host.read(meta.location, pending.mimes, pending.list) { result ->
                                    if (current() && !responded) {
                                        if (result.status == KittyClipboardStatus.OK) {
                                            val response = readResponse(pending, result)
                                            if (response != null) { remember(); finish(response) }
                                            else finish(status(pending, KittyClipboardStatus.EIO))
                                        } else finish(status(pending, if (result.status in READ_ERRORS) result.status else KittyClipboardStatus.EIO))
                                    }
                                }
                            } catch (_: Exception) { if (!responded) finish(status(pending, KittyClipboardStatus.EIO)) }
                        }
                    }
                } catch (_: Exception) { if (!responded) finish(status(pending, KittyClipboardStatus.EIO)) }
            }
        } finally { draining = false }
    }

    private fun readResponse(pending: Pending, result: KittyClipboardReadResult): Sequence<String>? {
        val served = pending.mimes.mapNotNull { mime -> result.contents.firstOrNull { it.mime == mime } }
        if (served.sumOf { it.data.size.toLong() } > readLimit) return null
        val listing = mutableListOf<String>()
        var listingBytes = 0
        if (pending.list) for (mime in result.available) {
            val remaining = 4095 - listingBytes - if (listing.isEmpty()) 0 else 1
            if (mime.length > remaining || mime.encodeToByteArray().size > remaining) {
                // Preserve the original nonempty-but-no-fitting-type listing (a newline).
                if (listing.isEmpty()) listing.add("x".repeat(4096))
                break
            }
            if (listing.isNotEmpty()) listingBytes++
            listing.add(mime); listingBytes += mime.encodeToByteArray().size
        }
        // Calculate the exact encoded size before OK: padding restarts on each DATA chunk.
        fun base64(bytes: Long) = (bytes + 2) / 3 * 4
        fun header(status: KittyClipboardStatus, mimeBytes: Int? = null, primary: Boolean = false): Long =
            "\u001b]5522;type=read:status=".length + status.name.length +
                (if (pending.terminator == 7) 1L else 2L) +
                (if (pending.metadata.id.isEmpty()) 0 else 4 + pending.metadata.id.length) +
                (if (mimeBytes == null) 0 else 6 + base64(mimeBytes.toLong())) +
                (if (primary) 12 else 0)
        var wire = header(KittyClipboardStatus.OK, primary = pending.metadata.location == TerminalClipboardLocation.PRIMARY) +
            header(KittyClipboardStatus.DONE)
        if (pending.list) wire += header(KittyClipboardStatus.DATA, 1) +
            if (result.available.isEmpty()) 0 else 1 + base64(listingBytes + 1L)
        for (content in served) {
            val size = content.data.size.toLong()
            val chunks = (size + 4095) / 4096
            wire += chunks * (header(KittyClipboardStatus.DATA, content.mime.encodeToByteArray().size) + 1) +
                size / 4096 * base64(4096) + base64(size % 4096)
        }
        if (wire > replyLimit) return null
        return KittyClipboardReadSuccess(primary = pending.metadata.location == TerminalClipboardLocation.PRIMARY,
            id = pending.metadata.id, list = pending.list, available = listing,
            contents = served.map { KittyClipboardContent(it.mime, it.data.copyOf()) }, terminator = pending.terminator).packets()
    }

    fun reset(preserveWrite: Boolean = false) {
        generation++; if (!preserveWrite) transaction = null
        queue.clear(); retained = 0; active = false; grants.clear()
        if (!disposed) ready()
    }
    override fun dispose() { disposed = true; reset() }

    private companion object {
        val READ_ERRORS = setOf(KittyClipboardStatus.EPERM, KittyClipboardStatus.ENOSYS, KittyClipboardStatus.EBUSY, KittyClipboardStatus.EIO)
        val WRITE_RESULTS = READ_ERRORS + setOf(KittyClipboardStatus.DONE, KittyClipboardStatus.EINVAL)
    }
}
