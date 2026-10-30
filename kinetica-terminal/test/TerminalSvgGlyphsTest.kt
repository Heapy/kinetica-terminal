package io.heapy.kinetica.terminal

import kotlin.test.*

class TerminalSvgGlyphsTest {
    @Test fun membershipPreservesGapsInclusiveEndsAndSharedDocuments() {
        val data = table(0 to 0, 10 to 12, 65535 to 65535)
        val glyphs = TerminalSvgGlyphs.read(data)
        for (glyph in 0..65535) assertEquals(glyph == 0 || glyph in 10..12 || glyph == 65535, glyph in glyphs)
        assertFalse(-1 in glyphs); assertFalse(65536 in glyphs)
        data.fill(0)
        assertTrue(11 in glyphs, "Membership does not retain the original table buffer")
    }

    @Test fun truncatedTablesAndInvalidOffsetsNeverReadOutsideTheInput() {
        val valid = table(10 to 12)
        for (length in 0 until valid.size) {
            val result = TerminalSvgGlyphs.read(length) { index ->
                assertTrue(index in 0 until length); valid[index].toInt() and 255
            }
            assertFalse(10 in result)
        }
        for (offset in listOf(0L, 9L, valid.size.toLong(), 0xffffffffL)) {
            val copy = valid.copyOf(); put32(copy, 2, offset)
            assertFalse(10 in TerminalSvgGlyphs.read(copy))
        }
        for ((index, value) in listOf(16 to 0L, 16 to 0xffffffffL, 20 to 0L, 20 to 0xffffffffL)) {
            val copy = valid.copyOf(); put32(copy, index, value)
            assertFalse(10 in TerminalSvgGlyphs.read(copy))
        }
    }

    @Test fun unsupportedVersionsUnsortedOverlappingAndEmptyListsAreRejected() {
        for (data in listOf(table(12 to 10), table(10 to 12, 12 to 14), table(20 to 21, 10 to 12), table()))
            assertFalse(12 in TerminalSvgGlyphs.read(data))
        val version = table(10 to 12); version[1] = 1
        assertFalse(12 in TerminalSvgGlyphs.read(version))
    }

    private fun table(vararg ranges: Pair<Int, Int>): ByteArray {
        val documentOffset = 2 + ranges.size * 12
        val result = ByteArray(10 + documentOffset + 6)
        put32(result, 2, 10); put16(result, 10, ranges.size)
        for ((i, range) in ranges.withIndex()) {
            val record = 12 + i * 12
            put16(result, record, range.first); put16(result, record + 2, range.second)
            put32(result, record + 4, documentOffset.toLong()); put32(result, record + 8, 6)
        }
        "<svg/>".encodeToByteArray().copyInto(result, 10 + documentOffset)
        return result
    }
    private fun put16(data: ByteArray, index: Int, value: Int) { data[index] = (value ushr 8).toByte(); data[index + 1] = value.toByte() }
    private fun put32(data: ByteArray, index: Int, value: Long) { put16(data, index, (value ushr 16).toInt()); put16(data, index + 2, value.toInt()) }
}
