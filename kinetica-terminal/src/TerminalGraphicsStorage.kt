// Image loading/storage semantics follow pinned Ghostty kitty/graphics_exec.zig and graphics_storage.zig.
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors. MIT licensed.
// Pinned source and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

internal class TerminalGraphicsStoredImage(
    val id: Long,
    val number: Long,
    pixels: TerminalGraphicsPixels,
    val transient: Boolean,
    val implicitId: Boolean,
    internal var age: Long,
) {
    val animation = TerminalGraphicsAnimation(pixels)
    val pixels: TerminalGraphicsPixels get() = animation.rootPixels
    // Object identity is a unique content generation even across stores, resets and ID reuse.
    val generation: Any get() = animation.contentGeneration
    private var content = TerminalGraphicsImageContent(id, pixels)
    val renderContent: TerminalGraphicsImageContent get() {
        synchronizeRenderContent()
        return content
    }
    internal fun synchronizeRenderContent() {
        if (content.pixels !== animation.currentPixels) content = TerminalGraphicsImageContent(id, animation.currentPixels)
    }
    internal var accountedBytes: Long = pixels.data.size.toLong()
    internal var placementCount: Int = 0
}

internal data class TerminalGraphicsResponse(
    val id: Long = 0,
    val number: Long = 0,
    val placement: Long = 0,
    val message: String = "OK",
    val frame: Long = 0,
) {
    fun encode(quiet: Int = 0): String? {
        if (quiet >= 2 || quiet == 1 && message == "OK" || id == 0L && number == 0L) return null
        return buildString {
            append("\u001b_G")
            if (id != 0L) append("i=$id")
            if (number != 0L) { if (id != 0L) append(','); append("I=$number") }
            if (placement != 0L) append(",p=$placement")
            if (frame != 0L) append(",r=$frame")
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
 * One screen's decoded images, animation state and in-progress image/frame transfer. The
 * placement executor owns geometry and calls [remove] for selected image IDs. This component
 * delegates external reads to the explicitly supplied host and does not advertise graphics
 * support by itself. It contains no platform I/O implementation.
 */
internal class TerminalGraphicsStorage(
    limit: Int = 320_000_000,
    private val decodeLimits: TerminalGraphicsDecodeLimits = TerminalGraphicsDecodeLimits(),
    private val removed: (TerminalGraphicsStoredImage) -> Unit = {},
    private val media: TerminalGraphicsMedia? = null,
    private val allocateTransfer: (Int) -> ByteArray = { ByteArray(it) },
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
    private var animationIdleGeneration: Any? = null
    val enabled: Boolean get() = limit != 0
    val imageCount: Int get() = images.size
    val loadingBytes: Int get() = loading?.data?.size ?: 0
    val retainedLoadingBytes: Int get() = loading?.data?.capacity ?: 0
    val hasPendingTransfer: Boolean get() = loading != null

    fun image(id: Long): TerminalGraphicsStoredImage? = images[id]
    fun placementChanged() { generation = Any() }
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
        totalBytes -= image.accountedBytes
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
            decode(command, transmissionData(command))
            null
        } catch (error: TerminalGraphicsDecodeException) { error.messageForProtocol() }
        catch (error: TerminalGraphicsMediaException) { error.messageForProtocol() }
        return TerminalGraphicsTransferResult(response(command, error ?: "OK"), command.quiet)
    }

    /**
     * Direct transmit-like commands continue an existing load with its original header and
     * target generation. External media abandon the load before starting a new transmission.
     */
    fun transmit(command: TerminalGraphicsCommand): TerminalGraphicsTransferResult {
        require(command.action in "tTf")
        if (!enabled) return TerminalGraphicsTransferResult()
        conflictingIdentifiers(command)?.let { return TerminalGraphicsTransferResult(it, command.quiet) }
        if (command.medium != 'd') abortTransfer()
        val current = loading
        val quiet = if (current != null && command.quiet == 0) current.quiet else command.quiet
        if (current != null && command.quiet != 0) current.quiet = quiet
        val initial = current?.header ?: command
        fun failed(message: String) = TerminalGraphicsTransferResult(response(initial, message).let {
            if (initial.action == 'f') it.copy(id = current?.id ?: it.id, frame = if (current == null) initial.unsigned('r') else 0) else it
        }, quiet)
        val load: Loading
        if (current != null) {
            // Failure to append leaves the previous chunks intact, as in Ghostty.
            if (!current.data.append(command.data, final = !command.moreChunks)) return failed("EINVAL: invalid data")
            if (command.moreChunks) return TerminalGraphicsTransferResult(quiet = quiet, pending = true)
            load = current
            loading = null
        } else {
            val target = if (command.action == 'f') {
                if (command.imageId == 0L && command.imageNumber == 0L) return failed("EINVAL: image ID or number required")
                find(command) ?: return failed("ENOENT: image not found")
            } else null
            // Replacement destroys the old image/placements at the first chunk, even if loading fails.
            if (target == null && command.imageId != 0L) remove(command.imageId)
            fun startFailure(message: String) = failed(message).let { result ->
                if (target == null) result else TerminalGraphicsTransferResult(result.response?.copy(id = target.id), quiet)
            }
            validateMedium(command)?.let { return startFailure(it) }
            val data = try { transmissionData(command) }
            catch (error: TerminalGraphicsMediaException) { return startFailure(error.messageForProtocol()) }
            val bytes = GraphicsTransferBytes(decodeLimits.maxEncodedBytes, allocateTransfer)
            if (!bytes.append(data, final = !command.moreChunks)) return startFailure("EINVAL: invalid data")
            val implicit = target == null && command.imageId == 0L && command.imageNumber == 0L
            val id = target?.id ?: if (command.imageId != 0L) command.imageId else nextId(implicit)
            load = Loading(command.withoutData(), id, implicit, quiet, bytes, target?.generation)
            if (command.moreChunks) {
                loading = load
                return TerminalGraphicsTransferResult(quiet = quiet, pending = true)
            }
        }
        if (load.header.action == 'f') return completeFrame(load, quiet)
        val pixels = try { decode(load.header, load.data.take(), takeDataOwnership = true) }
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

    private fun evictTo(target: Long, except: Long? = null) {
        if (totalBytes <= target) return
        // Sort once to avoid quadratic work when a large image evicts many tiny ones.
        val victims = images.values.filter { it.id != except }.sortedWith(compareBy<TerminalGraphicsStoredImage>(
            { (if (it.transient) 0 else 1) + (if (it.placementCount > 0) 2 else 0) }, { it.age }, { it.id }))
        for (victim in victims) {
            remove(victim.id)
            if (totalBytes <= target) return
        }
    }

    private fun validateMedium(command: TerminalGraphicsCommand): String? = when {
        command.format < 0 -> "EINVAL: unsupported format"
        command.medium != 'd' && media == null -> "EINVAL: unsupported medium"
        else -> null
    }

    private fun transmissionData(command: TerminalGraphicsCommand): ByteArray {
        if (command.medium == 'd') return command.data
        val medium = when (command.medium) {
            'f' -> TerminalGraphicsMedium.FILE
            't' -> TerminalGraphicsMedium.TEMPORARY_FILE
            's' -> TerminalGraphicsMedium.SHARED_MEMORY
            else -> throw TerminalGraphicsMediaException(TerminalGraphicsMediaResult.UNSUPPORTED)
        }
        val request = TerminalGraphicsMediaRequest(medium, command.data, command.unsigned('O'), command.unsigned('S'),
            command.format, command.unsigned('s'), command.unsigned('v'), command.compressed,
            decodeLimits.maxEncodedBytes, decodeLimits.maxDimension)
        val result = try { media?.read(request) ?: TerminalGraphicsMediaResult.UNSUPPORTED }
        catch (_: Exception) { TerminalGraphicsMediaResult.INVALID_DATA }
        if (result !is TerminalGraphicsMediaResult.Data) throw TerminalGraphicsMediaException(result)
        if (result.bytes.size > decodeLimits.maxEncodedBytes || request.size > 0 && result.bytes.size.toLong() != request.size)
            throw TerminalGraphicsMediaException(TerminalGraphicsMediaResult.INVALID_DATA)
        return result.bytes
    }

    private fun decode(command: TerminalGraphicsCommand, bytes: ByteArray, takeDataOwnership: Boolean = false): TerminalGraphicsPixels =
        decodeTerminalGraphicsImage(command.format, command.unsigned('s'), command.unsigned('v'), command.compressed, bytes, decodeLimits,
            animationFrame = command.action == 'f', takeDataOwnership = takeDataOwnership)

    private class Loading(val header: TerminalGraphicsCommand, val id: Long, val implicit: Boolean,
        var quiet: Int, val data: GraphicsTransferBytes, val targetGeneration: Any? = null)

    private fun find(command: TerminalGraphicsCommand) =
        if (command.imageId != 0L) image(command.imageId) else imageByNumber(command.imageNumber)

    /** Reconcile after root RGB conversion as well as after append/delete, including failed loads. */
    private fun account(image: TerminalGraphicsStoredImage) {
        val size = image.animation.byteCount
        totalBytes += size - image.accountedBytes
        image.accountedBytes = size
    }

    private inline fun <T> mutate(image: TerminalGraphicsStoredImage, action: () -> T): T {
        val before = image.animation.mutation
        val content = image.generation
        try { return action() } finally {
            account(image)
            image.synchronizeRenderContent()
            if (content !== image.generation) image.age = ++age
            if (before !== image.animation.mutation) generation = Any()
        }
    }

    private fun completeFrame(load: Loading, quiet: Int): TerminalGraphicsTransferResult {
        // LoadingImage's saved response has no frame number until completion resolves it.
        var response = response(load.header).copy(id = load.id)
        fun failed(message: String) = TerminalGraphicsTransferResult(response.copy(message = message), quiet)
        val target = find(load.header)?.takeIf { it.generation === load.targetGeneration }
            ?: return failed("ENOENT: image not found")
        val pixels = try { decode(load.header, load.data.take(), takeDataOwnership = true) }
        catch (error: TerminalGraphicsDecodeException) { return failed(error.messageForProtocol()) }
        if (pixels.width > target.pixels.width || pixels.height > target.pixels.height)
            return failed("EINVAL: frame dimensions exceed image")
        val requested = load.header.unsigned('r')
        response = response.copy(frame = if (requested == 0L || requested > target.animation.frameCount + 1L)
            target.animation.frameCount + 1L else requested)
        try {
            mutate(target) {
                target.animation.load(load.header, pixels) { bytes ->
                    account(target)
                    val excess = totalBytes + bytes - limit
                    if (bytes > limit || excess > limit) false else {
                        evictTo(limit.toLong() - bytes, except = target.id)
                        totalBytes + bytes <= limit
                    }
                }
            }
        } catch (error: TerminalGraphicsAnimationException) { return failed(error.message!!) }
        return TerminalGraphicsTransferResult(response, quiet)
    }

    fun animate(command: TerminalGraphicsCommand): TerminalGraphicsTransferResult {
        require(command.action in "ac")
        if (!enabled) return TerminalGraphicsTransferResult()
        conflictingIdentifiers(command)?.let { return TerminalGraphicsTransferResult(it, command.quiet) }
        var response = response(command)
        fun failed(message: String) = TerminalGraphicsTransferResult(response.copy(message = message), command.quiet)
        if (command.imageId == 0L && command.imageNumber == 0L) return failed("EINVAL: image ID or number required")
        val image = find(command) ?: return failed("ENOENT: image not found")
        // Successful control has no reply; its errors retain the client's identifier fields.
        if (command.action == 'c') response = response.copy(id = image.id)
        try {
            mutate(image) { if (command.action == 'a') image.animation.control(command) else image.animation.compose(command) }
        } catch (error: TerminalGraphicsAnimationException) { return failed(error.message!!) }
        return TerminalGraphicsTransferResult(if (command.action == 'a') null else response, command.quiet)
    }

    fun deleteFrame(id: Long, number: Long, deleteImage: Boolean) {
        val image = images[id] ?: return
        if (mutate(image) { image.animation.delete(number, deleteImage) }) remove(id)
    }

    fun animationTick(now: Long): Long? {
        require(now >= 0)
        if (animationIdleGeneration === generation) return null
        var delay: Long? = null
        for (image in images.values) {
            val next = mutate(image) { image.animation.tick(now, image.placementCount > 0) }
            if (next != null) delay = delay?.let { minOf(it, next) } ?: next
        }
        animationIdleGeneration = if (delay == null) generation else null
        return delay
    }
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

private class GraphicsTransferBytes(private val limit: Int, private val allocate: (Int) -> ByteArray) {
    private var bytes = ByteArray(0)
    var size = 0
        private set
    val capacity: Int get() = bytes.size
    fun append(data: ByteArray, final: Boolean): Boolean {
        if (data.size > limit - size) return false
        val required = size + data.size
        if (required > bytes.size) {
            // The final chunk needs no growth reserve, so take() can transfer the exact buffer.
            val capacity = if (final) required else
                maxOf(required.toLong(), maxOf(256L, bytes.size.toLong() * 2)).coerceAtMost(limit.toLong()).toInt()
            bytes = resized(capacity)
        }
        data.copyInto(bytes, size)
        size = required
        return true
    }
    private fun resized(capacity: Int): ByteArray = allocate(capacity).also {
        require(it.size == capacity)
        bytes.copyInto(it, endIndex = size)
    }
    fun take(): ByteArray = (if (size == bytes.size) bytes else resized(size)).also { bytes = ByteArray(0); size = 0 }
}
