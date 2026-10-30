// Hover identity and decoration rules follow Ghostty RenderState.linkCells and renderer/generic.zig (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Pinned sources and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

/** Coordinates are viewport-relative; each range covers one visible row with an exclusive end. */
internal data class TerminalLinkHover(val target: TerminalLinkTarget, val ranges: List<TerminalSelectionRange>) {
    fun contains(column: Int, row: Int): Boolean {
        var low = 0; var high = ranges.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val range = ranges[middle]
            when {
                row < range.start.line || row == range.start.line && column < range.start.column -> high = middle - 1
                row > range.start.line || column >= range.end.column -> low = middle + 1
                else -> return true
            }
        }
        return false
    }
}

internal fun terminalLinkHoverAt(column: Int, row: Int, first: Int, rows: Int, last: Int,
    line: (Int) -> TerminalLine, osc8Enabled: Boolean): TerminalLinkHover? {
    if (row !in 0 until rows || column !in 0 until line(first + row).columns) return null
    val hovered = line(first + row)
    val x = if (hovered.width(column) == 0) (column - 1).coerceAtLeast(0) else column
    val identity = if (osc8Enabled) hovered.hyperlinkIdentity(x) else null
    if (identity != null) {
        val ranges = mutableListOf<TerminalSelectionRange>()
        for (y in 0 until rows) {
            val current = line(first + y)
            var start = -1
            for (col in 0..current.columns) {
                val matches = col < current.columns && identity.sameIdentity(current.hyperlinkIdentity(col))
                if (matches && start < 0) start = col
                if (!matches && start >= 0) {
                    ranges += TerminalSelectionRange(TerminalCellPosition(y, start), TerminalCellPosition(y, col))
                    start = -1
                }
            }
        }
        return TerminalLinkHover(TerminalLinkTarget(identity.value.uri, true), ranges)
    }
    val detected = terminalDetectedLinkAt(first + row, x, 0, last, line) ?: return null
    val ranges = (maxOf(first, detected.range.start.line)..minOf(first + rows - 1, detected.range.end.line)).map { y ->
        TerminalSelectionRange(TerminalCellPosition(y - first, if (y == detected.range.start.line) detected.range.start.column else 0),
            TerminalCellPosition(y - first, if (y == detected.range.end.line) detected.range.end.column else line(y).columns))
    }
    return TerminalLinkHover(TerminalLinkTarget(detected.uri, false), ranges)
}

internal fun terminalLinkHoverStyle(style: Int, hovered: Boolean): Int = if (!hovered) style else
    (style and TerminalStyle.UNDERLINES.inv()) or
        if (style and TerminalStyle.UNDERLINES == TerminalStyle.UNDERLINE) TerminalStyle.DOUBLE_UNDERLINE else TerminalStyle.UNDERLINE
