// Image loading/storage semantics follow pinned Ghostty kitty/graphics_exec.zig and graphics_storage.zig.
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors. MIT licensed.
// Pinned source and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

internal class TerminalGraphicsStoredImage(
    val id: Long,
    val number: Long,
    val pixels: TerminalGraphicsPixels,
    val transient: Boolean,
    val implicitId: Boolean,
    internal val age: Long,
) {
    // Object identity is a unique content generation even across stores, resets and ID reuse.
    val generation: Any = Any()
    val renderContent = TerminalGraphicsImageContent(id, pixels)
    internal var placementCount: Int = 0
}

internal data class TerminalGraphicsResponse(
    val id: Long = 0,
    val number: Long = 0,
    val placement: Long = 0,
    val message: String = "OK",
) {
    fun encode(quiet: Int = 0): String? {
        if (quiet >= 2 || quiet == 1 && message == "OK" || id == 0L && number == 0L) return null
        return buildString {
            append("\u001b_G")
            if (id != 0L) append("i=$id")
            if (number != 0L) { if (id != 0L) append(','); append("I=$number") }
            if (placement != 0L) append(",p=$placement")
            append(';'); append(message); append("\u001b\\")
        }
    }
}

/** The executor must apply [display] before sending the final response for a=T. */
internal class TerminalGraphicsTransferResult(
    val response: TerminalGraphicsResponse? = null,
    val quiet: Int = 0,
    val image: TerminalGraphicsStoredImage? = null,
    val display: TerminalGraphicsCommand? = null,
    val pending: Boolean = false,
) {
    fun reply(): String? = response?.encode(quiet)
}

/**
 * One screen's decoded images and in-progress base-image transfer. The placement/animation
 * executor owns their geometry and calls [remove] for selected image IDs. This component
 * performs no host I/O and does not advertise graphics support by itself.
 */
