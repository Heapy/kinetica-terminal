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

/** Decodes a complete transmission. Chunk assembly and external-media access belong to the caller. */
internal fun decodeTerminalGraphicsImage(
    format: Int,
    width: Long,
    height: Long,
    compressed: Boolean,
    data: ByteArray,
    limits: TerminalGraphicsDecodeLimits = TerminalGraphicsDecodeLimits(),
    animationFrame: Boolean = false,
): TerminalGraphicsPixels {
    if (format != 24 && format != 32 && format != 100) graphicsDecodeFailure(TerminalGraphicsDecodeError.UNSUPPORTED_FORMAT)
    if (data.size > limits.maxEncodedBytes) graphicsDecodeFailure(TerminalGraphicsDecodeError.RESOURCE_LIMIT)
    val decoded = if (compressed) {
        val bytes = GraphicsBytes(limits.maxEncodedBytes)
        var offset = 0
        try {
            inflateTerminalGraphicsZlib({ if (offset < data.size) data[offset++].toInt() and 255 else -1 }, limits.maxEncodedBytes, bytes::add)
        } catch (_: TerminalGraphicsZlibException) { graphicsDecodeFailure(TerminalGraphicsDecodeError.DECOMPRESSION_FAILED) }
        bytes.take()
    } else data
    if (format == 100) return decodeTerminalGraphicsPng(decoded, limits)
    val length = graphicsPixelLength(width, height, format / 8, limits)
    if (animationFrame && decoded.size < length) graphicsDecodeFailure(TerminalGraphicsDecodeError.INSUFFICIENT_DATA)
    if ((!animationFrame && decoded.size != length)) graphicsDecodeFailure(TerminalGraphicsDecodeError.INVALID_DATA)
    return TerminalGraphicsPixels(width.toInt(), height.toInt(), format, decoded.copyOfRange(0, length))
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

private class GraphicsBytes(private val limit: Int) {
    private var data = ByteArray(0)
    private var size = 0
    fun add(value: Int) {
        if (size == limit) graphicsDecodeFailure(TerminalGraphicsDecodeError.RESOURCE_LIMIT)
        if (size == data.size) data = data.copyOf(maxOf(256L, data.size.toLong() * 2).coerceAtMost(limit.toLong()).toInt())
        data[size++] = value.toByte()
    }
    fun take(): ByteArray = if (data.size == size) data else data.copyOf(size)
}
