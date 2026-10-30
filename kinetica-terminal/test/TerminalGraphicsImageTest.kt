package io.heapy.kinetica.terminal

import kotlin.io.encoding.Base64
import kotlin.test.*

class TerminalGraphicsImageTest {
    private val smallLimits = TerminalGraphicsDecodeLimits(1024 * 1024, 1024 * 1024)

    private fun decode(format: Int, data: ByteArray, width: Long = 1, height: Long = 1,
        compressed: Boolean = false, frame: Boolean = false, limits: TerminalGraphicsDecodeLimits = smallLimits) =
        decodeTerminalGraphicsImage(format, width, height, compressed, data, limits, frame)

    @Test fun rgbAndRgbaLengthsDimensionsAndOwnedPixels() {
        for (format in listOf(24, 32)) {
            val input = ByteArray(2 * 3 * format / 8) { (it * 41).toByte() }
            val pixels = decode(format, input, 2, 3)
            assertEquals(2, pixels.width); assertEquals(3, pixels.height); assertEquals(format, pixels.format)
            assertContentEquals(input, pixels.data)
            input[0] = 99
            assertEquals(0, pixels.data[0].toInt())
            for (length in listOf(input.size - 1, input.size + 1)) failure(TerminalGraphicsDecodeError.INVALID_DATA) {
                decode(format, input.copyOf(length), 2, 3)
            }
            assertContentEquals(input, decode(format, input + byteArrayOf(12, 13), 2, 3, frame = true).data)
            failure(TerminalGraphicsDecodeError.INSUFFICIENT_DATA) { decode(format, input.copyOf(input.size - 1), 2, 3, frame = true) }
        }
        failure(TerminalGraphicsDecodeError.UNSUPPORTED_FORMAT) { decode(-1, byteArrayOf()) }
        for ((width, height) in listOf(0L to 1L, 1L to 0L)) failure(TerminalGraphicsDecodeError.DIMENSIONS_REQUIRED) { decode(32, byteArrayOf(), width, height) }
        for ((width, height) in listOf(10001L to 1L, 1L to 4294967295L, Long.MAX_VALUE to Long.MAX_VALUE)) {
            failure(TerminalGraphicsDecodeError.DIMENSIONS_TOO_LARGE) { decode(32, byteArrayOf(), width, height) }
        }
        failure(TerminalGraphicsDecodeError.RESOURCE_LIMIT) { decode(32, byteArrayOf(), 10000, 10000) }
        failure(TerminalGraphicsDecodeError.RESOURCE_LIMIT) { decode(32, ByteArray(4), limits = TerminalGraphicsDecodeLimits(3, 4)) }
        failure(TerminalGraphicsDecodeError.RESOURCE_LIMIT) { decode(32, ByteArray(4), limits = TerminalGraphicsDecodeLimits(4, 3)) }
        assertEquals(4, decode(32, ByteArray(4), limits = TerminalGraphicsDecodeLimits(4, 4)).data.size)
    }

    @Test fun allPngColorTypesDepthsFiltersAndAdam7MatchLibpng() {
        for (fixture in terminalGraphicsPngFixtures()) {
            // PNG's own dimensions override Kitty's s/v values, even absent or nonsensical ones.
            val pixels = decode(100, Base64.decode(fixture.png), 0, 4294967295)
            assertEquals(fixture.width, pixels.width, fixture.name)
            assertEquals(fixture.height, pixels.height, fixture.name)
            assertEquals(32, pixels.format, fixture.name)
            assertContentEquals(Base64.decode(fixture.rgba), pixels.data, fixture.name)
        }
    }

