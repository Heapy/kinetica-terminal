package io.heapy.kinetica.terminal

/** Owned, tightly packed, unpremultiplied RGB/RGBA bytes. No host or renderer objects. */
internal class TerminalGraphicsPixels(val width: Int, val height: Int, val format: Int, val data: ByteArray)

internal enum class TerminalGraphicsDecodeError {
    UNSUPPORTED_FORMAT, DIMENSIONS_REQUIRED, DIMENSIONS_TOO_LARGE, INVALID_DATA,
    INSUFFICIENT_DATA, DECOMPRESSION_FAILED, RESOURCE_LIMIT,
}

internal class TerminalGraphicsDecodeException(val error: TerminalGraphicsDecodeError) : Exception(error.name)

/** Each encoded/decoded buffer is bounded; PNG scanlines and DEFLATE history use bounded scratch space. */
internal class TerminalGraphicsDecodeLimits(
    val maxEncodedBytes: Int = 400 * 1024 * 1024,
    val maxDecodedBytes: Int = 400 * 1024 * 1024,
    val maxDimension: Int = 10_000,
) {
    init { require(maxEncodedBytes >= 0 && maxDecodedBytes >= 0 && maxDimension in 1..10_000) }
}

/** Decodes a complete transmission. Chunk assembly and external-media access belong to the caller.
 * With [takeDataOwnership], the caller relinquishes the input buffer; exact raw pixels can reuse it.
 * Borrowed input (the default), PNG, compressed input and excess frame tails still need owned output. */
internal fun decodeTerminalGraphicsImage(
    format: Int,
    width: Long,
    height: Long,
    compressed: Boolean,
    data: ByteArray,
    limits: TerminalGraphicsDecodeLimits = TerminalGraphicsDecodeLimits(),
    animationFrame: Boolean = false,
    allocateInflated: (Int) -> ByteArray = { ByteArray(it) },
    takeDataOwnership: Boolean = false,
): TerminalGraphicsPixels {
    if (format != 24 && format != 32 && format != 100) graphicsDecodeFailure(TerminalGraphicsDecodeError.UNSUPPORTED_FORMAT)
    if (data.size > limits.maxEncodedBytes) graphicsDecodeFailure(TerminalGraphicsDecodeError.RESOURCE_LIMIT)
    // Ghostty validates decompression before dimensions, and truncates excess raw frame data.
    // Keep that error/acceptance order without retaining an arbitrarily large discarded tail.
    var dimensionError: TerminalGraphicsDecodeException? = null
    val length = if (format == 100) 0 else try { graphicsPixelLength(width, height, format / 8, limits) }
        catch (error: TerminalGraphicsDecodeException) { dimensionError = error; 0 }
    fun validateLength(actual: Int) {
        dimensionError?.let { throw it }
        if (animationFrame && actual < length) graphicsDecodeFailure(TerminalGraphicsDecodeError.INSUFFICIENT_DATA)
        if (!animationFrame && actual != length) graphicsDecodeFailure(TerminalGraphicsDecodeError.INVALID_DATA)
    }
    if (!compressed) {
        if (format == 100) return decodeTerminalGraphicsPng(data, limits)
        validateLength(data.size)
        val pixels = if (takeDataOwnership && data.size == length) data else data.copyOfRange(0, length)
        return TerminalGraphicsPixels(width.toInt(), height.toInt(), format, pixels)
    }
    // PNG needs its encoded chunks for the second decode stage. Raw pixels need only their
    // declared prefix; the inflater still maintains history/checksum and validates every byte.
    val retainedLimit = if (format == 100) limits.maxEncodedBytes else minOf(length, limits.maxEncodedBytes)
    val bytes = GraphicsBytes(retainedLimit, allocateInflated)
    var offset = 0
    var inflatedSize = 0
    try {
        inflateTerminalGraphicsZlib({ if (offset < data.size) data[offset++].toInt() and 255 else -1 }, limits.maxEncodedBytes) { value ->
            if (inflatedSize < retainedLimit) bytes.add(value)
            inflatedSize++
        }
    } catch (_: TerminalGraphicsZlibException) { graphicsDecodeFailure(TerminalGraphicsDecodeError.DECOMPRESSION_FAILED) }
    if (format == 100) return decodeTerminalGraphicsPng(bytes.take(), limits)
    validateLength(inflatedSize)
    // The bounded collector already owns exactly the raw pixel prefix: no second full copy.
    return TerminalGraphicsPixels(width.toInt(), height.toInt(), format, bytes.take())
}

internal fun graphicsPixelLength(width: Long, height: Long, bpp: Int, limits: TerminalGraphicsDecodeLimits): Int {
    if (width <= 0 || height <= 0) graphicsDecodeFailure(TerminalGraphicsDecodeError.DIMENSIONS_REQUIRED)
    if (width > limits.maxDimension || height > limits.maxDimension) graphicsDecodeFailure(TerminalGraphicsDecodeError.DIMENSIONS_TOO_LARGE)
    val size = width * height * bpp
    if (size > limits.maxDecodedBytes) graphicsDecodeFailure(TerminalGraphicsDecodeError.RESOURCE_LIMIT)
    return size.toInt()
}

internal fun graphicsDecodeFailure(error: TerminalGraphicsDecodeError = TerminalGraphicsDecodeError.INVALID_DATA): Nothing =
    throw TerminalGraphicsDecodeException(error)

private class GraphicsBytes(private val limit: Int, private val allocate: (Int) -> ByteArray) {
    private var data = ByteArray(0)
    private var size = 0
    fun add(value: Int) {
        if (size == limit) graphicsDecodeFailure(TerminalGraphicsDecodeError.RESOURCE_LIMIT)
        if (size == data.size) {
            val capacity = maxOf(256L, data.size.toLong() * 2).coerceAtMost(limit.toLong()).toInt()
            val grown = allocate(capacity)
            require(grown.size == capacity)
            data.copyInto(grown); data = grown
        }
        data[size++] = value.toByte()
    }
    fun take(): ByteArray = if (data.size == size) data else data.copyOf(size)
}
