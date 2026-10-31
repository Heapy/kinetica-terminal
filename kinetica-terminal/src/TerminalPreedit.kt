// Adapted from Ghostty renderer/State.zig Preedit and Surface.preeditCallback (1.4.0).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors. MIT licensed.
// See third-party/ghostty.json and third-party/GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

/** Immutable display-only IME text. Live terminal cells and copied text never contain it. */
internal class TerminalPreedit private constructor(private val points: List<Int>) {
    val width: Double = points.sumOf { terminalCharacterWidth(it).toDouble() }
    data class Range(val start: Int, val end: Int, val offset: Int)

    fun range(cursor: Int, lastColumn: Int): Range {
        val available = lastColumn - cursor + 1
        var width = 0
        var offset = 0
        for (index in points.indices.reversed()) {
            width += terminalCharacterWidth(points[index])
            if (width > available) { offset = index; break }
        }
        val end = if (width > 0) cursor + width - 1 else cursor
        val shift = (end - lastColumn).coerceAtLeast(0)
        return Range((cursor - shift).coerceAtLeast(0), end - shift, offset)
    }

    fun line(original: TerminalLine, range: Range): TerminalLine = original.snapshot().also { line ->
        // Replacing half of an underlying wide glyph cannot leave an orphaned half.
        if (range.start > 0 && line.width(range.start) == 0 && line.width(range.start - 1) == 2)
            line.replaceWithBlank(range.start - 1)
        if (range.end + 1 < line.columns && line.width(range.end + 1) == 0)
            line.replaceWithBlank(range.end + 1)
        var column = range.start
        for (index in range.offset until points.size) {
            if (column >= line.columns) break
            val point = points[index]
            val width = terminalCharacterWidth(point)
            line.put(column, point, width, TerminalStyle.DEFAULT, TerminalStyle.DEFAULT, TerminalStyle.UNDERLINE)
            if (width == 2 && column + 1 < line.columns)
                line.put(column + 1, 0, 0, TerminalStyle.DEFAULT, TerminalStyle.DEFAULT, TerminalStyle.UNDERLINE)
            column += width
        }
    }

    companion object {
        fun from(text: String): TerminalPreedit? = text.terminalCodepoints()
            .filter { terminalCharacterWidth(it) > 0 }.takeIf { it.isNotEmpty() }?.let(::TerminalPreedit)
    }
}