internal class TerminalGraphicsStorage(
    limit: Int = 320_000_000,
    private val decodeLimits: TerminalGraphicsDecodeLimits = TerminalGraphicsDecodeLimits(),
    private val removed: (TerminalGraphicsStoredImage) -> Unit = {},
) {
    init { require(limit >= 0) }
    private val images = mutableMapOf<Long, TerminalGraphicsStoredImage>()
    private var nextImplicitId = 2147483647L
    private var age = 0L
    private var loading: Loading? = null
    var limit: Int = limit
        private set
    var totalBytes: Long = 0
        private set
    var generation: Any = Any()
        private set
    val enabled: Boolean get() = limit != 0
    val imageCount: Int get() = images.size
    val loadingBytes: Int get() = loading?.data?.size ?: 0
    val retainedLoadingBytes: Int get() = loading?.data?.capacity ?: 0
    val hasPendingTransfer: Boolean get() = loading != null

    fun image(id: Long): TerminalGraphicsStoredImage? = images[id]
    fun imageByNumber(number: Long): TerminalGraphicsStoredImage? = images.values.filter { it.number == number }.maxByOrNull { it.age }

    fun setLimit(value: Int) {
        require(value >= 0)
        if (value == 0) clear()
        else evictTo(value.toLong())
        limit = value
    }

    fun clear() {
        abortTransfer()
        for (image in images.values.toList()) remove(image.id)
        nextImplicitId = 2147483647L
        generation = Any()
    }

    fun abortTransfer() { loading = null }

    fun removeUnused() {
        images.values.filter { it.placementCount == 0 }.map { it.id }.forEach { remove(it) }
    }

    fun remove(id: Long): Boolean {
        val image = images.remove(id) ?: return false
        totalBytes -= image.pixels.data.size
        generation = Any()
        removed(image)
        return true
    }

    /** An uppercase range deletion also selects unused, never-placed images. */
    fun removeRange(first: Long, last: Long) {
        if (last == 0L || first > last) return
        for (id in images.keys.filter { it in first..last }) remove(id)
    }

    fun conflictingIdentifiers(command: TerminalGraphicsCommand): TerminalGraphicsResponse? =
        if (command.imageId != 0L && command.imageNumber != 0L)
            response(command, "EINVAL: image ID and number are mutually exclusive") else null

    /** Queries validate a complete image without altering an existing transfer or stored image. */
    fun query(command: TerminalGraphicsCommand): TerminalGraphicsTransferResult {
        require(command.action == 'q')
        if (!enabled) return TerminalGraphicsTransferResult()
        conflictingIdentifiers(command)?.let { return TerminalGraphicsTransferResult(it, command.quiet) }
        if (command.imageId == 0L) return TerminalGraphicsTransferResult()
        val error = validateMedium(command) ?: try {
            decode(command, command.data)
            null
        } catch (error: TerminalGraphicsDecodeException) { error.messageForProtocol() }
        return TerminalGraphicsTransferResult(response(command, error ?: "OK"), command.quiet)
    }

    /**
     * Base image t/T, or any transmit-like continuation of that base image. Standalone a=f
     * belongs to the animation executor and must never be routed here as a new base image.
     */
    fun transmit(command: TerminalGraphicsCommand): TerminalGraphicsTransferResult {
        require(command.action in "tT" || command.action == 'f' && loading != null)
        if (!enabled) return TerminalGraphicsTransferResult()
        conflictingIdentifiers(command)?.let { return TerminalGraphicsTransferResult(it, command.quiet) }
        val current = loading
        val quiet = if (current != null && command.quiet == 0) current.quiet else command.quiet
        if (current != null && command.quiet != 0) current.quiet = quiet
        val initial = current?.header ?: command
        fun failed(message: String) = TerminalGraphicsTransferResult(response(initial, message), quiet)
        val load: Loading
        if (current != null) {
            // Failure to append leaves the previous chunks intact, as in Ghostty.
            if (!current.data.append(command.data)) return failed("EINVAL: invalid data")
            if (command.moreChunks) return TerminalGraphicsTransferResult(quiet = quiet, pending = true)
            load = current
            loading = null
        } else {
            // Replacement destroys the old image/placements at the first chunk, even if loading fails.
            if (command.imageId != 0L) remove(command.imageId)
            validateMedium(command)?.let { return failed(it) }
            val bytes = GraphicsTransferBytes(decodeLimits.maxEncodedBytes)
            if (!bytes.append(command.data)) return failed("EINVAL: invalid data")
            val implicit = command.imageId == 0L && command.imageNumber == 0L
            val id = if (command.imageId != 0L) command.imageId else nextId(implicit)
            load = Loading(command.withoutData(), id, implicit, quiet, bytes)
            if (command.moreChunks) {
                loading = load
                return TerminalGraphicsTransferResult(quiet = quiet, pending = true)
            }
        }
        val pixels = try { decode(load.header, load.data.take()) }
        catch (error: TerminalGraphicsDecodeException) { return failed(error.messageForProtocol()) }
        if (pixels.data.size > limit) return failed("ENOMEM: out of memory")
        evictTo(limit.toLong() - pixels.data.size)
        val image = TerminalGraphicsStoredImage(load.id, load.header.imageNumber, pixels,
            load.header.unsigned('N') and 1L != 0L, load.implicit, ++age)
        // A new explicit ID can only be absent here; a chunk's ID is allocated at its beginning.
        // Queries and placement commands cannot insert a conflicting image during that load.
        remove(image.id)
        images[image.id] = image
        totalBytes += pixels.data.size
        generation = Any()
        return TerminalGraphicsTransferResult(
            if (load.implicit) null else response(load.header).copy(id = image.id), quiet, image,
            if (load.header.action == 'T') load.header else null)
    }

    private fun nextId(implicit: Boolean): Long {
        var id = if (implicit) nextImplicitId else 1L
        repeat(images.size + 2) {
            if (id != 0L && !images.containsKey(id)) {
                if (implicit) nextImplicitId = (id + 1) and 0xffffffffL
                return id
            }
            id = (id + 1) and 0xffffffffL
        }
        error("No image ID available")
    }

    private fun evictTo(target: Long) {
        if (totalBytes <= target) return
        // Sort once to avoid quadratic work when a large image evicts many tiny ones.
        val victims = images.values.sortedWith(compareBy<TerminalGraphicsStoredImage>(
            { (if (it.transient) 0 else 1) + (if (it.placementCount > 0) 2 else 0) }, { it.age }, { it.id }))
        for (victim in victims) {
            remove(victim.id)
            if (totalBytes <= target) return
        }
    }

    private fun validateMedium(command: TerminalGraphicsCommand): String? = when {
        command.format < 0 -> "EINVAL: unsupported format"
        command.medium != 'd' -> "EINVAL: unsupported medium"
        else -> null
    }

    private fun decode(command: TerminalGraphicsCommand, bytes: ByteArray): TerminalGraphicsPixels =
        decodeTerminalGraphicsImage(command.format, command.unsigned('s'), command.unsigned('v'), command.compressed, bytes, decodeLimits)

    private class Loading(val header: TerminalGraphicsCommand, val id: Long, val implicit: Boolean,
        var quiet: Int, val data: GraphicsTransferBytes)
}

private fun response(command: TerminalGraphicsCommand, message: String = "OK") =
    TerminalGraphicsResponse(command.imageId, command.imageNumber, command.placementId, message)

private fun TerminalGraphicsDecodeException.messageForProtocol(): String = when (error) {
    TerminalGraphicsDecodeError.UNSUPPORTED_FORMAT -> "EINVAL: unsupported format"
    TerminalGraphicsDecodeError.DIMENSIONS_REQUIRED -> "EINVAL: dimensions required"
    TerminalGraphicsDecodeError.DIMENSIONS_TOO_LARGE -> "EINVAL: dimensions too large"
    TerminalGraphicsDecodeError.INVALID_DATA, TerminalGraphicsDecodeError.RESOURCE_LIMIT -> "EINVAL: invalid data"
    TerminalGraphicsDecodeError.INSUFFICIENT_DATA -> "ENODATA: insufficient data"
    TerminalGraphicsDecodeError.DECOMPRESSION_FAILED -> "EINVAL: decompression failed"
}

private class GraphicsTransferBytes(private val limit: Int) {
    private var bytes = ByteArray(0)
    var size = 0
        private set
    val capacity: Int get() = bytes.size
    fun append(data: ByteArray): Boolean {
        if (data.size > limit - size) return false
        val required = size + data.size
        if (required > bytes.size) bytes = bytes.copyOf(maxOf(required.toLong(), maxOf(256L, bytes.size.toLong() * 2)).coerceAtMost(limit.toLong()).toInt())
        data.copyInto(bytes, size)
        size = required
        return true
    }
    fun take(): ByteArray = (if (size == bytes.size) bytes else bytes.copyOf(size)).also { bytes = ByteArray(0); size = 0 }
}
