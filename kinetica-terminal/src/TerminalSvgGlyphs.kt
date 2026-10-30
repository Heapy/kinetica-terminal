package io.heapy.kinetica.terminal

/** Only SVG glyph membership is retained. Platform font APIs own XML/gzip rendering. */
internal class TerminalSvgGlyphs private constructor(private val ranges: IntArray) {
    operator fun contains(glyph: Int): Boolean {
        if (glyph !in 0..65535) return false
        var low = 0; var high = ranges.size / 2 - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                glyph < ranges[mid * 2] -> high = mid - 1
                glyph > ranges[mid * 2 + 1] -> low = mid + 1
                else -> return true
            }
        }
        return false
    }

    companion object {
        val EMPTY = TerminalSvgGlyphs(IntArray(0))
        fun read(data: ByteArray): TerminalSvgGlyphs = read(data.size) { data[it].toInt() and 255 }

        /** Read a bounded table without copying its potentially large SVG documents. */
        fun read(size: Int, byte: (Int) -> Int): TerminalSvgGlyphs {
            fun u16(index: Int) = (byte(index) shl 8) or byte(index + 1)
            fun u32(index: Int) = (u16(index).toLong() shl 16) or u16(index + 2).toLong()
            if (size < 10 || u16(0) != 0) return EMPTY
            val offset = u32(2)
            if (offset < 10 || offset > size.toLong() - 2) return EMPTY
            val start = offset.toInt()
            val count = u16(start)
            if (count == 0 || count > (size - start - 2) / 12) return EMPTY
            val documents = offset + 2 + count * 12
            val ranges = IntArray(count * 2)
            var previous = -1
            for (i in 0 until count) {
                val record = start + 2 + i * 12
                val first = u16(record); val last = u16(record + 2)
                val document = offset + u32(record + 4); val length = u32(record + 8)
                if (first <= previous || first > last || document < documents || length == 0L || document > size.toLong() - length) return EMPTY
                ranges[i * 2] = first; ranges[i * 2 + 1] = last; previous = last
            }
            return TerminalSvgGlyphs(ranges)
        }
    }
}
