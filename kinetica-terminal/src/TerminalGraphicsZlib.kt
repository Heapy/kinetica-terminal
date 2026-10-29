package io.heapy.kinetica.terminal

/** RFC 1950/1951 decoder. A fixed sliding window bounds memory independently of output size. */
internal class TerminalGraphicsZlibException : Exception("Invalid or oversized zlib stream")

internal fun inflateTerminalGraphicsZlib(
    nextByte: () -> Int,
    maxOutputBytes: Int,
    output: (Int) -> Unit,
) {
    require(maxOutputBytes >= 0)
    GraphicsInflater(nextByte, maxOutputBytes, output).inflate()
}

private class GraphicsInflater(
    private val source: () -> Int,
    private val limit: Int,
    private val output: (Int) -> Unit,
) {
    private val history = ByteArray(32768)
    private var size = 0
    private var bits = 0
    private var bitCount = 0
    private var adlerA = 1
    private var adlerB = 0
    private var window = 32768

    private fun byte(): Int = source().also { if (it !in 0..255) invalidZlib() }
    private fun read(count: Int): Int {
        while (bitCount < count) { bits = bits or (byte() shl bitCount); bitCount += 8 }
        val value = bits and ((1 shl count) - 1)
        bits = bits ushr count; bitCount -= count
        return value
    }
    private fun emit(value: Int) {
        if (size == limit) invalidZlib()
        history[size and 32767] = value.toByte()
        size++
        adlerA += value
        if (adlerA >= 65521) adlerA -= 65521
        adlerB += adlerA
        if (adlerB >= 65521) adlerB -= 65521
        output(value)
    }

    fun inflate() {
        val cmf = byte()
        val flags = byte()
        // Preset dictionaries are forbidden for PNG and unsupported for Kitty image payloads.
        if (cmf and 15 != 8 || cmf ushr 4 > 7 || (cmf * 256 + flags) % 31 != 0 || flags and 32 != 0) invalidZlib()
        window = 1 shl ((cmf ushr 4) + 8)
        do {
            val last = read(1) != 0
            when (read(2)) {
                0 -> {
                    bits = 0; bitCount = 0
                    val length = byte() or (byte() shl 8)
                    val inverse = byte() or (byte() shl 8)
                    if (length xor inverse != 65535) invalidZlib()
                    repeat(length) { emit(byte()) }
                }
                1 -> compressed(FIXED_LITERALS, FIXED_DISTANCES)
                2 -> dynamic()
                else -> invalidZlib()
            }
        } while (!last)
        // No speculative byte reads: only padding from the final DEFLATE byte is discarded.
        bits = 0; bitCount = 0
        val expected = (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
        if (expected != ((adlerB shl 16) or adlerA)) invalidZlib()
        // Kitty payloads must consume the whole input; Ghostty rejects bytes after the stream.
        if (source() != -1) invalidZlib()
    }

    private fun dynamic() {
        val literalCount = read(5) + 257
        val distanceCount = read(5) + 1
        val codeCount = read(4) + 4
        if (literalCount > 286) invalidZlib()
        val codeLengths = IntArray(19)
        repeat(codeCount) { codeLengths[CODE_ORDER[it]] = read(3) }
        val codes = GraphicsHuffman(codeLengths, complete = true)
        val lengths = IntArray(literalCount + distanceCount)
        var index = 0
        while (index < lengths.size) {
            when (val code = symbol(codes)) {
                in 0..15 -> lengths[index++] = code
                16, 17, 18 -> {
                    if (code == 16 && index == 0) invalidZlib()
                    val value = if (code == 16) lengths[index - 1] else 0
                    val count = when (code) { 16 -> read(2) + 3; 17 -> read(3) + 3; else -> read(7) + 11 }
                    if (count > lengths.size - index) invalidZlib()
                    lengths.fill(value, index, index + count)
                    index += count
                }
                else -> invalidZlib()
            }
        }
        if (lengths[256] == 0) invalidZlib()
        compressed(GraphicsHuffman(lengths.copyOfRange(0, literalCount)),
            GraphicsHuffman(lengths.copyOfRange(literalCount, lengths.size), emptyAllowed = true))
    }

    private fun symbol(tree: GraphicsHuffman): Int {
        var code = 0
        var first = 0
        var index = 0
        for (length in 1..tree.maxLength) {
            code = (code shl 1) or read(1)
            val count = tree.counts[length]
            if (code >= first && code - first < count) return tree.symbols[index + code - first]
            index += count
            first = (first + count) shl 1
        }
        invalidZlib()
    }

    private fun compressed(literals: GraphicsHuffman, distances: GraphicsHuffman) {
        while (true) {
            val code = symbol(literals)
            when {
                code < 256 -> emit(code)
                code == 256 -> return
                code in 257..285 -> {
                    val length = LENGTH_BASE[code - 257] + read(LENGTH_BITS[code - 257])
                    val distanceCode = symbol(distances)
                    if (distanceCode >= DISTANCE_BASE.size) invalidZlib()
                    val distance = DISTANCE_BASE[distanceCode] + read(DISTANCE_BITS[distanceCode])
                    if (distance > size || distance > window || length > limit - size) invalidZlib()
                    repeat(length) { emit(history[(size - distance) and 32767].toInt() and 255) }
                }
                else -> invalidZlib()
            }
        }
    }

    private companion object {
        val CODE_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
        val LENGTH_BASE = intArrayOf(3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,35,43,51,59,67,83,99,115,131,163,195,227,258)
        val LENGTH_BITS = intArrayOf(0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,4,4,4,4,5,5,5,5,0)
        val DISTANCE_BASE = intArrayOf(1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,257,385,513,769,1025,1537,2049,3073,4097,6145,8193,12289,16385,24577)
        val DISTANCE_BITS = intArrayOf(0,0,0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,8,8,9,9,10,10,11,11,12,12,13,13)
        val FIXED_LITERALS = GraphicsHuffman(IntArray(288) { when (it) { in 0..143 -> 8; in 144..255 -> 9; in 256..279 -> 7; else -> 8 } })
        val FIXED_DISTANCES = GraphicsHuffman(IntArray(32) { 5 })
    }
}

private class GraphicsHuffman(lengths: IntArray, complete: Boolean = false, emptyAllowed: Boolean = false) {
    val counts = IntArray(16)
    val symbols = IntArray(lengths.size)
    val maxLength = lengths.maxOrNull() ?: 0
    init {
        for (length in lengths) {
            if (length !in 0..15) invalidZlib()
            if (length != 0) counts[length]++
        }
        var left = 1
        for (length in 1..15) {
            left = (left shl 1) - counts[length]
            if (left < 0) invalidZlib()
        }
        // RFC 1951 permits an unused distance alphabet and a single one-bit code.
        if (maxLength == 0) { if (!emptyAllowed) invalidZlib() }
        else if (left != 0 && (complete || maxLength != 1)) invalidZlib()
        val offsets = IntArray(16)
        for (length in 1..14) offsets[length + 1] = offsets[length] + counts[length]
        for (symbol in lengths.indices) if (lengths[symbol] != 0) symbols[offsets[lengths[symbol]]++] = symbol
    }
}

private fun invalidZlib(): Nothing = throw TerminalGraphicsZlibException()
