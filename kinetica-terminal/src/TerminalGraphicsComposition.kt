// Pixel operations follow Ghostty kitty/graphics_pixel.zig (MIT, see GHOSTTY-LICENSE).
// Source-over arithmetic adapted from Wuffs v0.4 (MIT, see ../third-party/WUFFS-LICENSE).
package io.heapy.kinetica.terminal

internal fun TerminalGraphicsPixels.animationRgba(): TerminalGraphicsPixels {
    if (format == 32) return this // Pixel buffers are immutable; the destination copies before editing.
    require(format == 24)
    val bytes = ByteArray(width * height * 4)
    for (pixel in 0 until width * height) {
        for (channel in 0..2) bytes[pixel * 4 + channel] = data[pixel * 3 + channel]
        bytes[pixel * 4 + 3] = 255.toByte()
    }
    return TerminalGraphicsPixels(width,height,32,bytes)
}

internal fun animationBackground(length: Int, rgba: Long): ByteArray {
    require(length >= 0 && length % 4 == 0 && rgba in 0..0xffffffffL)
    val bytes = ByteArray(length)
    if (rgba != 0L) for (i in bytes.indices step 4) for (c in 0..3) bytes[i+c] = (rgba ushr (24-c*8)).toByte()
    return bytes
}

/** Callers own [destination]. A transmitted rectangle clips; an explicit source rectangle
 * has already passed command-level bounds checks. Coordinates stay Long until clipped. */
internal fun animationCompose(destination: ByteArray, destinationWidth: Int, destinationHeight: Int,
    source: ByteArray, sourceWidth: Int, sourceHeight: Int, x: Long, y: Long,
    sourceX: Int = 0, sourceY: Int = 0, width: Int = sourceWidth, height: Int = sourceHeight, overwrite: Boolean) {
    require(x >= 0 && y >= 0 && sourceX >= 0 && sourceY >= 0 && width >= 0 && height >= 0 &&
        sourceX.toLong() + width <= sourceWidth && sourceY.toLong() + height <= sourceHeight)
    if (x >= destinationWidth || y >= destinationHeight) return
    val clippedWidth = minOf(width.toLong(),destinationWidth - x).toInt()
    val clippedHeight = minOf(height.toLong(),destinationHeight - y).toInt()
    for (row in 0 until clippedHeight) {
        val target = ((y.toInt() + row) * destinationWidth + x.toInt()) * 4
        val start = ((sourceY + row) * sourceWidth + sourceX) * 4
        if (overwrite) source.copyInto(destination,target,start,start+clippedWidth*4)
        else for (column in 0 until clippedWidth) animationBlendPixel(destination,target+column*4,source,start+column*4)
    }
}

/** Wuffs expands channels to 16 bits, uses integer divisions, then truncates back to 8 bits. */
private fun animationBlendPixel(dst: ByteArray, d: Int, src: ByteArray, s: Int) {
    val da = (dst[d+3].toInt() and 255).toLong() * 257
    if (da == 0L) { src.copyInto(dst,d,s,s+4); return }
    val sa = (src[s+3].toInt() and 255).toLong() * 257
    val inverse = 65535 - sa
    val alpha = sa + da * inverse / 65535
    for (c in 0..2) {
        val destination = (dst[d+c].toInt() and 255).toLong() * 257 * da / 65535
        val source = (src[s+c].toInt() and 255).toLong() * 257
        val premultiplied = (source * sa + destination * inverse) / 65535
        dst[d+c] = ((if (alpha == 0L) premultiplied else premultiplied * 65535 / alpha) ushr 8).toByte()
    }
    dst[d+3] = (alpha ushr 8).toByte()
}
