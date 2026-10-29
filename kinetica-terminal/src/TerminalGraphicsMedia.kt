// External transmission semantics follow Ghostty kitty/graphics_image.zig (MIT).
// Pinned source and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

public enum class TerminalGraphicsMedium { FILE, TEMPORARY_FILE, SHARED_MEMORY }

/**
 * A single external Kitty image read. [name] contains the decoded path/name bytes, not Base64.
 * [size] is an exact byte count; zero uses the medium's implicit length. [maxBytes] bounds the
 * returned encoded image, before decompression. The host must also bound its intermediate data.
 */
public class TerminalGraphicsMediaRequest internal constructor(
    public val medium: TerminalGraphicsMedium,
    public val name: ByteArray,
    public val offset: Long,
    public val size: Long,
    public val format: Int,
    public val width: Long,
    public val height: Long,
    public val compressed: Boolean,
    public val maxBytes: Int,
    internal val maxDimension: Int,
) {
    /** Ghostty uses the raw pixel length for uncompressed shared memory, excluding page padding. */
    internal fun readLength(available: Long): Long {
        if (available < 0) throw TerminalGraphicsMediaException(TerminalGraphicsMediaResult.INVALID_DATA)
        if (medium == TerminalGraphicsMedium.SHARED_MEMORY && format != 100 &&
            (width > maxDimension || height > maxDimension))
            throw TerminalGraphicsMediaException(TerminalGraphicsMediaResult.DIMENSIONS_TOO_LARGE)
        val length = when {
            size > 0 -> size
            medium == TerminalGraphicsMedium.SHARED_MEMORY && !compressed && format != 100 -> width * height * (format / 8)
            else -> available
        }
        if (length > maxBytes || length > available)
            throw TerminalGraphicsMediaException(TerminalGraphicsMediaResult.INVALID_DATA)
        return length
    }
}

/** Fixed protocol errors keep local paths and exception messages out of terminal replies. */
public sealed interface TerminalGraphicsMediaResult {
    /** Transfers ownership: the host must not change [bytes] after returning them. */
    public class Data(public val bytes: ByteArray) : TerminalGraphicsMediaResult
    public data object UNSUPPORTED : TerminalGraphicsMediaResult
    public data object INVALID_DATA : TerminalGraphicsMediaResult
    public data object TEMPORARY_FILE_NOT_IN_TEMP_DIR : TerminalGraphicsMediaResult
    public data object TEMPORARY_FILE_NOT_NAMED_CORRECTLY : TerminalGraphicsMediaResult
    public data object DIMENSIONS_TOO_LARGE : TerminalGraphicsMediaResult
}

/**
 * Synchronous host access, called on the session's owning thread. Authorize the medium before
 * touching the filesystem. Do not reenter the session. Implementations validate paths, permit
 * only regular files, bound reads, and release handles/mappings on every exit. Temporary files
 * and shared memory also require the protocol's safe deletion/unlink behavior. Returning
 * [TerminalGraphicsMediaResult.UNSUPPORTED] denies access. No attached host means all external
 * media are denied. Browser hosts can supply an explicitly authorized virtual file store.
 */
public fun interface TerminalGraphicsMedia {
    public fun read(request: TerminalGraphicsMediaRequest): TerminalGraphicsMediaResult
}

internal class TerminalGraphicsMediaException(val result: TerminalGraphicsMediaResult) : Exception() {
    fun messageForProtocol(): String = when (result) {
        TerminalGraphicsMediaResult.UNSUPPORTED -> "EINVAL: unsupported medium"
        TerminalGraphicsMediaResult.TEMPORARY_FILE_NOT_IN_TEMP_DIR -> "EINVAL: temporary file not in temp dir"
        TerminalGraphicsMediaResult.TEMPORARY_FILE_NOT_NAMED_CORRECTLY -> "EINVAL: temporary file not named correctly"
        TerminalGraphicsMediaResult.DIMENSIONS_TOO_LARGE -> "EINVAL: dimensions too large"
        else -> "EINVAL: invalid data"
    }
}
