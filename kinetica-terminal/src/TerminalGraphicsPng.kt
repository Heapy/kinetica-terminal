package io.heapy.kinetica.terminal

import kotlin.math.abs

// PNG Third Edition: https://www.w3.org/TR/png-3/
// Static default image, all standard color types/depths and Adam7. APNG animation is
// separate from Kitty's frame protocol; ancillary animation/color-profile chunks are ignored.
/** Reads only the valid prefix of a borrowed buffer; spare collector capacity is not PNG data. */
internal fun decodeTerminalGraphicsPng(data: ByteArray, limits: TerminalGraphicsDecodeLimits,
    length: Int = data.size): TerminalGraphicsPixels {
    require(length in 0..data.size)
    return GraphicsPng(data, length, limits).decode()
}

private class GraphicsPng(private val data: ByteArray, private val validLength: Int, private val limits: TerminalGraphicsDecodeLimits) {
    private var width = 0
    private var height = 0
    private var depth = 0
    private var color = 0
    private var channels = 0
    private var interlaced = false
    private var palette: ByteArray? = null
    private var transparency: ByteArray? = null
    private var idatStart = -1
    private var idatEnd = -1
    private var pixelLength = 0

    private fun byte(offset: Int): Int = data[offset].toInt() and 255
    private fun uint(offset: Int): Long = ((byte(offset).toLong() shl 24) or (byte(offset + 1).toLong() shl 16) or
        (byte(offset + 2).toLong() shl 8) or byte(offset + 3).toLong())
    private fun type(offset: Int): Int = uint(offset).toInt()