    @Test fun relinquishedRawBuffersBecomePixelsOnlyWhenTheirLengthIsExact() {
        for (format in listOf(24, 32)) for (frame in listOf(false, true)) {
            val input = ByteArray(6 * format / 8) { (it * 41).toByte() }
            val expected = input.copyOf()
            val pixels = decodeTerminalGraphicsImage(format, 2, 3, false, input, smallLimits,
                animationFrame = frame, takeDataOwnership = true)
            assertSame(input, pixels.data, "An owned, exact raw buffer needs no second full allocation")
            assertContentEquals(expected, pixels.data)
            val excess = expected + byteArrayOf(99, 98)
            if (frame) {
                val cropped = decodeTerminalGraphicsImage(format, 2, 3, false, excess, smallLimits,
                    animationFrame = true, takeDataOwnership = true)
                assertNotSame(excess, cropped.data)
                assertContentEquals(expected, cropped.data)
                assertEquals(expected.size, cropped.data.size, "Discarded frame tails must not stay resident")
            } else failure(TerminalGraphicsDecodeError.INVALID_DATA) {
                decodeTerminalGraphicsImage(format, 2, 3, false, excess, smallLimits, takeDataOwnership = true)
            }
            failure(TerminalGraphicsDecodeError.RESOURCE_LIMIT) {
                decodeTerminalGraphicsImage(format, 2, 3, false, expected,
                    TerminalGraphicsDecodeLimits(expected.size, expected.size - 1), takeDataOwnership = true)
            }
        }
    }

    @Test fun zlibCompressedRawAndPngPayloads() {
        for (format in listOf(24, 32)) {
            val pixels = ByteArray(format / 8 * 6) { (it * 59).toByte() }
            assertContentEquals(pixels, decode(format, storedZlib(pixels), 2, 3, compressed = true).data)
        }
        val fixture = terminalGraphicsPngFixtures().first()
        assertContentEquals(Base64.decode(fixture.rgba), decode(100, storedZlib(Base64.decode(fixture.png)), compressed = true).data)
        failure(TerminalGraphicsDecodeError.DECOMPRESSION_FAILED) { decode(32, storedZlib(ByteArray(4)).dropLast(1).toByteArray(), compressed = true) }
        failure(TerminalGraphicsDecodeError.DECOMPRESSION_FAILED) {
            val bomb = Base64.decode(terminalGraphicsZlibFixtures().first { it.name == "zlib_4_6_0" }.zlib)
            decode(32, bomb, compressed = true, limits = TerminalGraphicsDecodeLimits(200, 4))
        }
    }

    @Test fun pngRejectsTruncationCrcCorruptionAndOversizedHeaders() {
        val data = Base64.decode(terminalGraphicsPngFixtures().first().png)
        for (length in 0 until data.size) failure(TerminalGraphicsDecodeError.INVALID_DATA) { decode(100, data.copyOf(length)) }
        for (index in data.indices) {
            val damaged = data.copyOf(); damaged[index] = (damaged[index].toInt() xor 1).toByte()
            failure(TerminalGraphicsDecodeError.INVALID_DATA) { decode(100, damaged) }
        }
        val ihdr = data.copyOfRange(16, 29)
        for (width in listOf(0, 10001, Int.MAX_VALUE, -1)) {
            val changed = ihdr.copyOf(); writeInt(changed, 0, width)
            failure(if (width == 0) TerminalGraphicsDecodeError.DIMENSIONS_REQUIRED else TerminalGraphicsDecodeError.DIMENSIONS_TOO_LARGE) {
                decode(100, data.copyOfRange(0, 8) + pngChunk("IHDR", changed) + data.copyOfRange(33, data.size))
            }
        }
        failure(TerminalGraphicsDecodeError.RESOURCE_LIMIT) {
            decode(100, data, limits = TerminalGraphicsDecodeLimits(data.size, 4))
        }
    }

