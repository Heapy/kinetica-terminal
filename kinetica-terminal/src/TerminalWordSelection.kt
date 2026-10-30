// Word selection adapted from Ghostty Screen.selectWord/selectWordBetween (MIT).
// Copyright (c) 2024 Mitchell Hashimoto, Ghostty contributors.
// Pinned sources and license: ../third-party/ghostty.json and GHOSTTY-LICENSE.
package io.heapy.kinetica.terminal

internal const val terminalDefaultWordChars: String = "\t '\"│`|:;,()[]{}<>$"
internal val terminalDefaultWordBoundaries: IntArray = terminalWordBoundaryCodePoints(terminalDefaultWordChars)

internal fun terminalWordBoundaryCodePoints(value: String): IntArray = buildList {
    add(0)
    var index = 0
    while (index < value.length) {
        val first = value[index++]
        val code = if (first.isHighSurrogate()) {
            require(index < value.length && value[index].isLowSurrogate()) { "Word boundaries must contain valid Unicode code points" }
            0x10000 + ((first.code - 0xd800) shl 10) + value[index++].code - 0xdc00
        } else {
            require(!first.isLowSurrogate()) { "Word boundaries must contain valid Unicode code points" }
            first.code
        }
        add(code)
    }
}.toIntArray()

internal fun terminalWordRange(point: TerminalCellPosition, first: Int, last: Int,
    line: (Int) -> TerminalLine, boundaries: IntArray): TerminalSelectionRange? {
    fun code(point: TerminalCellPosition): Int? {
        var row = line(point.line)
        var x = point.column
        if (row.isSpacerHead(x)) {
            if (point.line == last) return null
            row = line(point.line + 1); x = 0
            if (row.width(x) != 2) return null
        } else if (row.width(x) == 0) x--
        return if (x >= 0 && row.hasText(x)) row.codePoint(x) else null
    }
    val isBoundary = (code(point) ?: return null) in boundaries
    var start = point
    var end = point
    while (true) {
        val previous = if (start.column > 0) start.copy(column = start.column - 1) else {
            if (start.line == first || !line(start.line - 1).wrapped) break
            TerminalCellPosition(start.line - 1, line(start.line - 1).columns - 1)
        }
        if (((code(previous) ?: break) in boundaries) != isBoundary) break
        start = previous
    }
    while (true) {
        val next = if (end.column < line(end.line).columns - 1) end.copy(column = end.column + 1) else {
            if (end.line == last || !line(end.line).wrapped) break
            TerminalCellPosition(end.line + 1, 0)
        }
        if (((code(next) ?: break) in boundaries) != isBoundary) break
        end = next
    }
    return TerminalSelectionRange(start, end.copy(column = end.column + 1))
}

internal fun terminalWordBetween(start: TerminalCellPosition, end: TerminalCellPosition,
    first: Int, last: Int, line: (Int) -> TerminalLine, boundaries: IntArray): TerminalSelectionRange? {
    val forward = start < end
    var point = start
    while (true) {
        terminalWordRange(point, first, last, line, boundaries)?.let { return it }
        if (point == end) return null
        point = if (forward) {
            if (point.column < line(point.line).columns - 1) point.copy(column = point.column + 1)
            else TerminalCellPosition(point.line + 1, 0)
        } else {
            if (point.column > 0) point.copy(column = point.column - 1)
            else TerminalCellPosition(point.line - 1, line(point.line - 1).columns - 1)
        }
    }
}