    fun decode(): TerminalGraphicsPixels {
        if (validLength > limits.maxEncodedBytes) graphicsDecodeFailure(TerminalGraphicsDecodeError.RESOURCE_LIMIT)
        if (validLength < 8 || PNG_SIGNATURE.indices.any { byte(it) != PNG_SIGNATURE[it] }) graphicsDecodeFailure()
        var offset = 8
        var ended = false
        var idatClosed = false
        while (offset < validLength) {
            if (validLength - offset < 12) graphicsDecodeFailure()
            val lengthLong = uint(offset)
            if (lengthLong > validLength - offset - 12) graphicsDecodeFailure()
            val length = lengthLong.toInt()
            val start = offset + 8
            val end = start + length
            for (i in offset + 4 until start) if (byte(i) !in 65..90 && byte(i) !in 97..122) graphicsDecodeFailure()
            if (byte(offset + 6) and 32 != 0) graphicsDecodeFailure()
            var crc = -1
            for (i in offset + 4 until end) crc = CRC_TABLE[(crc xor byte(i)) and 255] xor (crc ushr 8)
            if ((crc.inv().toLong() and 0xffffffffL) != uint(end)) graphicsDecodeFailure()
            val kind = type(offset + 4)
            if (offset == 8 && kind != IHDR) graphicsDecodeFailure()
            if (idatStart >= 0 && kind != IDAT) idatClosed = true
            when (kind) {
                IHDR -> {
                    if (offset != 8 || length != 13) graphicsDecodeFailure()
                    pixelLength = graphicsPixelLength(uint(start), uint(start + 4), 4, limits)
                    width = uint(start).toInt(); height = uint(start + 4).toInt()
                    depth = byte(start + 8); color = byte(start + 9)
                    channels = when (color) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4; else -> graphicsDecodeFailure() }
                    val validDepth = when (color) { 0 -> depth in intArrayOf(1,2,4,8,16); 3 -> depth in intArrayOf(1,2,4,8); else -> depth == 8 || depth == 16 }
                    if (!validDepth || byte(start + 10) != 0 || byte(start + 11) != 0 || byte(start + 12) !in 0..1) graphicsDecodeFailure()
                    interlaced = byte(start + 12) == 1
                }
                PLTE -> {
                    if (palette != null || transparency != null || idatStart >= 0 || color == 0 || color == 4 || length == 0 || length > 768 || length % 3 != 0) graphicsDecodeFailure()
                    if (color == 3 && length / 3 > (1 shl depth)) graphicsDecodeFailure()
                    palette = data.copyOfRange(start, end)
                }
                TRNS -> {
                    if (transparency != null || idatStart >= 0) graphicsDecodeFailure()
                    when (color) {
                        0 -> if (length != 2) graphicsDecodeFailure()
                        2 -> if (length != 6) graphicsDecodeFailure()
                        3 -> if (palette == null || length == 0 || length > palette!!.size / 3) graphicsDecodeFailure()
                        else -> graphicsDecodeFailure()
                    }
                    transparency = data.copyOfRange(start, end)
                }
                IDAT -> {
                    if (idatClosed || color == 3 && palette == null) graphicsDecodeFailure()
                    if (idatStart < 0) idatStart = offset
                    idatEnd = end + 4
                }
                IEND -> {
                    if (length != 0 || idatStart < 0) graphicsDecodeFailure()
                    ended = true
                    break
                }
                else -> if (byte(offset + 4) and 32 == 0) graphicsDecodeFailure() // Unknown critical chunk.
            }
            offset = end + 4
        }
        if (!ended) graphicsDecodeFailure()
        val rows = GraphicsPngRows(width, height, depth, color, channels, interlaced, palette, transparency, pixelLength)
        // Read contiguous IDAT chunks without concatenating them or allocating a list per chunk.
        var chunk = idatStart
        var position = chunk + 8
        var chunkEnd = position + uint(chunk).toInt()
        fun nextByte(): Int {
            while (position == chunkEnd) {
                chunk = chunkEnd + 4
                if (chunk >= idatEnd) return -1
                position = chunk + 8
                chunkEnd = position + uint(chunk).toInt()
            }
            return byte(position++)
        }
        try {
            inflateTerminalGraphicsZlib(::nextByte, rows.expectedBytes, rows::accept)
        } catch (_: TerminalGraphicsZlibException) { graphicsDecodeFailure() }
        if (!rows.complete) graphicsDecodeFailure()
        return TerminalGraphicsPixels(width, height, 32, rows.pixels)
    }

    private companion object {
        val PNG_SIGNATURE = intArrayOf(137,80,78,71,13,10,26,10)
        const val IHDR = 0x49484452
        const val PLTE = 0x504c5445
        const val TRNS = 0x74524e53
        const val IDAT = 0x49444154
        const val IEND = 0x49454e44
        val CRC_TABLE = IntArray(256) { value ->
            var crc = value
            repeat(8) { crc = (crc ushr 1) xor (if (crc and 1 != 0) 0xedb88320.toInt() else 0) }
            crc
        }
    }
}