    @Test fun compressedRawFramesRetainOnlyPixelPrefixButValidateTheWholeStream() {
        val raw = ByteArray(60_000) { (it * 59).toByte() }
        val stream = storedZlib(raw)
        val allocations = mutableListOf<ByteArray>()
        fun bounded(data: ByteArray = stream, frame: Boolean = true, width: Long = 1, decodedLimit: Int = 4) =
            decodeTerminalGraphicsImage(32, width, 1, true, data,
                TerminalGraphicsDecodeLimits(65536, decodedLimit), frame, allocateInflated = { size ->
                    assertTrue(size <= 4, "A discarded frame tail must not become a temporary decoded buffer")
                    ByteArray(size).also { allocations += it }
                })
        val pixels = bounded()
        assertContentEquals(raw.copyOf(4), pixels.data)
        assertEquals(1, allocations.size)
        assertSame(allocations.single(), pixels.data, "The collector's owned prefix is the published pixel buffer")
        failure(TerminalGraphicsDecodeError.INVALID_DATA) { bounded(frame = false) }
        failure(TerminalGraphicsDecodeError.INSUFFICIENT_DATA) { bounded(storedZlib(raw.copyOf(3))) }
        // Corruption/truncation after the retained prefix must still reject the entire frame.
        val corrupt = stream.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        for (bad in listOf(corrupt, stream.copyOf(stream.size - 1), stream + byteArrayOf(0), stream + storedZlib(byteArrayOf()))) {
            failure(TerminalGraphicsDecodeError.DECOMPRESSION_FAILED) { bounded(bad) }
        }
        allocations.clear()
        failure(TerminalGraphicsDecodeError.DIMENSIONS_REQUIRED) { bounded(width = 0) }
        failure(TerminalGraphicsDecodeError.RESOURCE_LIMIT) { bounded(decodedLimit = 3) }
        failure(TerminalGraphicsDecodeError.DECOMPRESSION_FAILED) { bounded(corrupt, width = 0) }
        assertTrue(allocations.isEmpty(), "Invalid dimensions need no retained decompression output")
    }

    @Test fun discardedFrameTailStillObeysInflationWorkLimit() {
        val fixture = terminalGraphicsZlibFixtures().first { it.name == "zlib_4_6_0" }
        val encoded = Base64.decode(fixture.zlib)
        failure(TerminalGraphicsDecodeError.DECOMPRESSION_FAILED) {
            decodeTerminalGraphicsImage(32, 1, 1, true, encoded,
                TerminalGraphicsDecodeLimits(200, 4), animationFrame = true,
                allocateInflated = { size -> assertTrue(size <= 4); ByteArray(size) })
        }
    }

    @Test fun pngRejectsInvalidChunkOrderSamplesFiltersAndInflatedLength() {
        val header = ByteArray(13).also { writeInt(it, 0, 1); writeInt(it, 4, 1); it[8] = 8; it[9] = 6 }
        val signature = byteArrayOf(137.toByte(),80,78,71,13,10,26,10)
        val ihdr = pngChunk("IHDR", header)
        val idat = pngChunk("IDAT", storedZlib(byteArrayOf(0,10,20,30,40)))
        val end = pngChunk("IEND", byteArrayOf())
        fun rejected(vararg chunks: ByteArray) = failure(TerminalGraphicsDecodeError.INVALID_DATA) {
            decode(100, signature + chunks.fold(byteArrayOf()) { a, b -> a + b })
        }
        rejected(idat, ihdr, end)
        rejected(ihdr, ihdr, idat, end)
        rejected(ihdr, end)
        rejected(ihdr, pngChunk("WHAT", byteArrayOf()), idat, end)
        rejected(ihdr, idat, pngChunk("vpAg", byteArrayOf()), idat, end)
        rejected(ihdr, pngChunk("tRNS", byteArrayOf(0,0)), idat, end)
        for (raw in listOf(byteArrayOf(5,10,20,30,40), byteArrayOf(0,10,20,30), byteArrayOf(0,10,20,30,40,50))) {
            rejected(ihdr, pngChunk("IDAT", storedZlib(raw)), end)
        }
        for ((index, value) in listOf(8 to 3, 9 to 1, 10 to 1, 11 to 1, 12 to 2)) {
            val badHeader = header.copyOf(); badHeader[index] = value.toByte()
            rejected(pngChunk("IHDR", badHeader), idat, end)
        }
        val paletteHeader = header.copyOf(); paletteHeader[9] = 3
        val paletteIhdr = pngChunk("IHDR", paletteHeader)
        val indexed = pngChunk("IDAT", storedZlib(byteArrayOf(0, 1)))
        rejected(paletteIhdr, indexed, end) // Missing palette.
        rejected(paletteIhdr, pngChunk("PLTE", byteArrayOf(0,0,0)), indexed, end) // Index out of range.
        rejected(paletteIhdr, pngChunk("PLTE", byteArrayOf(0,0)), indexed, end)
        rejected(paletteIhdr, pngChunk("tRNS", byteArrayOf(0)), pngChunk("PLTE", byteArrayOf(0,0,0)), indexed, end)
        assertContentEquals(byteArrayOf(10,20,30,40), decode(100, signature + ihdr + idat + end).data)
    }

