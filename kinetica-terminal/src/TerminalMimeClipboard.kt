package io.heapy.kinetica.terminal

public data class TerminalMimeClipboardContent(public val mime: String, public val data: ByteArray)
public enum class TerminalClipboardOperation { READ, WRITE }
public enum class TerminalClipboardResult { SUCCESS, DENIED, UNSUPPORTED, BUSY, IO_ERROR, INVALID_DATA }

/** Application-provided names are untrusted labels, not authenticated process identities. */
public data class TerminalMimeClipboardRequest(
    public val location: TerminalClipboardLocation,
    public val operation: TerminalClipboardOperation,
    public val name: String,
    public val mimeTypes: List<String>,
    public val dataBytes: Int,
    public val targets: Boolean,
    public val granted: Boolean,
    public val canRemember: Boolean,
) {
    public val promptExempt: Boolean get() = operation == TerminalClipboardOperation.READ && mimeTypes.isEmpty()
}

public data class TerminalClipboardDecision(public val allowed: Boolean, public val remember: Boolean = false)

/** Called on the session thread before I/O. A remembered grant may avoid a prompt, not host policy. */
public fun interface TerminalMimeClipboardPolicy {
    public fun authorize(request: TerminalMimeClipboardRequest, complete: (TerminalClipboardDecision) -> Unit)
    public companion object {
        public val DENY: TerminalMimeClipboardPolicy = TerminalMimeClipboardPolicy { _, done -> done(TerminalClipboardDecision(false)) }
        public val ALLOW: TerminalMimeClipboardPolicy = TerminalMimeClipboardPolicy { _, done -> done(TerminalClipboardDecision(true)) }
        public val WRITE_ONLY: TerminalMimeClipboardPolicy = TerminalMimeClipboardPolicy { request, done ->
            done(TerminalClipboardDecision(request.operation == TerminalClipboardOperation.WRITE))
        }
        /** Permit writes and reads authorized by a user paste, without remembering new grants. */
        public val WRITE_AND_PASTE: TerminalMimeClipboardPolicy = TerminalMimeClipboardPolicy { request, done ->
            done(TerminalClipboardDecision(request.operation == TerminalClipboardOperation.WRITE || request.granted))
        }
    }
}

public data class TerminalMimeClipboardRead(
    public val result: TerminalClipboardResult = TerminalClipboardResult.SUCCESS,
    public val available: List<String> = emptyList(),
    public val contents: List<TerminalMimeClipboardContent> = emptyList(),
)

/**
 * Host MIME clipboard. Complete once on the session thread, synchronously or asynchronously.
 * Read arrays are copied before deferred delivery. Treat write arrays as read-only; complete
 * only after committing all representations, or report an error without a partial write.
 * An empty write clears the destination. Unsupported locations must return UNSUPPORTED.
 * Reads should fetch only requested types; [list] requests names of all available types.
 */
public interface TerminalMimeClipboard {
    public fun read(location: TerminalClipboardLocation, mimeTypes: List<String>, list: Boolean, complete: (TerminalMimeClipboardRead) -> Unit)
    public fun write(location: TerminalClipboardLocation, contents: List<TerminalMimeClipboardContent>, complete: (TerminalClipboardResult) -> Unit)
}

internal class TerminalMimeClipboardHost(
    private val clipboard: TerminalMimeClipboard,
    private val policy: TerminalMimeClipboardPolicy,
) : KittyClipboardHost {
    override fun authorize(access: KittyClipboardAccess, complete: (KittyClipboardDecision) -> Unit) {
        policy.authorize(TerminalMimeClipboardRequest(access.location,
            TerminalClipboardOperation.valueOf(access.direction.name), access.name, access.mimeTypes,
            access.dataBytes, access.targets, access.granted, access.canRemember)) {
            complete(KittyClipboardDecision(it.allowed, it.remember))
        }
    }
    override fun read(location: TerminalClipboardLocation, mimes: List<String>, list: Boolean, complete: (KittyClipboardReadResult) -> Unit) {
        clipboard.read(location, mimes, list) { result ->
            complete(KittyClipboardReadResult(result.result.status(KittyClipboardStatus.OK), result.available,
                result.contents.map { KittyClipboardContent(it.mime, it.data) }))
        }
    }
    override fun write(location: TerminalClipboardLocation, contents: List<KittyClipboardContent>, complete: (KittyClipboardStatus) -> Unit) {
        clipboard.write(location, contents.map { TerminalMimeClipboardContent(it.mime, it.data) }) {
            complete(it.status(KittyClipboardStatus.DONE))
        }
    }
    private fun TerminalClipboardResult.status(success: KittyClipboardStatus) = when (this) {
        TerminalClipboardResult.SUCCESS -> success
        TerminalClipboardResult.DENIED -> KittyClipboardStatus.EPERM
        TerminalClipboardResult.UNSUPPORTED -> KittyClipboardStatus.ENOSYS
        TerminalClipboardResult.BUSY -> KittyClipboardStatus.EBUSY
        TerminalClipboardResult.IO_ERROR -> KittyClipboardStatus.EIO
        TerminalClipboardResult.INVALID_DATA -> KittyClipboardStatus.EINVAL
    }
}