private class GraphicsPngRows(
    private val width: Int,
    private val height: Int,
    private val depth: Int,
    private val color: Int,
    private val channels: Int,
    private val interlaced: Boolean,
    private val palette: ByteArray?,
    private val transparency: ByteArray?,
    pixelLength: Int,
) {
    val pixels = ByteArray(pixelLength)
    private val bpp = maxOf(1, (channels * depth + 7) / 8)
    private var previous = ByteArray((width * channels * depth + 7) / 8)
    private var current = ByteArray(previous.size)
    private var pass = -1
    private var passWidth = 0
    private var passHeight = 0
    private var row = 0
    private var rowBytes = 0
    private var position = -1
    private var filter = 0
    val complete: Boolean get() = pass == (if (interlaced) 7 else 1)
    val expectedBytes: Int = (0 until if (interlaced) 7 else 1).sumOf { p ->
        val w = passSize(width, startX(p), stepX(p))
        val h = passSize(height, startY(p), stepY(p))
        if (w == 0 || h == 0) 0 else ((w * channels * depth + 7) / 8 + 1) * h
    }

    init { nextPass() }

    private fun startX(p: Int): Int = if (interlaced) START_X[p] else 0
    private fun startY(p: Int): Int = if (interlaced) START_Y[p] else 0
    private fun stepX(p: Int): Int = if (interlaced) STEP_X[p] else 1
    private fun stepY(p: Int): Int = if (interlaced) STEP_Y[p] else 1
    private fun nextPass() {
        do {
            pass++
            if (complete) return
            passWidth = passSize(width, startX(pass), stepX(pass))
            passHeight = passSize(height, startY(pass), stepY(pass))
        } while (passWidth == 0 || passHeight == 0)
        row = 0; rowBytes = (passWidth * channels * depth + 7) / 8
        previous.fill(0, 0, rowBytes)
    }

    fun accept(value: Int) {
        if (complete) graphicsDecodeFailure()
        if (position == -1) {
            if (value !in 0..4) graphicsDecodeFailure()
            filter = value; position = 0
            return
        }
        val left = if (position >= bpp) current[position - bpp].toInt() and 255 else 0
        val up = previous[position].toInt() and 255
        val upperLeft = if (position >= bpp) previous[position - bpp].toInt() and 255 else 0
        val prediction = when (filter) { 0 -> 0; 1 -> left; 2 -> up; 3 -> (left + up) / 2; else -> paeth(left, up, upperLeft) }
        current[position++] = (value + prediction).toByte()
        if (position == rowBytes) {
            writePixels()
            val swap = previous; previous = current; current = swap
            position = -1
            if (++row == passHeight) nextPass()
        }
    }

    private fun sample(index: Int): Int = when (depth) {
        16 -> ((current[index * 2].toInt() and 255) shl 8) or (current[index * 2 + 1].toInt() and 255)
        8 -> current[index].toInt() and 255
        else -> (current[index * depth / 8].toInt() ushr (8 - depth - index * depth % 8)) and ((1 shl depth) - 1)
    }
    private fun eight(value: Int): Int = when (depth) { 16 -> value ushr 8; 8 -> value; else -> value * 255 / ((1 shl depth) - 1) }
    private fun transparentSample(index: Int): Int = transparency?.let {
        ((it[index * 2].toInt() and 255) shl 8) or (it[index * 2 + 1].toInt() and 255)
    } ?: -1

    private fun writePixels() {
        val y = startY(pass) + row * stepY(pass)
        for (x in 0 until passWidth) {
            val offset = (y * width + startX(pass) + x * stepX(pass)) * 4
            val i = x * channels
            when (color) {
                0, 4 -> {
                    val gray = sample(i)
                    val value = eight(gray).toByte()
                    pixels[offset] = value; pixels[offset + 1] = value; pixels[offset + 2] = value
                    pixels[offset + 3] = (if (color == 4) eight(sample(i + 1)) else if (gray == transparentSample(0)) 0 else 255).toByte()
                }
                2, 6 -> {
                    val red = sample(i); val green = sample(i + 1); val blue = sample(i + 2)
                    pixels[offset] = eight(red).toByte(); pixels[offset + 1] = eight(green).toByte(); pixels[offset + 2] = eight(blue).toByte()
                    pixels[offset + 3] = (if (color == 6) eight(sample(i + 3)) else if (
                        red == transparentSample(0) && green == transparentSample(1) && blue == transparentSample(2)) 0 else 255).toByte()
                }
                3 -> {
                    val index = sample(i)
                    val table = palette ?: graphicsDecodeFailure()
                    if (index >= table.size / 3) graphicsDecodeFailure()
                    table.copyInto(pixels, offset, index * 3, index * 3 + 3)
                    pixels[offset + 3] = transparency?.getOrNull(index) ?: 255.toByte()
                }
            }
        }
    }

    private companion object {
        val START_X = intArrayOf(0,4,0,2,0,1,0)
        val START_Y = intArrayOf(0,0,4,0,2,0,1)
        val STEP_X = intArrayOf(8,8,4,4,2,2,1)
        val STEP_Y = intArrayOf(8,8,8,4,4,2,2)
        fun passSize(size: Int, start: Int, step: Int): Int = if (size <= start) 0 else (size - start + step - 1) / step
        fun paeth(a: Int, b: Int, c: Int): Int {
            val p = a + b - c
            val pa = abs(p - a); val pb = abs(p - b); val pc = abs(p - c)
            return when { pa <= pb && pa <= pc -> a; pb <= pc -> b; else -> c }
        }
    }
}