    private fun failure(error: TerminalGraphicsDecodeError, action: () -> Unit) {
        assertEquals(error, assertFailsWith<TerminalGraphicsDecodeException>(block = action).error)
    }
}

class TerminalGraphicsZlibTest {
    @Test fun imagePayloadRejectsTrailingBytesAndConcatenatedStreams() {
        val stream = storedZlib(byteArrayOf(1,2,3))
        for (suffix in listOf(byteArrayOf(0), "trailing".encodeToByteArray(), stream)) {
            assertFailsWith<TerminalGraphicsZlibException> { inflate(stream + suffix, 100) }
        }
    }

    @Test fun storedFixedDynamicOverlappingCopiesAndMultipleBlocksMatchZlib() {
        for (fixture in terminalGraphicsZlibFixtures()) {
            val pattern = Base64.decode(fixture.pattern)
            val expected = ByteArray(pattern.size * fixture.count) { pattern[it % pattern.size] }
            assertContentEquals(expected, inflate(Base64.decode(fixture.zlib), expected.size), fixture.name)
            if (expected.isNotEmpty()) assertFailsWith<TerminalGraphicsZlibException>(fixture.name) {
                inflate(Base64.decode(fixture.zlib), expected.size - 1)
            }
        }
    }

    @Test fun corruptedStreamsAreRejectedLikeZlib() {
        for ((index, fixture) in terminalGraphicsInvalidZlibFixtures().withIndex()) {
            assertFailsWith<TerminalGraphicsZlibException>("invalid zlib $index") { inflate(Base64.decode(fixture), 100_000) }
        }
    }

    @Test fun smallLimitStopsExpansionBeforeDeliveringExcessBytes() {
        val fixture = terminalGraphicsZlibFixtures().first { it.name == "zlib_4_6_0" }
        val compressed = Base64.decode(fixture.zlib)
        var input = 0; var output = 0
        assertFailsWith<TerminalGraphicsZlibException> {
            inflateTerminalGraphicsZlib({ if (input < compressed.size) compressed[input++].toInt() and 255 else -1 }, 32) { output++ }
        }
        assertTrue(output <= 32)
        assertTrue(input < compressed.size)
    }

    private fun inflate(data: ByteArray, limit: Int): ByteArray {
        var offset = 0; var size = 0
        val output = ByteArray(limit)
        inflateTerminalGraphicsZlib({ if (offset < data.size) data[offset++].toInt() and 255 else -1 }, limit) { output[size++] = it.toByte() }
        return output.copyOf(size)
    }
}

private fun writeInt(data: ByteArray, offset: Int, value: Int) {
    for (i in 0..3) data[offset + i] = (value ushr (24 - i * 8)).toByte()
}

private fun pngChunk(type: String, body: ByteArray): ByteArray {
    val data = ByteArray(body.size + 12)
    writeInt(data, 0, body.size); type.encodeToByteArray().copyInto(data, 4); body.copyInto(data, 8)
    var crc = -1
    for (byte in data.copyOfRange(4, data.size - 4)) {
        crc = crc xor (byte.toInt() and 255)
        repeat(8) { crc = (crc ushr 1) xor (if (crc and 1 != 0) 0xedb88320.toInt() else 0) }
    }
    writeInt(data, data.size - 4, crc.inv())
    return data
}

private fun storedZlib(bytes: ByteArray): ByteArray {
    require(bytes.size <= 65535)
    val output = ByteArray(bytes.size + 11)
    output[0] = 0x78; output[1] = 0x01; output[2] = 0x01
    output[3] = bytes.size.toByte(); output[4] = (bytes.size ushr 8).toByte()
    output[5] = bytes.size.inv().toByte(); output[6] = (bytes.size.inv() ushr 8).toByte()
    bytes.copyInto(output, 7)
    var a = 1; var b = 0
    for (byte in bytes) { a = (a + (byte.toInt() and 255)) % 65521; b = (b + a) % 65521 }
    writeInt(output, output.size - 4, (b shl 16) or a)
    return output
}
